package com.example.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "SpeakerSyncEngine"
private const val MAX_TRACK_BYTES = 60L * 1024 * 1024 // sanity cap - a malformed/huge header shouldn't fill the disk
private const val SPEAKER_WIFI_PORT = 57391
private const val WIFI_DIRECT_GROUP_OWNER_IP_FALLBACK = "192.168.49.1" // used only if the host couldn't determine its real P2P interface IP
private const val WIFI_SOCKET_CONNECT_TIMEOUT_MS = 3000
private const val FRAME_CONTROL: Int = 0 // a JSON SpeakerMessage
private const val FRAME_AUDIO_CHUNK: Int = 1 // part of the track currently being sent
private const val AUDIO_CHUNK_BYTES = 32_768 // small enough that a queued SYNC frame is never stuck waiting long

/**
 * "Speaker mode": any number of phones can join a host and play its current native-library
 * track locally, roughly in sync. Every speaker connects over Bluetooth first (fast, reliable,
 * needs no shared network) - if the host has an ad-hoc WiFi Direct group ready, it hands the
 * speaker that group's credentials as the very first message, and the speaker silently upgrades
 * to it for the actual (much higher-bandwidth) track transfer and sync ticks, closing the
 * Bluetooth socket. No shared WiFi network or router is required for this - it works in a car,
 * outdoors, anywhere - and any number of speakers can join the same WiFi Direct group, since it
 * behaves like a small access point rather than a strict 1:1 link. Falls back to keeping the
 * Bluetooth connection (own UUID, separate from the control channel) when WiFi Direct isn't
 * available on either end, so speaker mode still works, just with a slower start.
 *
 * Sync is "follow the leader": each speaker periodically corrects its position against the
 * host's reported position rather than anything sample-accurate.
 */
class SpeakerSyncEngine(private val context: Context) {

    companion object {
        val SPEAKER_UUID: UUID = UUID.fromString("3f6a9b7e-2c1d-4e88-9a2e-6b5f8c1d4e2a")
        private const val SYNC_DRIFT_THRESHOLD_MS = 200L
        private const val HARD_SEEK_THRESHOLD_MS = 1200L
        private const val MIN_SPEED_ADJUST = 0.01f
        private const val MAX_SPEED_ADJUST = 0.05f
        private const val EARLY_START_BYTES = 200_000L
        private const val REBUFFER_CHUNK_BYTES = 250_000L
        private const val MIN_ANNOUNCE_INTERVAL_MS = 1500L
    }

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val moshi = Moshi.Builder().build()
    private val messageAdapter = moshi.adapter(SpeakerMessage::class.java)
    private val wifiDirectManager = WifiDirectGroupManager(context)

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    // --- Host role: accept any number of simultaneous speaker connections, over either transport ---

    private var btServerSocket: BluetoothServerSocket? = null
    private var btAcceptJob: Job? = null
    private var wifiServerSocket: ServerSocket? = null
    private var wifiAcceptJob: Job? = null
    private var wifiDirectGroupJob: Job? = null
    @Volatile private var wifiDirectCredentials: WifiDirectGroupManager.GroupCredentials? = null
    private val speakerConnections = ConcurrentHashMap<String, SpeakerConnection>()

    private val _connectedSpeakerCount = MutableStateFlow(0)
    val connectedSpeakerCount: StateFlow<Int> = _connectedSpeakerCount.asStateFlow()

    private class SpeakerConnection(val out: DataOutputStream, val close: () -> Unit) {
        // Guards each individual frame write (see FRAME_CONTROL/FRAME_AUDIO_CHUNK) so two
        // frames can never interleave mid-write and corrupt each other's framing. Audio is
        // sent as many small chunk frames rather than one giant unframed blob specifically so
        // a SYNC frame can slot in *between* chunks - holding this lock for a whole multi-MB
        // (potentially many-second, especially over the Bluetooth fallback) transfer would
        // completely stall position corrections for that whole window, and the first one sent
        // afterward would carry an already-stale position, producing a visible jump.
        val writeMutex = Mutex()
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    @SuppressLint("MissingPermission")
    fun startHosting() {
        startBluetoothHosting()
        startWifiHosting()
        if (wifiDirectGroupJob == null) {
            wifiDirectGroupJob = scope.launch {
                wifiDirectCredentials = wifiDirectManager.createGroup()
                if (wifiDirectCredentials != null) {
                    Log.d(TAG, "WiFi Direct group ready: ${wifiDirectCredentials?.ssid}")
                } else {
                    Log.d(TAG, "No WiFi Direct group available - speakers will stay on Bluetooth")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothHosting() {
        if (btAcceptJob != null) return
        val adapter = bluetoothAdapter ?: return
        if (!hasConnectPermission()) return

        btAcceptJob = scope.launch {
            try {
                val server = adapter.listenUsingRfcommWithServiceRecord("BlueSyncSpeaker", SPEAKER_UUID)
                btServerSocket = server
                Log.d(TAG, "Speaker BT server listening")
                while (true) {
                    val socket = try {
                        server?.accept() ?: break
                    } catch (e: IOException) {
                        Log.d(TAG, "Speaker BT accept loop ending", e)
                        break
                    }
                    val address = try { "bt:" + socket.remoteDevice?.address } catch (e: SecurityException) { null } ?: "bt:${socket.hashCode()}"
                    val out = DataOutputStream(socket.outputStream)
                    // The whole point of a fresh Bluetooth speaker connection is to offer the
                    // faster WiFi Direct handoff before any other traffic - send it first, once,
                    // synchronously, so the client can rely on "first message" without a race
                    // against broadcastTrack()/broadcastSync() also writing to the same stream.
                    wifiDirectCredentials?.let { creds ->
                        try {
                            Log.d(TAG, "Offering WiFi Direct info to $address: ssid=${creds.ssid} hostIp=${creds.hostIp}")
                            writeControlFrame(out, SpeakerMessage(type = "WIFI_DIRECT_INFO", wifiSsid = creds.ssid, wifiPassphrase = creds.passphrase, wifiHostIp = creds.hostIp))
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to offer WiFi Direct info to $address", e)
                        }
                    }
                    handleNewSpeakerConnection(
                        address,
                        DataInputStream(socket.inputStream),
                        out
                    ) { try { socket.close() } catch (e: Exception) { /* already gone */ } }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start speaker BT server", e)
            }
        }
    }

    private fun startWifiHosting() {
        if (wifiAcceptJob != null) return
        wifiAcceptJob = scope.launch {
            try {
                val server = ServerSocket(SPEAKER_WIFI_PORT)
                wifiServerSocket = server
                Log.d(TAG, "Speaker WiFi server listening on port $SPEAKER_WIFI_PORT")
                while (true) {
                    val socket = try {
                        server.accept()
                    } catch (e: IOException) {
                        Log.d(TAG, "Speaker WiFi accept loop ending", e)
                        break
                    }
                    val address = "wifi:${socket.inetAddress?.hostAddress ?: socket.hashCode()}"
                    handleNewSpeakerConnection(
                        address,
                        DataInputStream(socket.inputStream),
                        DataOutputStream(socket.outputStream)
                    ) { try { socket.close() } catch (e: Exception) { /* already gone */ } }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start speaker WiFi server", e)
            } finally {
                wifiAcceptJob = null
            }
        }
    }

    private fun handleNewSpeakerConnection(address: String, input: DataInputStream, out: DataOutputStream, close: () -> Unit) {
        scope.launch {
            try {
                speakerConnections[address] = SpeakerConnection(out, close)
                _connectedSpeakerCount.value = speakerConnections.size
                Log.d(TAG, "Speaker joined: $address (${speakerConnections.size} total)")

                // The connection only needs to carry host -> speaker traffic; just block here
                // reading (and discarding) anything the speaker sends, so we notice a closed
                // socket promptly and clean up. A speaker that upgrades to WiFi Direct closes
                // this (Bluetooth) socket on its own right after reading the handoff offer,
                // which surfaces here as a normal read failure - not an error.
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
                Log.d(TAG, "Speaker connection $address ended", e)
            } finally {
                speakerConnections.remove(address)
                _connectedSpeakerCount.value = speakerConnections.size
                close()
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
                        conn.writeMutex.withLock { writeControlFrame(conn.out, fullHeader) }
                        // Sent as many small chunk frames, each under its own brief lock
                        // acquisition, rather than one big write under one lock held for the
                        // whole transfer - see the writeMutex doc comment on why that matters.
                        var offset = 0
                        while (offset < bytes.size) {
                            val len = minOf(AUDIO_CHUNK_BYTES, bytes.size - offset)
                            conn.writeMutex.withLock { writeAudioChunkFrame(conn.out, bytes, offset, len) }
                            offset += len
                        }
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
                    conn.writeMutex.withLock { writeControlFrame(conn.out, message) }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send sync to speaker $address", e)
                }
            }
        }
    }

    fun stopHosting() {
        btAcceptJob?.cancel()
        btAcceptJob = null
        try { btServerSocket?.close() } catch (e: Exception) { /* ignore */ }
        btServerSocket = null
        wifiAcceptJob?.cancel()
        wifiAcceptJob = null
        try { wifiServerSocket?.close() } catch (e: Exception) { /* ignore */ }
        wifiServerSocket = null
        wifiDirectGroupJob?.cancel()
        wifiDirectGroupJob = null
        if (wifiDirectCredentials != null) {
            wifiDirectManager.removeGroup()
            wifiDirectCredentials = null
        }
        for (conn in speakerConnections.values) {
            conn.close()
        }
        speakerConnections.clear()
        _connectedSpeakerCount.value = 0
    }

    private fun writeControlFrame(out: DataOutputStream, message: SpeakerMessage) {
        val json = messageAdapter.toJson(message).toByteArray(Charsets.UTF_8)
        out.writeByte(FRAME_CONTROL)
        out.writeInt(json.size)
        out.write(json)
        out.flush()
    }

    private fun writeAudioChunkFrame(out: DataOutputStream, chunk: ByteArray, offset: Int, length: Int) {
        out.writeByte(FRAME_AUDIO_CHUNK)
        out.writeInt(length)
        out.write(chunk, offset, length)
        out.flush()
    }

    // --- Client role: join a host as a speaker, upgrading from Bluetooth to WiFi Direct if offered ---

    private var joinJob: Job? = null
    @Volatile private var joinCloseFn: (() -> Unit)? = null

    sealed class SpeakerClientState {
        object Disconnected : SpeakerClientState()
        object Connecting : SpeakerClientState()
        // isComplete=false fires as soon as a small initial buffer is on disk, so playback can
        // start almost immediately instead of waiting out the whole transfer; isComplete=true
        // fires again once the rest has arrived, at the same fileUri, so the caller can
        // seamlessly swap from the partial file to the full one in place.
        data class Ready(val title: String, val artist: String, val fileUri: Uri, val isComplete: Boolean = true) : SpeakerClientState()
        object Failed : SpeakerClientState()
    }

    private val _clientState = MutableStateFlow<SpeakerClientState>(SpeakerClientState.Disconnected)
    val clientState: StateFlow<SpeakerClientState> = _clientState.asStateFlow()

    private val _syncSignal = MutableStateFlow<Pair<Long, Boolean>?>(null) // positionMs, isPlaying
    val syncSignal: StateFlow<Pair<Long, Boolean>?> = _syncSignal.asStateFlow()

    /** Connects to the host's speaker channel, always starting over Bluetooth (fast, reliable,
     * needs no shared network), then transparently upgrading to WiFi Direct if the host offers
     * it as its first message and the upgrade succeeds within a few seconds. */
    @SuppressLint("MissingPermission")
    fun joinAsSpeaker(hostDevice: BluetoothDevice) {
        leaveSpeakerMode()
        _clientState.value = SpeakerClientState.Connecting
        joinJob = scope.launch {
            try {
                val btSocket = hostDevice.createRfcommSocketToServiceRecord(SPEAKER_UUID)
                btSocket.connect()
                joinCloseFn = { try { btSocket.close() } catch (e: Exception) { /* ignore */ } }
                var input = DataInputStream(btSocket.inputStream)
                Log.d(TAG, "Joined host as speaker over Bluetooth")

                var pendingFirstMessage: SpeakerMessage? = null
                val firstFrameType = input.readByte().toInt()
                val firstLen = input.readInt()
                if (firstFrameType == FRAME_CONTROL && firstLen in 1..1_000_000) {
                    val firstBytes = ByteArray(firstLen)
                    input.readFully(firstBytes)
                    val firstMessage = messageAdapter.fromJson(String(firstBytes, Charsets.UTF_8))
                    if (firstMessage?.type == "WIFI_DIRECT_INFO" && firstMessage.wifiSsid != null && firstMessage.wifiPassphrase != null) {
                        val upgraded = tryUpgradeToWifiDirect(firstMessage.wifiSsid, firstMessage.wifiPassphrase, firstMessage.wifiHostIp)
                        if (upgraded != null) {
                            try { btSocket.close() } catch (e: Exception) { /* ignore */ }
                            joinCloseFn = { try { upgraded.close() } catch (e: Exception) { /* ignore */ } }
                            input = DataInputStream(upgraded.inputStream)
                            Log.d(TAG, "Upgraded speaker connection to WiFi Direct")
                        } else {
                            Log.d(TAG, "WiFi Direct upgrade not available/failed - staying on Bluetooth")
                        }
                    } else if (firstMessage != null) {
                        // Host has no WiFi Direct group - this was a real message (e.g. a
                        // TRACK_HEADER), not a handoff offer, so feed it into the same handling
                        // the main read loop uses instead of throwing it away.
                        pendingFirstMessage = firstMessage
                    }
                }

                readSpeakerStream(input, pendingFirstMessage)
            } catch (e: Exception) {
                Log.e(TAG, "Speaker join failed/ended", e)
                _clientState.value = SpeakerClientState.Failed
            } finally {
                joinCloseFn?.invoke()
                joinCloseFn = null
            }
        }
    }

    private suspend fun tryUpgradeToWifiDirect(ssid: String, passphrase: String, hostIp: String?): Socket? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val network = wifiDirectManager.joinGroup(ssid, passphrase) ?: return null
        val targetIp = hostIp ?: WIFI_DIRECT_GROUP_OWNER_IP_FALLBACK
        // onAvailable() can hand back a Network slightly before its routing/ARP is actually
        // usable - an immediate connect() attempt right then is a known source of ECONNREFUSED
        // on freshly-joined WiFi Direct links even though the server side is already listening.
        // One short settle wait plus a single retry clears this without adding much latency to
        // the (already several-second) one-time handoff cost.
        repeat(2) { attempt ->
            if (attempt == 1) kotlinx.coroutines.delay(700)
            try {
                val socket = Socket()
                network.bindSocket(socket)
                socket.connect(InetSocketAddress(targetIp, SPEAKER_WIFI_PORT), WIFI_SOCKET_CONNECT_TIMEOUT_MS)
                return socket
            } catch (e: Exception) {
                Log.d(TAG, "WiFi Direct socket connect to $targetIp failed (attempt $attempt)", e)
            }
        }
        return null
    }

    /** Reads a track's audio chunks and any interleaved control messages (see writeMutex's doc
     * comment for why the host interleaves them) via one small piece of mutable state carried
     * across loop iterations - the "current in-progress track download," if any. */
    private class InProgressTrack(
        val file: java.io.OutputStream,
        val fileUri: Uri,
        val title: String,
        val artist: String,
        val totalBytes: Long
    ) {
        var received = 0L
        var nextAnnounceAt = 0L
        // Each "isComplete=false" announcement makes PlaybackService re-prepare the player
        // in place, which briefly re-buffers - fine as a rare top-up on a slow Bluetooth
        // transfer, but a fast WiFi Direct link can cross the byte threshold many times a
        // second, turning that into constant audible stutter. Gate announcements by wall-clock
        // time too so they can't fire more often than this regardless of transfer speed.
        var lastAnnounceRealtime = 0L
    }

    private suspend fun readSpeakerStream(input: DataInputStream, pendingFirstMessage: SpeakerMessage? = null) {
        var track: InProgressTrack? = null
        if (pendingFirstMessage != null) {
            if (pendingFirstMessage.type == "TRACK_HEADER") {
                track = startTrack(pendingFirstMessage)
            } else {
                handleSpeakerMessage(pendingFirstMessage)
            }
        }
        while (true) {
            val frameType = input.readByte().toInt()
            val len = input.readInt()
            if (len < 0 || len > MAX_TRACK_BYTES) break
            when (frameType) {
                FRAME_CONTROL -> {
                    if (len > 1_000_000) break
                    val jsonBytes = ByteArray(len)
                    input.readFully(jsonBytes)
                    val message = messageAdapter.fromJson(String(jsonBytes, Charsets.UTF_8)) ?: continue
                    if (message.type == "TRACK_HEADER") {
                        track?.file?.let { try { it.close() } catch (e: Exception) { /* ignore */ } }
                        track = startTrack(message)
                    } else {
                        handleSpeakerMessage(message)
                    }
                }
                FRAME_AUDIO_CHUNK -> {
                    val chunk = ByteArray(len)
                    input.readFully(chunk)
                    track?.let { appendTrackChunk(it, chunk) }
                }
                else -> break
            }
        }
    }

    private fun handleSpeakerMessage(message: SpeakerMessage) {
        if (message.type == "SYNC") {
            val position = message.positionMs
            val playing = message.isPlaying
            if (position != null && playing != null) {
                _syncSignal.value = position to playing
            }
        }
    }

    private fun startTrack(message: SpeakerMessage): InProgressTrack {
        val totalBytes = (message.totalBytes ?: 0L).coerceIn(0L, MAX_TRACK_BYTES)
        val cacheFile = File(context.cacheDir, "speaker_track_${message.songId ?: "current"}.audio")
        return InProgressTrack(
            file = cacheFile.outputStream(),
            fileUri = Uri.fromFile(cacheFile),
            title = message.title ?: "Speaker track",
            artist = message.artist ?: "",
            totalBytes = totalBytes
        ).apply {
            // Start playback once a small early chunk is down, then keep re-announcing every
            // REBUFFER_CHUNK_BYTES so the player can top itself up before it runs dry. Over
            // WiFi Direct this whole download typically finishes before the first threshold is
            // even relevant; over the Bluetooth fallback it's what keeps audio from going
            // silent mid-transfer.
            nextAnnounceAt = minOf(totalBytes, maxOf(EARLY_START_BYTES, totalBytes / 8))
        }
    }

    private fun appendTrackChunk(track: InProgressTrack, chunk: ByteArray) {
        track.file.write(chunk)
        track.received += chunk.size
        if (track.received >= track.totalBytes) {
            try { track.file.flush(); track.file.close() } catch (e: Exception) { /* ignore */ }
            _clientState.value = SpeakerClientState.Ready(track.title, track.artist, track.fileUri, isComplete = true)
        } else if (track.received >= track.nextAnnounceAt) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - track.lastAnnounceRealtime >= MIN_ANNOUNCE_INTERVAL_MS) {
                try { track.file.flush() } catch (e: Exception) { /* ignore */ }
                _clientState.value = SpeakerClientState.Ready(track.title, track.artist, track.fileUri, isComplete = false)
                track.lastAnnounceRealtime = now
            }
            track.nextAnnounceAt += REBUFFER_CHUNK_BYTES
        }
    }

    fun leaveSpeakerMode() {
        joinJob?.cancel()
        joinJob = null
        joinCloseFn?.invoke()
        joinCloseFn = null
        _clientState.value = SpeakerClientState.Disconnected
        _syncSignal.value = null
    }

    /** How the UI-side player should react to a given amount of drift from the host's reported
     * position. A flat "seek whenever off by more than X" is what caused the "sometimes doesn't
     * sync properly" complaints: a hard seek on *every* correction is an audible jump each time,
     * so small drift (typical steady-state jitter from network/scheduling variance) is instead
     * corrected with a tiny, inaudible playback-speed nudge that gradually closes the gap over
     * the next few seconds - the same technique Spotify Group Session/Sonos use. Only a large
     * jump (track just started, a reconnect, a long stall) gets a hard seek, since a speed nudge
     * alone would take too long to catch up.
     *
     * Every speaker corrects toward the *same* host-reported position rather than toward each
     * other - a shared reference point ("star" topology) is what keeps any number of speakers
     * coherent with each other, not just individually close to the host. The speed nudge is
     * scaled by how far off a speaker is (within the soft-correction band) so a speaker that's
     * drifted further closes the gap faster instead of crawling back at the same fixed rate as
     * one barely over the threshold - this bounds how long any single speaker can sound
     * noticeably offset from the rest of the group. */
    sealed class SyncCorrection {
        object None : SyncCorrection()
        data class SpeedAdjust(val speed: Float) : SyncCorrection()
        data class HardSeek(val toPositionMs: Long) : SyncCorrection()
    }

    fun syncCorrectionFor(localPositionMs: Long, hostPositionMs: Long): SyncCorrection {
        val drift = localPositionMs - hostPositionMs // positive = local is ahead of host
        val absDrift = kotlin.math.abs(drift)
        return when {
            absDrift > HARD_SEEK_THRESHOLD_MS -> SyncCorrection.HardSeek(hostPositionMs)
            absDrift > SYNC_DRIFT_THRESHOLD_MS -> {
                // Scales from a barely-perceptible ~1% right at the soft threshold up to ~5% (the
                // rough edge of what most listeners can notice as a pitch change) as drift
                // approaches the hard-seek threshold, so bigger gaps close proportionally faster.
                val range = (HARD_SEEK_THRESHOLD_MS - SYNC_DRIFT_THRESHOLD_MS).toFloat()
                val ratio = ((absDrift - SYNC_DRIFT_THRESHOLD_MS) / range).coerceIn(0f, 1f)
                val adjust = MIN_SPEED_ADJUST + ratio * (MAX_SPEED_ADJUST - MIN_SPEED_ADJUST)
                // Ahead of host -> play slightly slower to fall back; behind -> slightly faster.
                SyncCorrection.SpeedAdjust(if (drift > 0) 1f - adjust else 1f + adjust)
            }
            else -> SyncCorrection.None // close enough - let the caller reset speed to normal
        }
    }
}
