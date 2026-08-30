package com.example.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.model.SpeakerMessage
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "SpeakerSyncEngine"
private const val MAX_TRACK_BYTES = 60L * 1024 * 1024 // sanity cap - a malformed/huge header shouldn't fill the disk

/**
 * "Speaker mode": any number of phones can join a host and play its current native-library
 * track locally, roughly in sync. This is deliberately a *separate* RFCOMM connection (own
 * UUID, own accept loop) from BluetoothConnectionEngine's single control channel, so a bug
 * here can't destabilize normal remote-control use, and so the host can accept many speakers
 * at once without touching the control channel's inherently one-to-one design at all.
 *
 * Honest limitations, given the transport: classic Bluetooth SPP tops out around 100-300KB/s,
 * shared across however many speakers are connected, and there's no way to capture a hooked
 * third-party app's audio - so this only works for the host's own local library, and joining
 * means downloading the whole track before it can start playing (typically single-digit
 * seconds for a compressed song, longer for lossless files or many simultaneous speakers).
 * Sync is "follow the leader": each speaker periodically corrects its position against the
 * host's reported position rather than anything sample-accurate.
 */
class SpeakerSyncEngine(private val context: Context) {

    companion object {
        val SPEAKER_UUID: UUID = UUID.fromString("3f6a9b7e-2c1d-4e88-9a2e-6b5f8c1d4e2a")
        private const val SYNC_DRIFT_THRESHOLD_MS = 200L
    }

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val moshi = Moshi.Builder().build()
    private val messageAdapter = moshi.adapter(SpeakerMessage::class.java)

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    // --- Host role: accept any number of simultaneous speaker connections ---

    private var serverSocket: BluetoothServerSocket? = null
    private var acceptJob: Job? = null
    private val speakerConnections = ConcurrentHashMap<String, SpeakerConnection>()

    private val _connectedSpeakerCount = MutableStateFlow(0)
    val connectedSpeakerCount: StateFlow<Int> = _connectedSpeakerCount.asStateFlow()

    private class SpeakerConnection(val socket: BluetoothSocket, val out: DataOutputStream, val writeJob: Job)

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    @SuppressLint("MissingPermission")
    fun startHosting() {
        if (acceptJob != null) return
        val adapter = bluetoothAdapter ?: return
        if (!hasConnectPermission()) return

        acceptJob = scope.launch {
            try {
                val server = adapter.listenUsingRfcommWithServiceRecord("BlueSyncSpeaker", SPEAKER_UUID)
                serverSocket = server
                Log.d(TAG, "Speaker server listening")
                while (true) {
                    val socket = try {
                        server?.accept() ?: break
                    } catch (e: IOException) {
                        Log.d(TAG, "Speaker server accept loop ending", e)
                        break
                    }
                    val address = try { socket.remoteDevice?.address } catch (e: SecurityException) { null } ?: socket.hashCode().toString()
                    handleNewSpeaker(address, socket)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start speaker server", e)
            }
        }
    }

    private fun handleNewSpeaker(address: String, socket: BluetoothSocket) {
        scope.launch {
            try {
                val out = DataOutputStream(socket.outputStream)
                val input = DataInputStream(socket.inputStream)
                val writeJob = Job()
                speakerConnections[address] = SpeakerConnection(socket, out, writeJob)
                _connectedSpeakerCount.value = speakerConnections.size
                Log.d(TAG, "Speaker joined: $address (${speakerConnections.size} total)")

                // The connection only needs to carry host -> speaker traffic; just block here
                // reading (and discarding) anything the speaker sends, so we notice a closed
                // socket promptly and clean up.
                while (true) {
                    val len = try {
                        input.readInt()
                    } catch (e: IOException) {
                        break
                    }
                    if (len <= 0 || len > 1_000_000) break
                    val buf = ByteArray(len)
                    input.readFully(buf)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Speaker connection $address ended", e)
            } finally {
                speakerConnections.remove(address)
                _connectedSpeakerCount.value = speakerConnections.size
                try { socket.close() } catch (e: Exception) { /* already gone */ }
                Log.d(TAG, "Speaker left: $address (${speakerConnections.size} remaining)")
            }
        }
    }

    /** Sends the current track to every connected speaker so they can start downloading and
     * playing it. Safe to call from any thread, including Main - the (blocking) file read and
     * all socket writes happen on this engine's own IO-dispatcher scope, never the caller's. */
    fun broadcastTrack(songId: String, title: String, artist: String, sourceUri: Uri) {
        if (speakerConnections.isEmpty()) return
        scope.launch {
            val bytes = try {
                context.contentResolver.openInputStream(sourceUri)?.use { it.readBytes() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read track for speaker broadcast", e)
                null
            } ?: return@launch

            val fullHeader = SpeakerMessage(
                type = "TRACK_HEADER",
                songId = songId,
                title = title,
                artist = artist,
                totalBytes = bytes.size.toLong()
            )
            for ((address, conn) in speakerConnections) {
                launch {
                    try {
                        writeMessage(conn.out, fullHeader)
                        conn.out.write(bytes)
                        conn.out.flush()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send track to speaker $address", e)
                    }
                }
            }
        }
    }

    /** Lightweight, frequent position/play-state correction signal - the actual sync
     * mechanism, sent every second or so rather than relying on a one-time scheduled start. */
    fun broadcastSync(positionMs: Long, isPlaying: Boolean) {
        if (speakerConnections.isEmpty()) return
        val message = SpeakerMessage(type = "SYNC", positionMs = positionMs, isPlaying = isPlaying)
        for ((address, conn) in speakerConnections) {
            scope.launch {
                try {
                    writeMessage(conn.out, message)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send sync to speaker $address", e)
                }
            }
        }
    }

    fun stopHosting() {
        acceptJob?.cancel()
        acceptJob = null
        try { serverSocket?.close() } catch (e: Exception) { /* ignore */ }
        serverSocket = null
        for (conn in speakerConnections.values) {
            try { conn.socket.close() } catch (e: Exception) { /* ignore */ }
        }
        speakerConnections.clear()
        _connectedSpeakerCount.value = 0
    }

    private fun writeMessage(out: DataOutputStream, message: SpeakerMessage) {
        val json = messageAdapter.toJson(message).toByteArray(Charsets.UTF_8)
        out.writeInt(json.size)
        out.write(json)
        out.flush()
    }

    // --- Client role: join a host as a speaker ---

    private var joinJob: Job? = null
    private var joinSocket: BluetoothSocket? = null

    sealed class SpeakerClientState {
        object Disconnected : SpeakerClientState()
        object Connecting : SpeakerClientState()
        data class Ready(val title: String, val artist: String, val fileUri: Uri) : SpeakerClientState()
        object Failed : SpeakerClientState()
    }

    private val _clientState = MutableStateFlow<SpeakerClientState>(SpeakerClientState.Disconnected)
    val clientState: StateFlow<SpeakerClientState> = _clientState.asStateFlow()

    private val _syncSignal = MutableStateFlow<Pair<Long, Boolean>?>(null) // positionMs, isPlaying
    val syncSignal: StateFlow<Pair<Long, Boolean>?> = _syncSignal.asStateFlow()

    @SuppressLint("MissingPermission")
    fun joinAsSpeaker(hostDevice: BluetoothDevice) {
        leaveSpeakerMode()
        _clientState.value = SpeakerClientState.Connecting
        joinJob = scope.launch {
            try {
                val socket = hostDevice.createRfcommSocketToServiceRecord(SPEAKER_UUID)
                joinSocket = socket
                socket.connect()
                Log.d(TAG, "Joined host as speaker")

                val input = DataInputStream(socket.inputStream)
                var pendingSongId: String? = null
                var pendingTitle = ""
                var pendingArtist = ""

                while (true) {
                    val len = input.readInt()
                    if (len <= 0 || len > 1_000_000) break
                    val jsonBytes = ByteArray(len)
                    input.readFully(jsonBytes)
                    val message = messageAdapter.fromJson(String(jsonBytes, Charsets.UTF_8)) ?: continue

                    when (message.type) {
                        "TRACK_HEADER" -> {
                            val totalBytes = (message.totalBytes ?: 0L).coerceIn(0L, MAX_TRACK_BYTES)
                            pendingSongId = message.songId
                            pendingTitle = message.title ?: "Speaker track"
                            pendingArtist = message.artist ?: ""

                            val cacheFile = File(context.cacheDir, "speaker_track_${pendingSongId ?: "current"}.audio")
                            cacheFile.outputStream().use { fileOut ->
                                val buffer = ByteArray(8192)
                                var remaining = totalBytes
                                while (remaining > 0) {
                                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                                    val read = input.read(buffer, 0, toRead)
                                    if (read <= 0) break
                                    fileOut.write(buffer, 0, read)
                                    remaining -= read
                                }
                            }
                            _clientState.value = SpeakerClientState.Ready(pendingTitle, pendingArtist, Uri.fromFile(cacheFile))
                        }
                        "SYNC" -> {
                            val position = message.positionMs
                            val playing = message.isPlaying
                            if (position != null && playing != null) {
                                _syncSignal.value = position to playing
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Speaker join failed/ended", e)
                _clientState.value = SpeakerClientState.Failed
            } finally {
                try { joinSocket?.close() } catch (e: Exception) { /* ignore */ }
                joinSocket = null
            }
        }
    }

    fun leaveSpeakerMode() {
        joinJob?.cancel()
        joinJob = null
        try { joinSocket?.close() } catch (e: Exception) { /* ignore */ }
        joinSocket = null
        _clientState.value = SpeakerClientState.Disconnected
        _syncSignal.value = null
    }

    /** Threshold used by the UI-side player to decide whether a SYNC signal is worth acting
     * on (a small correction every tick would be audible as constant micro-stutters). */
    fun isDrifted(localPositionMs: Long, hostPositionMs: Long): Boolean =
        kotlin.math.abs(localPositionMs - hostPositionMs) > SYNC_DRIFT_THRESHOLD_MS
}
