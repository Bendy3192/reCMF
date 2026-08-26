/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import androidx.core.content.getSystemService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A device the user could pair with. */
data class DiscoveredWatch(
    val address: String,
    val name: String?,
    /** Null for a device that is known from the bond list but has not been seen in a scan. */
    val rssi: Int?,
    /** Already paired in Android's Bluetooth settings. */
    val isBonded: Boolean,
) {
    /** Whether the name matches what a CMF Watch Pro calls itself. */
    val looksLikeWatch: Boolean get() = name != null && WATCH_NAME.containsMatchIn(name)

    companion object {
        /**
         * What a CMF Watch Pro advertises as, e.g. `CMF Watch Pro 2-7219`. Used only to
         * sort the list — never to hide anything, since a device can turn up unnamed.
         *
         * Deliberately here rather than on [WatchScanner]: that companion builds
         * Bluetooth objects, and touching it from a plain JVM test hits the android.jar
         * stubs. Which name looks like a watch is not a fact about the radio.
         */
        val WATCH_NAME = Regex("CMF Watch Pro|Watch Pro", RegexOption.IGNORE_CASE)
    }
}

/**
 * Finds candidate watches, from the bond list and from a BLE scan.
 *
 * Two things here are deliberate, and both were learned the hard way:
 *
 * **No service-UUID filter.** The obvious filter is the command service, and it finds
 * nothing: the watch does not put its GATT services in its advertisement. Gadgetbridge
 * matches these devices on their name for the same reason. So the scan is unfiltered and
 * the list is merely *sorted* by how much each result looks like a watch — the user can
 * still pick a device whose name did not come through.
 *
 * **Bonded devices are listed without scanning.** A watch that has been paired in
 * Android's Bluetooth settings — which is the normal state for one that has been used
 * with the stock app — may not advertise at all while it is connected to something else.
 * It is already known by address, so it is offered immediately.
 */
@SuppressLint("MissingPermission")
class WatchScanner(private val context: Context) {

    private val adapter get() = context.getSystemService<BluetoothManager>()?.adapter

    /** Devices already paired at the OS level. Empty if Bluetooth is off or unpermitted. */
    fun bonded(): List<DiscoveredWatch> = try {
        adapter?.bondedDevices.orEmpty().map { device ->
            DiscoveredWatch(
                address = device.address,
                name = device.name,
                rssi = null,
                isBonded = true,
            )
        }.sortedWith(ORDER)
    } catch (e: SecurityException) {
        emptyList()
    }

    /**
     * Emits the candidate list, starting from the bond list and growing as advertisements
     * arrive. Stops the scan when collection ends, so leaving the screen stops the radio.
     */
    fun scan(): Flow<List<DiscoveredWatch>> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth is unavailable"))
            return@callbackFlow
        }

        val found = LinkedHashMap<String, DiscoveredWatch>()
        bonded().forEach { found[it.address] = it }
        trySend(found.values.sortedWith(ORDER))

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val address = result.device.address
                val name = result.device.name ?: result.scanRecord?.deviceName

                found[address] = DiscoveredWatch(
                    address = address,
                    // Keep a name we already had: an advertisement often carries none.
                    name = name ?: found[address]?.name,
                    rssi = result.rssi,
                    isBonded = found[address]?.isBonded == true,
                )
                trySend(found.values.sortedWith(ORDER))
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("Bluetooth scan failed with code $errorCode"))
            }
        }

        scanner.startScan(null, SCAN_SETTINGS, callback)

        awaitClose { scanner.stopScan(callback) }
    }

    companion object {
        private val SCAN_SETTINGS by lazy {
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
        }

        /** Likely watches first, then already-paired devices, then the strongest signal. */
        private val ORDER = compareByDescending<DiscoveredWatch> { it.looksLikeWatch }
            .thenByDescending { it.isBonded }
            .thenByDescending { it.rssi ?: Int.MIN_VALUE }
            .thenBy { it.address }
    }
}
