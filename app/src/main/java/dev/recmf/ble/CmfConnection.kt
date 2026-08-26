/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 * See LICENSE and NOTICE at the repository root.
 */
package dev.recmf.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.getSystemService
import dev.recmf.protocol.CmfAuthAction
import dev.recmf.protocol.CmfAuthenticator
import dev.recmf.protocol.CmfCodec
import dev.recmf.protocol.CmfCommand
import dev.recmf.protocol.CmfDecoded
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** A decoded command from the watch. */
data class CmfMessage(val cmd: CmfCommand, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is CmfMessage && cmd == other.cmd && payload.contentEquals(other.payload))

    override fun hashCode(): Int = 31 * cmd.hashCode() + payload.contentHashCode()
}

/**
 * Owns the GATT link to one watch: connection, handshake, and a serialized queue of
 * characteristic operations.
 *
 * Three things here exist specifically to keep the hosting process alive and healthy
 * over days of uptime, which the stock app does not manage:
 *
 * 1. **Every GATT operation is queued and awaited.** Android's stack silently drops a
 *    write issued while another is outstanding, and a dropped write during the handshake
 *    leaves the link half-open forever.
 * 2. **Every operation has a timeout.** A watch that goes out of range mid-write never
 *    delivers its callback; without a timeout the queue wedges and the app looks alive
 *    while doing nothing.
 * 3. **[BluetoothGatt.close] is called on every teardown path.** An unclosed client
 *    leaks a binder registration and a native connection per reconnect — over a day of
 *    range flapping that is what eventually gets the process killed.
 *
 * All mutable state is confined to [scope]'s single-threaded dispatcher; GATT callbacks
 * hand their arguments over rather than touching it directly.
 */
@SuppressLint("MissingPermission")
class CmfConnection(
    private val context: Context,
    private val scope: CoroutineScope,
    private val phoneName: String = "${Build.MANUFACTURER} ${Build.MODEL}",
    private val onAuthKeyNegotiated: (ByteArray) -> Unit,
) {
    private val _state = MutableStateFlow(ConnectionState.IDLE)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<CmfMessage>(
        extraBufferCapacity = MESSAGE_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Decoded commands from the watch. Bounded and drop-oldest: a slow collector must
     * never be able to make the radio's backlog grow without limit.
     */
    val messages: SharedFlow<CmfMessage> = _messages.asSharedFlow()

    private val _failures = MutableSharedFlow<ConnectionFailure>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val failures: SharedFlow<ConnectionFailure> = _failures.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    private var queueJob: Job? = null
    private var operations: Channel<QueuedOperation>? = null

    /** Completed by the GATT callback for whichever operation the queue is running. */
    private var pending: CompletableDeferred<Boolean>? = null

    private val commandCodec = CmfCodec()
    private val dataCodec = CmfCodec()
    private var authenticator: CmfAuthenticator? = null

    private var commandWrite: BluetoothGattCharacteristic? = null
    private var dataWrite: BluetoothGattCharacteristic? = null
    private var shellWrite: BluetoothGattCharacteristic? = null

    /**
     * Opens a link to [address] and runs the handshake.
     *
     * @param authKey the stored long-term key, or null to pair from scratch.
     */
    fun connect(address: String, authKey: ByteArray?) {
        scope.launch {
            teardown()

            val adapter = context.getSystemService<BluetoothManager>()?.adapter
            if (adapter == null || !adapter.isEnabled) {
                _state.value = ConnectionState.WAITING
                _failures.emit(ConnectionFailure.Unavailable("Bluetooth is off"))
                return@launch
            }

            val device = try {
                adapter.getRemoteDevice(address)
            } catch (e: IllegalArgumentException) {
                _state.value = ConnectionState.IDLE
                _failures.emit(ConnectionFailure.Unavailable("Not a Bluetooth address: ${e.message}"))
                return@launch
            }

            authenticator = CmfAuthenticator(phoneName, authKey)
            _state.value = ConnectionState.CONNECTING

            val channel = Channel<QueuedOperation>(capacity = OPERATION_QUEUE_CAPACITY)
            operations = channel
            queueJob = scope.launch { runOperations(channel) }

            // autoConnect = true hands the retry to the Bluetooth stack, which can wait
            // for the watch to reappear without this process staying awake to poll.
            gatt = device.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
        }
    }

    /** Closes the link and releases the GATT client. Safe to call when already closed. */
    fun disconnect() {
        scope.launch {
            teardown()
            _state.value = ConnectionState.IDLE
        }
    }

    /** Queues [cmd] on the command characteristic. Returns false if it could not be written. */
    suspend fun send(cmd: CmfCommand, payload: ByteArray = ByteArray(0)): Boolean {
        val characteristic = commandWrite ?: return false
        return writeFrames(characteristic, commandCodec.encode(cmd, payload))
    }

    /** Queues [cmd] on the bulk-data characteristic. */
    suspend fun sendOnDataChannel(cmd: CmfCommand, payload: ByteArray = ByteArray(0)): Boolean {
        val characteristic = dataWrite ?: return false
        return writeFrames(characteristic, dataCodec.encode(cmd, payload))
    }

    private suspend fun writeFrames(
        characteristic: BluetoothGattCharacteristic,
        frames: List<ByteArray>,
    ): Boolean {
        for (frame in frames) {
            // Each frame must land before the next is issued, so a failure stops the
            // rest rather than sending the watch a payload with a hole in it.
            if (!enqueue(GattOperation.Write(characteristic, frame))) return false
        }
        return true
    }

    // region GATT callbacks

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            scope.launch {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        _state.value = ConnectionState.INITIALIZING
                        // Ask for the largest MTU the watch will grant before anything
                        // else: chunk sizing depends on it, and re-chunking mid-transfer
                        // is not something the protocol allows for.
                        enqueue(GattOperation.RequestMtu(REQUESTED_MTU))
                        enqueue(GattOperation.DiscoverServices)
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        val failure = if (status == BluetoothGatt.GATT_SUCCESS) {
                            ConnectionFailure.LinkLost
                        } else {
                            ConnectionFailure.GattError(status)
                        }
                        // autoConnect keeps the stack retrying, so hold the GATT client
                        // open and only reset the protocol state.
                        resetProtocolState()
                        _state.value = ConnectionState.WAITING
                        _failures.emit(failure)
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            scope.launch {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    // The ATT MTU includes a 3-byte opcode-and-handle header that the
                    // payload does not get to use.
                    val usable = (mtu - ATT_HEADER_SIZE).coerceAtLeast(CmfCodec.MIN_MTU)
                    commandCodec.mtu = usable
                    dataCodec.mtu = usable
                }
                completePending(status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            scope.launch {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    completePending(false)
                    _failures.emit(ConnectionFailure.GattError(status))
                    return@launch
                }
                completePending(true)
                onServicesReady(gatt)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            scope.launch { completePending(status == BluetoothGatt.GATT_SUCCESS) }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            scope.launch { completePending(status == BluetoothGatt.GATT_SUCCESS) }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            val uuid = characteristic.uuid
            scope.launch { onNotification(uuid, value) }
        }

        @Deprecated("Superseded by the overload carrying the value, kept for API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return

            val uuid = characteristic.uuid
            // The framework reuses this buffer for the next notification, so copy now.
            val value = characteristic.value?.copyOf() ?: return
            scope.launch { onNotification(uuid, value) }
        }
    }

    // endregion

    private suspend fun onServicesReady(gatt: BluetoothGatt) {
        val commandService = gatt.getService(CmfUuids.SERVICE_COMMAND)
        val dataService = gatt.getService(CmfUuids.SERVICE_DATA)
        val shellService = gatt.getService(CmfUuids.SERVICE_SHELL)

        commandWrite = commandService?.getCharacteristic(CmfUuids.CHARACTERISTIC_COMMAND_WRITE)
        dataWrite = dataService?.getCharacteristic(CmfUuids.CHARACTERISTIC_DATA_WRITE)
        shellWrite = shellService?.getCharacteristic(CmfUuids.CHARACTERISTIC_SHELL_WRITE)

        val commandRead = commandService?.getCharacteristic(CmfUuids.CHARACTERISTIC_COMMAND_READ)
        val dataRead = dataService?.getCharacteristic(CmfUuids.CHARACTERISTIC_DATA_READ)
        val shellRead = shellService?.getCharacteristic(CmfUuids.CHARACTERISTIC_SHELL_READ)

        if (commandWrite == null || commandRead == null) {
            // A stale service cache, usually. Ask the stack to rediscover next time
            // rather than sitting in a state we cannot act from.
            _failures.emit(ConnectionFailure.Unavailable("Watch did not expose the command service"))
            _state.value = ConnectionState.WAITING
            return
        }

        enqueue(GattOperation.EnableNotifications(commandRead))
        dataRead?.let { enqueue(GattOperation.EnableNotifications(it)) }
        shellRead?.let { enqueue(GattOperation.EnableNotifications(it)) }

        _state.value = ConnectionState.AUTHENTICATING
        authenticator?.start()?.let { runAuthActions(it) }
    }

    private suspend fun onNotification(uuid: UUID, value: ByteArray) {
        if (uuid == CmfUuids.CHARACTERISTIC_SHELL_READ) {
            authenticator?.onShellData(value)?.let { runAuthActions(it) }
            return
        }

        val codec = when (uuid) {
            CmfUuids.CHARACTERISTIC_COMMAND_READ -> commandCodec
            CmfUuids.CHARACTERISTIC_DATA_READ -> dataCodec
            else -> return
        }

        when (val decoded = codec.decode(value)) {
            is CmfDecoded.Pending -> Unit

            is CmfDecoded.Dropped -> Log.w(TAG, "Dropped ${decoded.cmd ?: "frame"}: ${decoded.reason}")

            is CmfDecoded.Command -> {
                authenticator?.onCommand(decoded.cmd, decoded.payload)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { runAuthActions(it) }

                _messages.emit(CmfMessage(decoded.cmd, decoded.payload))
            }
        }
    }

    private suspend fun runAuthActions(actions: List<CmfAuthAction>) {
        for (action in actions) {
            when (action) {
                is CmfAuthAction.UseSessionKey -> {
                    commandCodec.sessionKey = action.key
                    dataCodec.sessionKey = action.key
                }

                is CmfAuthAction.PersistAuthKey -> onAuthKeyNegotiated(action.key)

                is CmfAuthAction.Send -> send(action.cmd, action.payload)

                is CmfAuthAction.WriteShell -> {
                    val characteristic = shellWrite
                    if (characteristic == null) {
                        _failures.emit(ConnectionFailure.Unavailable("Watch has no pairing channel"))
                        _state.value = ConnectionState.WAITING
                    } else {
                        enqueue(GattOperation.Write(characteristic, action.text.toByteArray()))
                    }
                }

                CmfAuthAction.Authenticated -> _state.value = ConnectionState.READY

                is CmfAuthAction.Failed -> {
                    Log.w(TAG, "Handshake failed: ${action.reason}")
                    _failures.emit(ConnectionFailure.AuthRejected)
                    _state.value = ConnectionState.WAITING
                }
            }
        }
    }

    // region Operation queue

    private sealed interface GattOperation {
        class Write(val characteristic: BluetoothGattCharacteristic, val value: ByteArray) : GattOperation
        class EnableNotifications(val characteristic: BluetoothGattCharacteristic) : GattOperation
        class RequestMtu(val mtu: Int) : GattOperation
        data object DiscoverServices : GattOperation
    }

    private class QueuedOperation(
        val operation: GattOperation,
        val result: CompletableDeferred<Boolean>,
    )

    /**
     * Submits [operation] and suspends until the stack reports its outcome.
     *
     * Returns false rather than suspending indefinitely when the queue is full: a full
     * queue means the link is not draining, and piling more work behind a dead
     * connection only delays noticing.
     */
    private suspend fun enqueue(operation: GattOperation): Boolean {
        val channel = operations ?: return false
        val queued = QueuedOperation(operation, CompletableDeferred())

        if (channel.trySend(queued).isFailure) {
            Log.w(TAG, "Operation queue is full; dropping ${operation::class.simpleName}")
            return false
        }

        // Bounded even if the queue worker goes away between the send and the result —
        // a caller waiting on a write must never be able to wedge for good.
        return withTimeoutOrNull(ENQUEUE_TIMEOUT_MILLIS) { queued.result.await() } ?: false
    }

    /**
     * Runs one operation at a time. Android's GATT stack silently drops a second
     * operation issued while the first is outstanding, so this serialization is not an
     * optimization — without it the handshake fails at random.
     */
    private suspend fun runOperations(channel: Channel<QueuedOperation>) {
        for (queued in channel) {
            val deferred = CompletableDeferred<Boolean>()
            pending = deferred

            val ok = if (!issue(queued.operation)) {
                false
            } else {
                withTimeoutOrNull(OPERATION_TIMEOUT_MILLIS) { deferred.await() }
                    ?: false.also { Log.w(TAG, "${queued.operation::class.simpleName} timed out") }
            }

            pending = null
            queued.result.complete(ok)

            // Some stacks need a beat between operations; without it back-to-back
            // writes during the handshake intermittently fail to issue at all.
            delay(INTER_OPERATION_DELAY_MILLIS)
        }

        drain(channel)
    }

    /** Fails every operation still queued, so no caller is left awaiting a dead queue. */
    private fun drain(channel: Channel<QueuedOperation>) {
        while (true) {
            val queued = channel.tryReceive().getOrNull() ?: return
            queued.result.complete(false)
        }
    }

    private fun issue(operation: GattOperation): Boolean {
        val gatt = this.gatt ?: return false

        return when (operation) {
            is GattOperation.DiscoverServices -> gatt.discoverServices()
            is GattOperation.RequestMtu -> gatt.requestMtu(operation.mtu)
            is GattOperation.EnableNotifications -> enableNotifications(gatt, operation.characteristic)
            is GattOperation.Write -> writeCharacteristic(gatt, operation.characteristic, operation.value)
        }
    }

    private fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ): Boolean {
        if (!gatt.setCharacteristicNotification(characteristic, true)) return false

        val descriptor = characteristic.getDescriptor(CmfUuids.CCCD) ?: return false
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = value
                gatt.writeDescriptor(descriptor)
            }
        }
    }

    private fun writeCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean {
        val hasWriteWithResponse =
            (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0

        val writeType = if (hasWriteWithResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.writeType = writeType
                characteristic.value = value
                gatt.writeCharacteristic(characteristic)
            }
        }
    }

    private fun completePending(success: Boolean) {
        pending?.complete(success)
    }

    // endregion

    private fun resetProtocolState() {
        commandCodec.reset()
        dataCodec.reset()
        commandCodec.sessionKey = null
        dataCodec.sessionKey = null
        commandWrite = null
        dataWrite = null
        shellWrite = null
    }

    private fun teardown() {
        val channel = operations
        operations = null
        queueJob?.cancel()
        queueJob = null
        pending?.complete(false)
        pending = null

        // Cancelling the worker means its own drain never runs, so release anything
        // still queued here instead of leaving those callers waiting on the timeout.
        channel?.close()
        channel?.let { drain(it) }

        resetProtocolState()
        authenticator = null

        // close() is what actually releases the native client; disconnect() alone
        // leaves it registered and leaks one per reconnect.
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    private companion object {
        const val TAG = "CmfConnection"

        /** Every ATT packet spends 3 bytes on the opcode and handle. */
        const val ATT_HEADER_SIZE = 3

        /** The maximum Android will negotiate; the watch grants less and we adapt. */
        const val REQUESTED_MTU = 517

        /**
         * Long enough for a slow watch to answer, short enough that a link which died
         * mid-write unblocks the queue while the user is still looking at the screen.
         */
        const val OPERATION_TIMEOUT_MILLIS = 10_000L

        /** Covers the queue wait as well as the operation itself. */
        const val ENQUEUE_TIMEOUT_MILLIS = 30_000L

        const val INTER_OPERATION_DELAY_MILLIS = 20L

        /** Deep enough for a full handshake burst, shallow enough to notice a stall. */
        const val OPERATION_QUEUE_CAPACITY = 32

        const val MESSAGE_BUFFER = 64
    }
}
