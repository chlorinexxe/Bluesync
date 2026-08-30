package com.example.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.example.model.BluetoothCommand
import com.example.model.BluetoothStateUpdate
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.util.UUID

private const val TAG = "BluetoothEngine"

enum class BluetoothConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    LISTENING
}

class BluetoothConnectionEngine(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    
    private val moshi = Moshi.Builder().build()
    private val commandAdapter = moshi.adapter(BluetoothCommand::class.java)
    private val updateAdapter = moshi.adapter(BluetoothStateUpdate::class.java)

    private val _connectionState = MutableStateFlow(BluetoothConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BluetoothConnectionState> = _connectionState.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    var userIntentDisconnected = true
        private set

    private var autoReconnectJob: Job? = null

    // Scanning State
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<BluetoothDevice>> = _discoveredDevices.asStateFlow()

    // Written from the IO dispatcher, read/cleared from cleanup() which can be called from any
    // thread (most call sites are the main thread) - @Volatile guarantees the null-out in
    // cleanup() is actually visible to whichever thread reads these next.
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var clientSocket: BluetoothSocket? = null

    @Volatile private var writer: PrintWriter? = null
    @Volatile private var reader: BufferedReader? = null

    private val ioDispatcher = Dispatchers.IO
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    private var connectionJob: Job? = null
    private var listeningJob: Job? = null
    private var heartbeatJob: Job? = null
    private var receiverRegistered = false

    // Callbacks
    var onCommandReceived: ((BluetoothCommand) -> Unit)? = null
    var onStateReceived: ((BluetoothStateUpdate) -> Unit)? = null
    var onConnectionStateChanged: ((BluetoothConnectionState) -> Unit)? = null

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            val action = intent.action
            if (BluetoothDevice.ACTION_FOUND == action) {
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }

                if (device != null) {
                    val currentList = _discoveredDevices.value
                    if (!currentList.any { it.address == device.address }) {
                        _discoveredDevices.value = currentList + device
                        Log.d(TAG, "Discovered Bluetooth Device: ${device.name ?: "Unknown"} [${device.address}]")
                    }
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED == action) {
                Log.d(TAG, "Bluetooth Scan finished.")
                _isScanning.value = false
            }
        }
    }

    fun isBluetoothSupported(): Boolean = bluetoothAdapter != null

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled ?: false

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        if (!hasBluetoothConnectPermission()) return emptyList()
        return bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
    }

    /** The device on the other end of the current control-channel connection, if any - used
     * to join that same device's speaker channel (a completely separate connection/UUID). */
    @SuppressLint("MissingPermission")
    fun getConnectedRemoteDevice(): BluetoothDevice? {
        if (_connectionState.value != BluetoothConnectionState.CONNECTED) return null
        return try {
            clientSocket?.remoteDevice
        } catch (e: SecurityException) {
            null
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun hasBluetoothScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    @SuppressLint("MissingPermission")
    fun startDeviceDiscovery() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return
        if (!hasBluetoothScanPermission()) return

        // Register Receiver if not registered
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            // On API 33+, registerReceiver requires an explicit exported flag or it throws.
            // These are system broadcasts (BluetoothDevice/BluetoothAdapter actions), so they
            // must never be receivable from other apps -> NOT_EXPORTED. ContextCompat provides
            // the same call down to API 24 without manual SDK branching.
            androidx.core.content.ContextCompat.registerReceiver(
                context,
                discoveryReceiver,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }

        _discoveredDevices.value = emptyList()
        _isScanning.value = true

        try {
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }
            bluetoothAdapter.startDiscovery()
            Log.d(TAG, "Initiated Active Bluetooth Scan")
        } catch (e: Exception) {
            // Permission can be revoked or the adapter can be toggled off between our check
            // above and this call - don't let that race crash the app.
            Log.e(TAG, "startDiscovery failed", e)
            _isScanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopDeviceDiscovery() {
        try {
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter.cancelDiscovery()
            }
        } catch (e: Exception) {
            Log.e(TAG, "cancelDiscovery failed", e)
        }
        _isScanning.value = false
        unregisterReceiver()
    }

    private fun unregisterReceiver() {
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(discoveryReceiver)
            } catch (e: Exception) {
                Log.e(TAG, "Unregister receiver fail", e)
            }
            receiverRegistered = false
        }
    }

    // onConnectionStateChanged is application code we don't control the thread-safety of - the
    // PlaybackService listener touches ExoPlayer, which throws IllegalStateException (crashing
    // the whole process) if called off the main thread. Every call site here runs on the IO
    // dispatcher, so route the callback through Main every time rather than relying on each
    // call site to remember to.
    private fun updateConnectionState(state: BluetoothConnectionState) {
        _connectionState.value = state
        if (state == BluetoothConnectionState.CONNECTED) {
            acquireConnectionWakeLock()
        } else {
            releaseConnectionWakeLock()
        }
        scope.launch(Dispatchers.Main) {
            onConnectionStateChanged?.invoke(state)
        }
    }

    // Held only while actively connected, so the screen turning off doesn't let the CPU sleep
    // through the blocking socket read that keeps the connection alive - the whole point of
    // "stay connected to the other phone as much as possible."
    private var wakeLock: PowerManager.WakeLock? = null

    @SuppressLint("WakelockTimeout")
    private fun acquireConnectionWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BlueSync:connection")
            wakeLock?.acquire()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire connection wake lock", e)
        }
    }

    private fun releaseConnectionWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release connection wake lock", e)
        }
        wakeLock = null
    }

    @SuppressLint("MissingPermission")
    fun startHostServer() {
        if (!isBluetoothEnabled() || !hasBluetoothConnectPermission()) {
            _connectionState.value = BluetoothConnectionState.DISCONNECTED
            return
        }

        autoReconnectJob?.cancel()
        userIntentDisconnected = false
        cleanup(explicit = false)
        updateConnectionState(BluetoothConnectionState.LISTENING)
        _connectedDeviceName.value = null

        listeningJob = scope.launch(ioDispatcher) {
            try {
                Log.d(TAG, "Starting RFCOMM server socket...")
                serverSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord("BlueSyncServer", SPP_UUID)
                
                var socket: BluetoothSocket? = null
                while (connectionState.value == BluetoothConnectionState.LISTENING) {
                    try {
                        socket = serverSocket?.accept()
                        if (socket != null) {
                            Log.d(TAG, "Accepted connection from client!")
                            break
                        }
                    } catch (e: IOException) {
                        Log.e(TAG, "ServerSocket accept failed / closed", e)
                        break
                    }
                }

                if (socket != null) {
                    manageConnectedSocket(socket, isHost = true)
                } else {
                    updateConnectionState(BluetoothConnectionState.DISCONNECTED)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed in startHostServer", e)
                updateConnectionState(BluetoothConnectionState.DISCONNECTED)
            } finally {
                closeServerSocketOnly()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        if (!isBluetoothEnabled() || !hasBluetoothConnectPermission()) {
            _connectionState.value = BluetoothConnectionState.DISCONNECTED
            return
        }

        autoReconnectJob?.cancel()
        userIntentDisconnected = false
        cleanup(explicit = false)
        updateConnectionState(BluetoothConnectionState.CONNECTING)
        _connectedDeviceName.value = device.name ?: "Unknown Device"

        connectionJob = scope.launch(ioDispatcher) {
            try {
                Log.d(TAG, "Connecting to device: ${device.name} [${device.address}]")
                val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                
                if (bluetoothAdapter?.isDiscovering == true) {
                    bluetoothAdapter.cancelDiscovery() // Cancel scan to improve connection performance
                }
                
                socket.connect()
                Log.d(TAG, "Connected successfully!")
                manageConnectedSocket(socket, isHost = false)
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                updateConnectionState(BluetoothConnectionState.DISCONNECTED)
                _connectedDeviceName.value = null
                cleanup()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun manageConnectedSocket(socket: BluetoothSocket, isHost: Boolean) {
        clientSocket = socket
        updateConnectionState(BluetoothConnectionState.CONNECTED)

        try {
            val device = socket.remoteDevice
            _connectedDeviceName.value = device?.name ?: "Remote Controller"
            if (!isHost && device != null) {
                val sharedPrefs = context.getSharedPreferences("BlueSyncPrefs", Context.MODE_PRIVATE)
                sharedPrefs.edit().putString("last_connected_mac", device.address).apply()
                Log.d(TAG, "Saved successful host device MAC: ${device.address}")
            }
        } catch (e: SecurityException) {
            _connectedDeviceName.value = "Remote Device"
        }

        scope.launch(ioDispatcher) {
            try {
                val outputStream = socket.outputStream
                writer = PrintWriter(BufferedWriter(OutputStreamWriter(outputStream, "UTF-8")), true)
                
                val inputStream = socket.inputStream
                reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))

                Log.d(TAG, "I/O Readers/Writers initialized, listening for data...")

                if (!isHost) {
                    heartbeatJob?.cancel()
                    heartbeatJob = scope.launch(ioDispatcher) {
                        while (connectionState.value == BluetoothConnectionState.CONNECTED) {
                            kotlinx.coroutines.delay(1200)
                            if (connectionState.value == BluetoothConnectionState.CONNECTED) {
                                sendCommand(BluetoothCommand(command = "PING"))
                            }
                        }
                    }
                }

                while (connectionState.value == BluetoothConnectionState.CONNECTED) {
                    val line = reader?.readLine() ?: break // socket closed

                    // Dispatch by content rather than trusting the fixed role this socket was
                    // originally set up with (RFCOMM server-accepted vs client-connected never
                    // changes for the lifetime of the socket). This is what makes live role
                    // swapping possible - after a swap, this device's *application* role
                    // flips, but the same physical connection needs to keep working in
                    // whichever direction the data actually flows now. A BluetoothCommand JSON
                    // is missing BluetoothStateUpdate's required fields and vice versa, so
                    // trying the "wrong" shape reliably fails to parse rather than silently
                    // producing garbage.
                    val cmd = try {
                        commandAdapter.fromJson(line)
                    } catch (e: Exception) {
                        null
                    }
                    if (cmd != null) {
                        if (cmd.command != "PING") {
                            Log.d(TAG, "Received raw line: $line")
                            withContext(Dispatchers.Main) {
                                onCommandReceived?.invoke(cmd)
                            }
                        }
                        continue
                    }

                    try {
                        val stateUpdate = updateAdapter.fromJson(line)
                        if (stateUpdate != null) {
                            withContext(Dispatchers.Main) {
                                onStateReceived?.invoke(stateUpdate)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse state update payload: $line", e)
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Socket read/write thread error/interrupted", e)
            } finally {
                Log.d(TAG, "Cleaning up sockets...")
                val wasConnected = _connectionState.value == BluetoothConnectionState.CONNECTED
                updateConnectionState(BluetoothConnectionState.DISCONNECTED)
                _connectedDeviceName.value = null
                cleanup(explicit = false)

                if (wasConnected && !userIntentDisconnected) {
                    triggerAutoReconnectWithDelay()
                }
            }
        }
    }

    private fun triggerAutoReconnectWithDelay() {
        autoReconnectJob?.cancel()
        autoReconnectJob = scope.launch {
            kotlinx.coroutines.delay(4000)
            if (_connectionState.value == BluetoothConnectionState.DISCONNECTED && !userIntentDisconnected) {
                val sharedPrefs = context.getSharedPreferences("BlueSyncPrefs", Context.MODE_PRIVATE)
                val lastMac = sharedPrefs.getString("last_connected_mac", null)
                if (lastMac != null) {
                    Log.d(TAG, "Auto-reconnect triggered in background for MAC: $lastMac")
                    val paired = getPairedDevices()
                    val match = paired.find { it.address == lastMac }
                    if (match != null) {
                        connectToDevice(match)
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun tryAutoConnect() {
        if (_connectionState.value != BluetoothConnectionState.DISCONNECTED) return
        val sharedPrefs = context.getSharedPreferences("BlueSyncPrefs", Context.MODE_PRIVATE)
        val lastMac = sharedPrefs.getString("last_connected_mac", null) ?: return
        
        val paired = getPairedDevices()
        val match = paired.find { it.address == lastMac }
        if (match != null) {
            Log.d(TAG, "Initiating silent background auto-connection to host: ${match.address}")
            userIntentDisconnected = false
            connectToDevice(match)
        }
    }

    fun sendCommand(command: BluetoothCommand) {
        if (_connectionState.value != BluetoothConnectionState.CONNECTED) return
        scope.launch(ioDispatcher) {
            try {
                val json = commandAdapter.toJson(command)
                writer?.println(json)
                writer?.flush()
                Log.d(TAG, "Sent command: $json")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send command", e)
            }
        }
    }

    fun sendStateUpdate(stateUpdate: BluetoothStateUpdate) {
        if (_connectionState.value != BluetoothConnectionState.CONNECTED) return
        scope.launch(ioDispatcher) {
            try {
                val json = updateAdapter.toJson(stateUpdate)
                writer?.println(json)
                writer?.flush()
                if (stateUpdate.status != "PING") {
                    Log.d(TAG, "Sent state update: $json")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send state update", e)
            }
        }
    }

    private fun closeServerSocketOnly() {
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket", e)
        }
        serverSocket = null
    }

    fun setUserDisconnected() {
        userIntentDisconnected = true
        autoReconnectJob?.cancel()
    }

    fun cleanup(explicit: Boolean = false) {
        if (explicit) {
            userIntentDisconnected = true
            autoReconnectJob?.cancel()
        }
        releaseConnectionWakeLock()
        heartbeatJob?.cancel()
        heartbeatJob = null
        stopDeviceDiscovery()
        listeningJob?.cancel()
        listeningJob = null
        connectionJob?.cancel()
        connectionJob = null

        // Every call site for cleanup() (mode switches, disconnect, starting a new
        // connection) runs on the main thread. BluetoothSocket/stream close() is a blocking
        // call that can hang for seconds when another thread is stuck in a concurrent
        // read() on the same socket - which the read loop in manageConnectedSocket always is
        // while connected. Snapshot the resources and null them out immediately (so nothing
        // else can touch a socket mid-teardown), but do the actual blocking close() off the
        // calling thread so the UI never freezes on a mode switch or disconnect.
        val serverSocketToClose = serverSocket
        val writerToClose = writer
        val readerToClose = reader
        val clientSocketToClose = clientSocket
        serverSocket = null
        writer = null
        reader = null
        clientSocket = null

        scope.launch(ioDispatcher) {
            try {
                serverSocketToClose?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing server socket", e)
            }
            try {
                writerToClose?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing writer", e)
            }
            try {
                readerToClose?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing reader", e)
            }
            try {
                clientSocketToClose?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing client socket", e)
            }
        }
    }
}
