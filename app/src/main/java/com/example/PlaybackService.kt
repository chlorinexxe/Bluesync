package com.example

import android.annotation.SuppressLint
import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.bluetooth.BluetoothConnectionEngine
import com.example.bluetooth.BluetoothConnectionState
import com.example.model.BluetoothCommand
import com.example.model.BluetoothStateUpdate
import com.example.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "PlaybackService"
private const val NOTIFICATION_ID = 2026
private const val CHANNEL_ID = "bluesync_playback_channel"

class PlaybackService : Service() {

    private val binder = LocalBinder()
    
    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.Main + serviceJob)

    var exoPlayer: ExoPlayer? = null
    lateinit var bluetoothEngine: BluetoothConnectionEngine
        private set

    val isHostMode = MutableStateFlow(true)
    val hostUseNotificationHook = MutableStateFlow(false)

    // Local host master tracks with MediaStore parameters
    private val _hostSongs = MutableStateFlow<List<Song>>(emptyList())
    val hostSongs = _hostSongs.asStateFlow()

    // Client Synchronized List
    private val _clientSongs = MutableStateFlow<List<Song>>(emptyList())
    val clientSongs = _clientSongs.asStateFlow()

    private val _clientPlaybackState = MutableStateFlow<BluetoothStateUpdate?>(null)
    val clientPlaybackState = _clientPlaybackState.asStateFlow()

    private var broadcastLoopJob: Job? = null

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "Service Bound")
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service Created")
        bluetoothEngine = BluetoothConnectionEngine(applicationContext)
        initializePlayer()
        setupBluetoothEngineCallbacks()
        createNotificationChannel()
        setupNotificationListenerHook()
    }

    private fun initializePlayer() {
        exoPlayer = ExoPlayer.Builder(this).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (!hostUseNotificationHook.value) {
                        broadcastHostStateToClient()
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!hostUseNotificationHook.value) {
                        broadcastHostStateToClient()
                    }
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    if (!hostUseNotificationHook.value) {
                        broadcastHostStateToClient()
                    }
                }
            })
        }
        
        scanLocalAudioFiles()
        startSyncBroadcastLoop()
    }

    private fun setupNotificationListenerHook() {
        MyNotificationListener.onDataChangedListener = {
            if (isHostMode.value && hostUseNotificationHook.value) {
                broadcastHostStateToClient()
            }
        }
    }

    fun toggleAppMode(host: Boolean) {
        if (isHostMode.value == host) return
        isHostMode.value = host
        bluetoothEngine.cleanup()
        
        if (host) {
            exoPlayer?.pause()
            if (hostUseNotificationHook.value) {
                broadcastHostStateToClient(includeMetadata = true)
            } else {
                scanLocalAudioFiles()
            }
        } else {
            exoPlayer?.pause()
            _clientSongs.value = emptyList()
            _clientPlaybackState.value = null
        }
        updateForegroundNotification()
    }

    fun toggleHostSource(useNotificationListener: Boolean) {
        if (hostUseNotificationHook.value == useNotificationListener) return
        hostUseNotificationHook.value = useNotificationListener
        
        if (useNotificationListener) {
            exoPlayer?.pause()
            broadcastHostStateToClient(includeMetadata = true)
        } else {
            scanLocalAudioFiles()
        }
        updateForegroundNotification()
    }

    @SuppressLint("Range")
    fun scanLocalAudioFiles() {
        scope.launch(Dispatchers.Default) {
            val songs = mutableListOf<Song>()
            val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            
            // Query Title, Artist, Album, Duration, Genre (if possible), AlbumId for art
            val projection = mutableListOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.ALBUM_ID
            ).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    add(MediaStore.Audio.Media.GENRE)
                }
            }.toTypedArray()

            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

            try {
                contentResolver.query(uri, projection, selection, null, sortOrder)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                    
                    val genreCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cursor.getColumnIndex(MediaStore.Audio.Media.GENRE)
                    } else {
                        -1
                    }

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol).toString()
                        val title = cursor.getString(titleCol) ?: "Unknown Song"
                        val artist = cursor.getString(artistCol) ?: "Unknown Artist"
                        val album = cursor.getString(albumCol) ?: "Unknown Album"
                        val duration = cursor.getLong(durationCol)
                        val albumId = cursor.getLong(albumIdCol)

                        // Genre fetching fallback
                        var genre = "Acoustic"
                        if (genreCol != -1) {
                            genre = cursor.getString(genreCol) ?: "Acoustic"
                        } else {
                            // Query genre table as fallback
                            try {
                                val genreUri = MediaStore.Audio.Genres.getContentUriForAudioId("external", id.toLong().toInt())
                                contentResolver.query(genreUri, arrayOf(MediaStore.Audio.Genres.NAME), null, null, null)?.use { genCursor ->
                                    if (genCursor.moveToFirst()) {
                                        genre = genCursor.getString(0) ?: "Acoustic"
                                    }
                                }
                            } catch (ge: Exception) {
                                // fallback gracefully
                            }
                        }

                        val trackUriString = ContentUris.withAppendedId(uri, cursor.getLong(idCol)).toString()
                        val albumArtUri = ContentUris.withAppendedId(
                            Uri.parse("content://media/external/audio/albumart"),
                            albumId
                        ).toString()

                        if (duration > 1500) {
                            songs.add(
                                Song(
                                    id = id,
                                    title = title,
                                    artist = artist,
                                    album = album,
                                    genre = genre,
                                    duration = duration,
                                    uriString = trackUriString,
                                    albumArtUri = albumArtUri
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying MediaStore lines", e)
            }

            withContext(Dispatchers.Main) {
                _hostSongs.value = songs
                if (!hostUseNotificationHook.value) {
                    exoPlayer?.clearMediaItems()
                    songs.forEach { song ->
                        exoPlayer?.addMediaItem(MediaItem.fromUri(song.uriString))
                    }
                    exoPlayer?.prepare()
                }
                Log.d(TAG, "Scanned and loaded ${songs.size} real tracks from MediaStore")
            }
        }
    }

    private fun setupBluetoothEngineCallbacks() {
        bluetoothEngine.onCommandReceived = { cmd ->
            if (isHostMode.value) {
                handleClientCommand(cmd)
            }
        }

        bluetoothEngine.onStateReceived = { stateUpdate ->
            if (!isHostMode.value) {
                stateUpdate.songs?.let { remoteSongs ->
                    _clientSongs.value = remoteSongs
                }
                _clientPlaybackState.value = stateUpdate
            }
        }

        bluetoothEngine.onConnectionStateChanged = { connectionState ->
            if (connectionState == BluetoothConnectionState.CONNECTED && isHostMode.value) {
                broadcastHostStateToClient(includeMetadata = true)
            }
        }
    }

    private fun handleClientCommand(cmd: BluetoothCommand) {
        scope.launch(Dispatchers.Main) {
            if (cmd.command == "SET_VOLUME") {
                cmd.volume?.let { volIndex ->
                    try {
                        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                        audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, volIndex, android.media.AudioManager.FLAG_SHOW_UI)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to set volume index", e)
                    }
                    broadcastHostStateToClient()
                }
                return@launch
            }

            if (hostUseNotificationHook.value) {
                // Pipe command directly to external player (like Poweramp) via Notification Media Controller
                MyNotificationListener.executeCommand(
                    command = cmd.command,
                    index = cmd.index,
                    itemId = cmd.id,
                    seekPosition = cmd.seekPosition
                )
            } else {
                val player = exoPlayer ?: return@launch
                when (cmd.command) {
                    "PLAY_INDEX" -> {
                        cmd.index?.let { idx ->
                            if (idx in 0 until player.mediaItemCount) {
                                player.seekTo(idx, 0)
                                player.play()
                            }
                        }
                    }
                    "TOGGLE_PLAY" -> {
                        if (player.isPlaying) player.pause() else player.play()
                    }
                    "PAUSE" -> player.pause()
                    "RESUME" -> player.play()
                    "NEXT" -> player.seekToNextMediaItem()
                    "PREV" -> player.seekToPreviousMediaItem()
                    "SEEK" -> cmd.seekPosition?.let { player.seekTo(it) }
                    "SKIP_TO_QUEUE_ITEM" -> {
                        cmd.id?.let { targetId ->
                            val matchIdx = _hostSongs.value.indexOfFirst { it.id == targetId }
                            if (matchIdx != -1) {
                                player.seekTo(matchIdx, 0)
                                player.play()
                            }
                        }
                    }
                }
                broadcastHostStateToClient()
            }
        }
    }

    fun broadcastHostStateToClient(includeMetadata: Boolean = false) {
        if (!isHostMode.value) return
        scope.launch(Dispatchers.Main) {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val currentVol = try {
                audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            } catch (e: Exception) {
                0
            }
            val maxVol = try {
                audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            } catch (e: Exception) {
                15
            }

            val update = if (hostUseNotificationHook.value) {
                val controller = MyNotificationListener.getActiveController()
                if (controller != null) {
                    val metadata = controller.metadata
                    val playbackState = controller.playbackState

                    val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Disconnected / Idle"
                    val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Poweramp Intercept"
                    val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
                    val genre = metadata?.getString(MediaMetadata.METADATA_KEY_GENRE) ?: "Various"
                    val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

                    val rawState = playbackState?.state ?: PlaybackState.STATE_NONE
                    val statusStr = when (rawState) {
                        PlaybackState.STATE_PLAYING -> "PLAYING"
                        PlaybackState.STATE_PAUSED -> "PAUSED"
                        PlaybackState.STATE_BUFFERING -> "BUFFERING"
                        else -> "IDLE"
                    }

                    // Retrieve System queue sequence
                    val queueItems = controller.queue
                    val systemSongs = mutableListOf<Song>()
                    var matchedIdx = 0

                    queueItems?.forEachIndexed { idx, qItem ->
                        val itemTitle = qItem.description.title?.toString() ?: "Queue Track"
                        val itemArtist = qItem.description.subtitle?.toString() ?: "Unknown Artist"
                        val itemIdStr = qItem.queueId.toString()
                        
                        if (itemTitle == title) {
                            matchedIdx = idx
                        }

                        systemSongs.add(
                            Song(
                                id = itemIdStr,
                                title = itemTitle,
                                artist = itemArtist,
                                album = "",
                                genre = "Streamed",
                                duration = 0L,
                                uriString = ""
                            )
                        )
                    }

                    BluetoothStateUpdate(
                        status = statusStr,
                        currentIndex = matchedIdx,
                        elapsedTime = playbackState?.position ?: 0,
                        duration = duration,
                        currentTitle = title,
                        currentArtist = artist,
                        currentAlbum = album,
                        currentGenre = genre,
                        songs = if (includeMetadata) systemSongs else null,
                        maxVolume = maxVol,
                        currentVolume = currentVol
                    )
                } else {
                    BluetoothStateUpdate(
                        status = "IDLE",
                        currentIndex = 0,
                        elapsedTime = 0,
                        duration = 0,
                        currentTitle = "No Active Player Connected",
                        currentArtist = "Open Poweramp or local media app",
                        songs = emptyList(),
                        maxVolume = maxVol,
                        currentVolume = currentVol
                    )
                }
            } else {
                val player = exoPlayer
                if (player != null) {
                    val statusStr = if (player.isPlaying) "PLAYING" else if (player.playbackState == Player.STATE_BUFFERING) "BUFFERING" else "PAUSED"
                    val idx = player.currentMediaItemIndex
                    val currentSong = _hostSongs.value.getOrNull(idx)

                    BluetoothStateUpdate(
                        status = statusStr,
                        currentIndex = idx,
                        elapsedTime = player.currentPosition,
                        duration = if (player.duration < 0) 0 else player.duration,
                        currentTitle = currentSong?.title,
                        currentArtist = currentSong?.artist,
                        currentAlbum = currentSong?.album,
                        currentGenre = currentSong?.genre,
                        currentAlbumArt = currentSong?.albumArtUri,
                        songs = if (includeMetadata) _hostSongs.value else null,
                        maxVolume = maxVol,
                        currentVolume = currentVol
                    )
                } else {
                    BluetoothStateUpdate(
                        status = "IDLE",
                        currentIndex = 0,
                        elapsedTime = 0,
                        duration = 0,
                        maxVolume = maxVol,
                        currentVolume = currentVol
                    )
                }
            }

            bluetoothEngine.sendStateUpdate(update)
        }
    }

    private fun startSyncBroadcastLoop() {
        broadcastLoopJob?.cancel()
        broadcastLoopJob = scope.launch(Dispatchers.Main) {
            while (true) {
                if (isHostMode.value && bluetoothEngine.connectionState.value == BluetoothConnectionState.CONNECTED) {
                    broadcastHostStateToClient()
                }
                delay(900)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand running")
        updateForegroundNotification()
        return START_STICKY
    }

    private fun updateForegroundNotification() {
        val modeText = if (isHostMode.value) {
            if (hostUseNotificationHook.value) "Host: Hooking Poweramp player" else "Host: Native Media3 Audio"
        } else {
            "Client Controller Mode"
        }

        val detailsText = if (isHostMode.value) {
            "Listening for Client RFCOMM connections and control commands"
        } else {
            "Synchronizing playheads, genres, & high-fidelity gestures"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BlueSync Active Remote Bridge")
            .setContentText("$modeText - $detailsText")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                "Playback Service Controls",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Synchronized Bluetooth network playback and controls channel"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service Destroyed")
        broadcastLoopJob?.cancel()
        serviceJob.cancel()
        
        exoPlayer?.release()
        exoPlayer = null
        
        bluetoothEngine.cleanup()
        MyNotificationListener.onDataChangedListener = null
    }
}
