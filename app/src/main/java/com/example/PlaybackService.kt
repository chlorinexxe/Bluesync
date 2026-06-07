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
    var mediaSession: androidx.media3.session.MediaSession? = null

    companion object {
        const val ACTION_PLAY_PAUSE = "com.example.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.example.ACTION_NEXT"
        const val ACTION_PREV = "com.example.ACTION_PREV"
    }

    val isHostMode = MutableStateFlow(true)
    val hostUseNotificationHook = MutableStateFlow(false)

    // Local host master tracks with MediaStore parameters
    private val _hostSongs = MutableStateFlow<List<Song>>(emptyList())
    val hostSongs = _hostSongs.asStateFlow()

    // Client Synchronized List
    private val _clientSongs = MutableStateFlow<List<Song>>(emptyList())
    val clientSongs = _clientSongs.asStateFlow()

    private val _hostNotificationSongs = MutableStateFlow<List<Song>>(emptyList())
    val hostNotificationSongs = _hostNotificationSongs.asStateFlow()

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
        val player = ExoPlayer.Builder(this).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (!hostUseNotificationHook.value) {
                        broadcastHostStateToClient()
                        updateForegroundNotification()
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!hostUseNotificationHook.value) {
                        broadcastHostStateToClient()
                        updateForegroundNotification()
                    }
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    if (!hostUseNotificationHook.value) {
                        broadcastHostStateToClient()
                        updateForegroundNotification()
                    }
                }
            })
        }
        exoPlayer = player

        try {
            mediaSession = androidx.media3.session.MediaSession.Builder(this, player).build()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create MediaSession Builder", e)
        }
        
        scanLocalAudioFiles()
        startSyncBroadcastLoop()
    }

    private fun setupNotificationListenerHook() {
        MyNotificationListener.onDataChangedListener = {
            if (isHostMode.value && hostUseNotificationHook.value) {
                broadcastHostStateToClient(includeMetadata = true)
                updateForegroundNotification()
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
                broadcastHostStateToClient(includeMetadata = true)
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
                updateForegroundNotification()
            }
        }

        bluetoothEngine.onConnectionStateChanged = { connectionState ->
            if (connectionState == BluetoothConnectionState.CONNECTED && isHostMode.value) {
                broadcastHostStateToClient(includeMetadata = true)
            }
            updateForegroundNotification()
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
                    "TOGGLE_SHUFFLE" -> {
                        player.shuffleModeEnabled = !player.shuffleModeEnabled
                    }
                    "TOGGLE_REPEAT" -> {
                        player.repeatMode = when (player.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                    }
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

                    val isShuffle = try {
                        val method = controller.javaClass.getMethod("getShuffleMode")
                        val modeValue = method.invoke(controller) as Int
                        modeValue != 0
                    } catch (e: Exception) {
                        false
                    }
                    val isRepeat = try {
                        val method = controller.javaClass.getMethod("getRepeatMode")
                        val modeValue = method.invoke(controller) as Int
                        when (modeValue) {
                            1 -> "ONE"
                            2 -> "ALL"
                            else -> "OFF"
                        }
                    } catch (e: Exception) {
                        "OFF"
                    }

                    // Retrieve System queue sequence
                    val queueItems = controller.queue
                    var matchedIdx = 0
                    queueItems?.forEachIndexed { idx, qItem ->
                        val itemTitle = qItem.description.title?.toString() ?: ""
                        if (itemTitle == title) {
                            matchedIdx = idx
                        }
                    }

                    // We now take the next 5 items starting after matchedIdx with 40x40 JPEGs
                    val systemSongs = mutableListOf<Song>()
                    if (queueItems != null && queueItems.isNotEmpty()) {
                        val startIndex = (matchedIdx + 1) % queueItems.size
                        for (i in 0 until 5) {
                            val qIdx = (startIndex + i) % queueItems.size
                            val qItem = queueItems[qIdx]
                            val itemTitle = qItem.description.title?.toString() ?: "Queue Track"
                            val itemArtist = qItem.description.subtitle?.toString() ?: "Unknown Artist"
                            val itemIdStr = qItem.queueId.toString()

                            var smallArtBase64: String? = null
                            val iconBitmap = qItem.description.iconBitmap
                            if (iconBitmap != null) {
                                smallArtBase64 = getSmallBase64AlbumArt(iconBitmap)
                            }

                            systemSongs.add(
                                Song(
                                    id = itemIdStr,
                                    title = itemTitle,
                                    artist = itemArtist,
                                    album = "",
                                    genre = "Streamed",
                                    duration = 0L,
                                    uriString = "",
                                    albumArtUri = smallArtBase64
                                )
                            )
                        }
                    } else {
                        // Fallback: Match current intercepted title to local scanned host library
                        val nativeSongs = _hostSongs.value
                        if (nativeSongs.isNotEmpty()) {
                            var matchedNativeIdx = nativeSongs.indexOfFirst {
                                it.title.equals(title, ignoreCase = true) || title.contains(it.title, ignoreCase = true) || it.title.contains(title, ignoreCase = true)
                            }
                            if (matchedNativeIdx == -1) matchedNativeIdx = 0

                            val startIndex = (matchedNativeIdx + 1) % nativeSongs.size
                            for (i in 0 until 5) {
                                val nIdx = (startIndex + i) % nativeSongs.size
                                val songItem = nativeSongs[nIdx]

                                var smallArtBase64: String? = null
                                if (songItem.albumArtUri != null) {
                                    try {
                                        val bitmap = getLocalAlbumArtBitmap(applicationContext, songItem.albumArtUri)
                                        if (bitmap != null) {
                                            smallArtBase64 = getSmallBase64AlbumArt(bitmap)
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Fail to get fallback native small art", e)
                                    }
                                }

                                systemSongs.add(
                                    Song(
                                        id = songItem.id,
                                        title = songItem.title,
                                        artist = songItem.artist,
                                        album = songItem.album,
                                        genre = songItem.genre,
                                        duration = songItem.duration,
                                        uriString = "",
                                        albumArtUri = smallArtBase64
                                    )
                                )
                            }
                        }
                    }

                    _hostNotificationSongs.value = systemSongs

                    val artBase64 = getBase64AlbumArt(controller = controller)

                    val exactElapsedTime = playbackState?.let { pb ->
                        if (pb.state == PlaybackState.STATE_PLAYING && pb.lastPositionUpdateTime > 0) {
                            val diff = android.os.SystemClock.elapsedRealtime() - pb.lastPositionUpdateTime
                            val speed = if (pb.playbackSpeed > 0f) pb.playbackSpeed else 1.0f
                            pb.position + (diff * speed).toLong()
                        } else {
                            pb.position
                        }
                    } ?: 0L

                    BluetoothStateUpdate(
                        status = statusStr,
                        currentIndex = matchedIdx,
                        elapsedTime = exactElapsedTime,
                        duration = duration,
                        currentTitle = title,
                        currentArtist = artist,
                        currentAlbum = album,
                        currentGenre = genre,
                        currentAlbumArt = artBase64,
                        songs = systemSongs,
                        maxVolume = maxVol,
                        currentVolume = currentVol,
                        shuffleActive = isShuffle,
                        repeatActive = isRepeat
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
                        currentVolume = currentVol,
                        shuffleActive = false,
                        repeatActive = "OFF"
                    )
                }
            } else {
                val player = exoPlayer
                if (player != null) {
                    val statusStr = if (player.isPlaying) "PLAYING" else if (player.playbackState == Player.STATE_BUFFERING) "BUFFERING" else "PAUSED"
                    val idx = player.currentMediaItemIndex
                    val currentSong = _hostSongs.value.getOrNull(idx)

                    val isShuffle = player.shuffleModeEnabled
                    val isRepeat = when (player.repeatMode) {
                        Player.REPEAT_MODE_ONE -> "ONE"
                        Player.REPEAT_MODE_ALL -> "ALL"
                        else -> "OFF"
                    }

                    val artBase64 = currentSong?.let { getBase64AlbumArt(song = it) }

                    // Construct next 5 upcoming tracks for client with 40x40 artwork thumbnail base64
                    val nextSongsToSend = mutableListOf<Song>()
                    val nativeSongs = _hostSongs.value
                    if (nativeSongs.isNotEmpty()) {
                        val startIndex = (idx + 1) % nativeSongs.size
                        for (i in 0 until 5) {
                            val nIdx = (startIndex + i) % nativeSongs.size
                            val songItem = nativeSongs[nIdx]

                            var smallArtBase64: String? = null
                            if (songItem.albumArtUri != null) {
                                try {
                                    val bitmap = getLocalAlbumArtBitmap(applicationContext, songItem.albumArtUri)
                                    if (bitmap != null) {
                                        smallArtBase64 = getSmallBase64AlbumArt(bitmap)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Fail to get native small art", e)
                                }
                            }

                            nextSongsToSend.add(
                                Song(
                                    id = songItem.id,
                                    title = songItem.title,
                                    artist = songItem.artist,
                                    album = songItem.album,
                                    genre = songItem.genre,
                                    duration = songItem.duration,
                                    uriString = "",
                                    albumArtUri = smallArtBase64
                                )
                            )
                        }
                    }

                    BluetoothStateUpdate(
                        status = statusStr,
                        currentIndex = idx,
                        elapsedTime = player.currentPosition,
                        duration = if (player.duration < 0) 0 else player.duration,
                        currentTitle = currentSong?.title,
                        currentArtist = currentSong?.artist,
                        currentAlbum = currentSong?.album,
                        currentGenre = currentSong?.genre,
                        currentAlbumArt = artBase64,
                        songs = nextSongsToSend,
                        maxVolume = maxVol,
                        currentVolume = currentVol,
                        shuffleActive = isShuffle,
                        repeatActive = isRepeat
                    )
                } else {
                    BluetoothStateUpdate(
                        status = "IDLE",
                        currentIndex = 0,
                        elapsedTime = 0,
                        duration = 0,
                        maxVolume = maxVol,
                        currentVolume = currentVol,
                        shuffleActive = false,
                        repeatActive = "OFF"
                    )
                }
            }

            bluetoothEngine.sendStateUpdate(update)
        }
    }

    private fun getSmallBase64AlbumArt(bitmap: android.graphics.Bitmap): String {
        return try {
            val outputStream = java.io.ByteArrayOutputStream()
            val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, 40, 40, true)
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 45, outputStream)
            val bytes = outputStream.toByteArray()
            val base64Encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            "data:image/jpeg;base64,$base64Encoded"
        } catch (e: Exception) {
            ""
        }
    }

    private fun getBase64AlbumArt(controller: MediaController? = null, song: Song? = null): String? {
        if (controller != null) {
            val metadata = controller.metadata
            val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            if (bitmap != null) {
                return compressBitmapToBase64(bitmap)
            }
        } else if (song != null) {
            val bitmap = getLocalAlbumArtBitmap(applicationContext, song.albumArtUri)
            if (bitmap != null) {
                return compressBitmapToBase64(bitmap)
            }
        }
        return null
    }

    private fun getLocalAlbumArtBitmap(context: Context, uriStr: String?): android.graphics.Bitmap? {
        if (uriStr == null) return null
        return try {
            val uri = Uri.parse(uriStr)
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                android.graphics.BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun compressBitmapToBase64(bitmap: android.graphics.Bitmap): String? {
        return try {
            val outputStream = java.io.ByteArrayOutputStream()
            val scaled = if (bitmap.width > 160 || bitmap.height > 160) {
                android.graphics.Bitmap.createScaledBitmap(bitmap, 160, 160, true)
            } else {
                bitmap
            }
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, outputStream)
            val bytes = outputStream.toByteArray()
            val base64Encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            "data:image/jpeg;base64,$base64Encoded"
        } catch (e: Exception) {
            Log.e(TAG, "Fail compress bitmap to base64", e)
            null
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
        Log.d(TAG, "onStartCommand running with action: ${intent?.action}")
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> triggerPlayPause()
            ACTION_NEXT -> triggerNext()
            ACTION_PREV -> triggerPrev()
        }
        updateForegroundNotification()
        return START_STICKY
    }

    fun triggerPlayPause() {
        scope.launch(Dispatchers.Main) {
            if (isHostMode.value) {
                if (hostUseNotificationHook.value) {
                    MyNotificationListener.executeCommand("TOGGLE_PLAY")
                } else {
                    val player = exoPlayer ?: return@launch
                    if (player.isPlaying) player.pause() else player.play()
                    broadcastHostStateToClient()
                }
            } else {
                val currentStatus = _clientPlaybackState.value?.status ?: "PAUSED"
                val cmdStr = if (currentStatus == "PLAYING") "PAUSE" else "RESUME"
                bluetoothEngine.sendCommand(BluetoothCommand(cmdStr))
            }
            updateForegroundNotification()
        }
    }

    fun triggerNext() {
        scope.launch(Dispatchers.Main) {
            if (isHostMode.value) {
                if (hostUseNotificationHook.value) {
                    MyNotificationListener.executeCommand("NEXT")
                } else {
                    exoPlayer?.seekToNextMediaItem()
                    broadcastHostStateToClient()
                }
            } else {
                bluetoothEngine.sendCommand(BluetoothCommand("NEXT"))
            }
            updateForegroundNotification()
        }
    }

    fun triggerPrev() {
        scope.launch(Dispatchers.Main) {
            if (isHostMode.value) {
                if (hostUseNotificationHook.value) {
                    MyNotificationListener.executeCommand("PREV")
                } else {
                    exoPlayer?.seekToPreviousMediaItem()
                    broadcastHostStateToClient()
                }
            } else {
                bluetoothEngine.sendCommand(BluetoothCommand("PREV"))
            }
            updateForegroundNotification()
        }
    }

    data class SimpleTrackInfo(
        val title: String,
        val artist: String,
        val isPlaying: Boolean,
        val albumArt: android.graphics.Bitmap? = null
    )

    fun getCurrentTrackInfo(): SimpleTrackInfo {
        if (isHostMode.value) {
            if (hostUseNotificationHook.value) {
                val controller = MyNotificationListener.getActiveController()
                val metadata = controller?.metadata
                val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "No Intercept Sync"
                val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Poweramp Native Hook"
                val rawState = controller?.playbackState?.state ?: PlaybackState.STATE_NONE
                val isPlaying = rawState == PlaybackState.STATE_PLAYING
                
                val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                return SimpleTrackInfo(title, artist, isPlaying, bitmap)
            } else {
                val player = exoPlayer
                val idx = player?.currentMediaItemIndex ?: -1
                val song = _hostSongs.value.getOrNull(idx)
                val title = song?.title ?: "Ready to Stream"
                val artist = song?.artist ?: "Offline Host"
                val isPlaying = player?.isPlaying ?: false
                
                val bitmap = if (song != null) getLocalAlbumArtBitmap(applicationContext, song.albumArtUri) else null
                return SimpleTrackInfo(title, artist, isPlaying, bitmap)
            }
        } else {
            val state = _clientPlaybackState.value
            val title = state?.currentTitle ?: "Bridge Idle"
            val artist = state?.currentArtist ?: "Select Host Source"
            val isPlaying = state?.status == "PLAYING"
            
            val bitmap = state?.currentAlbumArt?.let { base64 ->
                if (base64.startsWith("data:image")) {
                    try {
                        val clean = base64.substringAfter("base64,")
                        val bytes = android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } catch (e: Exception) {
                        null
                    }
                } else null
            }
            return SimpleTrackInfo(title, artist, isPlaying, bitmap)
        }
    }

    private fun updateForegroundNotification() {
        val trackInfo = getCurrentTrackInfo()

        val modeText = if (isHostMode.value) {
            if (hostUseNotificationHook.value) "Host Mode (Hooked)" else "Host Mode (Native)"
        } else {
            "Client Mode (Remote)"
        }

        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        } else {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT
        }

        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPending = if (openAppIntent != null) {
            android.app.PendingIntent.getActivity(this, 0, openAppIntent, flag)
        } else null

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppPending)

        if (isHostMode.value) {
            val detail = if (hostUseNotificationHook.value) "Broadcasting local active controllers" else "Broadcasting native Media3 player"
            builder.setContentTitle("BlueSync Host Bridge Active")
                .setContentText(detail)
                .setSubText("Host Terminal")
        } else {
            val prevIntent = Intent(this, PlaybackService::class.java).apply { action = ACTION_PREV }
            val prevPending = android.app.PendingIntent.getService(this, 1, prevIntent, flag)

            val playIntent = Intent(this, PlaybackService::class.java).apply { action = ACTION_PLAY_PAUSE }
            val playPending = android.app.PendingIntent.getService(this, 2, playIntent, flag)

            val nextIntent = Intent(this, PlaybackService::class.java).apply { action = ACTION_NEXT }
            val nextPending = android.app.PendingIntent.getService(this, 3, nextIntent, flag)

            builder.setContentTitle(trackInfo.title)
                .setContentText(trackInfo.artist)
                .setSubText("Client Remote")
                .addAction(android.R.drawable.ic_media_previous, "Previous", prevPending)
                .addAction(
                    if (trackInfo.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (trackInfo.isPlaying) "Pause" else "Play",
                    playPending
                )
                .addAction(android.R.drawable.ic_media_next, "Next", nextPending)

            if (trackInfo.albumArt != null) {
                builder.setLargeIcon(trackInfo.albumArt)
            }

            val session = mediaSession
            if (session != null) {
                val mediaStyle = androidx.media3.session.MediaStyleNotificationHelper.MediaStyle(session)
                    .setShowActionsInCompactView(0, 1, 2)
                builder.setStyle(mediaStyle)
            }
        }

        val notification = builder.build()

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
        
        try {
            mediaSession?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Failed release MediaSession", e)
        }
        mediaSession = null
        
        exoPlayer?.release()
        exoPlayer = null
        
        bluetoothEngine.cleanup()
        MyNotificationListener.onDataChangedListener = null
    }
}
