package com.example.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "BleDiscoveryEngine"

data class NearbyBleDevice(
    val device: BluetoothDevice,
    val name: String?,
    val rssi: Int,
    val lastSeenAtMs: Long
)

/**
 * Fast peer discovery for BlueSync, layered on top of (not replacing) the classic Bluetooth
 * RFCOMM transport in [BluetoothConnectionEngine]. Every BlueSync instance both advertises a
 * fixed service UUID over BLE and scans for that same UUID, so nearby phones surface in ~1s
 * instead of classic discovery's ~12s cycle.
 *
 * On almost all Android phones a single radio identity address is used for both BR/EDR and BLE
 * advertising, so the [BluetoothDevice] handed back by a BLE [ScanResult] can be passed directly
 * into the existing classic RFCOMM connect path unchanged - no new wire protocol. Devices/OEMs
 * that don't support BLE peripheral mode (advertising) simply won't be found this way; classic
 * discovery in [BluetoothConnectionEngine] remains available as the fallback.
 */
class BleDiscoveryEngine(private val context: Context) {

    companion object {
        // Random, fixed UUID identifying a BlueSync peer's advertisement. Any device
        // advertising this UUID is assumed to be running BlueSync.
        val SERVICE_UUID: UUID = UUID.fromString("8a37f9d0-9f0e-4bf0-8f6f-9b1c9a6c9e2d")
        private const val STALE_AFTER_MS = 10_000L
        private const val PRUNE_INTERVAL_MS = 3_000L
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _isBleScanning = MutableStateFlow(false)
    val isBleScanning: StateFlow<Boolean> = _isBleScanning.asStateFlow()

    private val _nearbyDevices = MutableStateFlow<List<NearbyBleDevice>>(emptyList())
    val nearbyDevices: StateFlow<List<NearbyBleDevice>> = _nearbyDevices.asStateFlow()

    // Address -> last-seen sighting. Rebuilt into _nearbyDevices (sorted strongest-first) on
    // every update so the UI can treat "closest" as "top of list", bitchat-style.
    private val seenDevices = mutableMapOf<String, NearbyBleDevice>()

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var pruneJob: Job? = null

    fun isBleSupported(): Boolean {
        return adapter != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    }

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasAdvertisePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "BLE advertising started")
            _isAdvertising.value = true
        }

        override fun onStartFailure(errorCode: Int) {
            // Common on OEMs that restrict/limit peripheral mode. Classic Bluetooth discovery
            // remains available in BluetoothConnectionEngine as a fallback for these devices.
            Log.e(TAG, "BLE advertising failed to start: $errorCode")
            _isAdvertising.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising() {
        if (_isAdvertising.value) return
        if (adapter == null || !adapter.isEnabled || !isBleSupported() || !hasAdvertisePermission()) return

        try {
            val leAdvertiser = adapter.bluetoothLeAdvertiser
            if (leAdvertiser == null) {
                Log.d(TAG, "Device has no BLE peripheral/advertising support")
                return
            }
            advertiser = leAdvertiser

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build()

            // A 128-bit service UUID already consumes most of the 31-byte legacy advertisement
            // payload, so the device name is carried in the scan response instead.
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()

            val scanResponse = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build()

            leAdvertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE advertising", e)
            _isAdvertising.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop BLE advertising", e)
        }
        advertiser = null
        _isAdvertising.value = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = try {
                if (hasConnectPermission()) result.scanRecord?.deviceName ?: device.name else null
            } catch (e: SecurityException) {
                null
            }
            seenDevices[device.address] = NearbyBleDevice(
                device = device,
                name = name,
                rssi = result.rssi,
                lastSeenAtMs = System.currentTimeMillis()
            )
            publishNearbyDevices()
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(0, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed to start: $errorCode")
            _isBleScanning.value = false
        }
    }

    private fun publishNearbyDevices() {
        _nearbyDevices.value = seenDevices.values.sortedByDescending { it.rssi }
    }

    @SuppressLint("MissingPermission")
    fun startScanning() {
        if (_isBleScanning.value) return
        if (adapter == null || !adapter.isEnabled || !isBleSupported() || !hasScanPermission()) return

        try {
            val leScanner = adapter.bluetoothLeScanner
            if (leScanner == null) {
                Log.d(TAG, "Device has no BLE scanner support")
                return
            }
            scanner = leScanner

            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            seenDevices.clear()
            publishNearbyDevices()
            leScanner.startScan(listOf(filter), settings, scanCallback)
            _isBleScanning.value = true

            pruneJob?.cancel()
            pruneJob = scope.launch {
                while (true) {
                    delay(PRUNE_INTERVAL_MS)
                    val cutoff = System.currentTimeMillis() - STALE_AFTER_MS
                    val before = seenDevices.size
                    seenDevices.entries.removeAll { it.value.lastSeenAtMs < cutoff }
                    if (seenDevices.size != before) {
                        publishNearbyDevices()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE scan", e)
            _isBleScanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop BLE scan", e)
        }
        scanner = null
        _isBleScanning.value = false
        pruneJob?.cancel()
        pruneJob = null
    }

    fun cleanup() {
        stopAdvertising()
        stopScanning()
        seenDevices.clear()
        _nearbyDevices.value = emptyList()
    }
}
