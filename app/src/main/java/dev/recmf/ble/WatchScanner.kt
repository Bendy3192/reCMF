/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import androidx.core.content.getSystemService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import android.os.ParcelUuid

/** A watch seen during a scan. */
data class DiscoveredWatch(
    val address: String,
    val name: String?,
    val rssi: Int,
)

/**
 * Scans for watches advertising the CMF command service.
 *
 * Filtered by service UUID rather than by name: filtering in the scanner is handled by
 * the Bluetooth controller, which is far cheaper than waking this process for every
 * advertisement in range — and the user's phone sees a lot of them.
 *
 * The flow stops the scan when it is cancelled, so collecting it in a screen scope means
 * the radio stops as soon as the user leaves the screen.
 */
@SuppressLint("MissingPermission")
class WatchScanner(private val context: Context) {

    fun scan(): Flow<List<DiscoveredWatch>> = callbackFlow {
        val scanner = context.getSystemService<BluetoothManager>()?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth is unavailable"))
            return@callbackFlow
        }

        val found = LinkedHashMap<String, DiscoveredWatch>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val watch = DiscoveredWatch(
                    address = result.device.address,
                    name = result.device.name ?: result.scanRecord?.deviceName,
                    rssi = result.rssi,
                )
                found[watch.address] = watch
                trySend(found.values.sortedByDescending { it.rssi })
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("Bluetooth scan failed with code $errorCode"))
            }
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(CmfUuids.SERVICE_COMMAND))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(listOf(filter), settings, callback)

        awaitClose { scanner.stopScan(callback) }
    }
}
