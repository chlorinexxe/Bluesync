package com.example

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.util.Log

private const val TAG = "NotificationListener"

class MyNotificationListener : NotificationListenerService() {

    private lateinit var mediaSessionManager: MediaSessionManager
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateActiveController(controllers)
    }

    private var currentController: MediaController? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            Log.d(TAG, "Hooked controller playback state changed: ${state?.state}")
            triggerUpdate()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            Log.d(TAG, "Hooked controller metadata changed: ${metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)}")
            triggerUpdate()
        }

        override fun onQueueChanged(queue: MutableList<android.media.session.MediaSession.QueueItem>?) {
            Log.d(TAG, "Hooked controller queue list changed. Size: ${queue?.size}")
            triggerUpdate()
        }

        override fun onSessionDestroyed() {
            Log.d(TAG, "Hooked session destroyed")
            currentController = null
            triggerUpdate()
        }
    }

    companion object {
        private var instance: MyNotificationListener? = null

        // Listener callback for the binder/VM
        var onDataChangedListener: (() -> Unit)? = null

        // Right after we call seekTo() on a hooked third-party session, its own PlaybackState
        // (position/lastPositionUpdateTime) is still stale until the app gets around to
        // reporting the new position - which can take a moment. Position estimation would
        // otherwise snap back to the pre-seek value for that window, which looks exactly like
        // "seeking doesn't work." Track the seek we issued and prefer our own optimistic
        // estimate until the app's own state actually catches up (or a grace period elapses).
        @Volatile private var pendingSeekPosition: Long? = null
        @Volatile private var pendingSeekSetAtRealtime: Long = 0L
        private const val SEEK_GRACE_MS = 2500L

        fun getActiveController(): MediaController? {
            return instance?.currentController
        }

        /** Estimated current playback position for a hooked controller's PlaybackState,
         * accounting for a just-issued local seek that the app hasn't confirmed yet. */
        fun estimatePosition(playbackState: PlaybackState?): Long {
            val pb = playbackState ?: return 0L
            val pending = pendingSeekPosition
            if (pending != null) {
                val now = android.os.SystemClock.elapsedRealtime()
                val elapsedSincePending = now - pendingSeekSetAtRealtime
                if (elapsedSincePending < SEEK_GRACE_MS && pb.lastPositionUpdateTime < pendingSeekSetAtRealtime) {
                    return if (pb.state == PlaybackState.STATE_PLAYING) pending + elapsedSincePending else pending
                }
                pendingSeekPosition = null
            }
            return if (pb.state == PlaybackState.STATE_PLAYING && pb.lastPositionUpdateTime > 0) {
                val diff = android.os.SystemClock.elapsedRealtime() - pb.lastPositionUpdateTime
                val speed = if (pb.playbackSpeed > 0f) pb.playbackSpeed else 1.0f
                pb.position + (diff * speed).toLong()
            } else {
                pb.position
            }
        }

        fun executeCommand(command: String, index: Int? = null, itemId: String? = null, seekPosition: Long? = null) {
            val controller = getActiveController() ?: return
            val controls = controller.transportControls
            try {
                when (command) {
                    "PLAY_INDEX", "SKIP_TO_QUEUE_ITEM" -> {
                        if (itemId != null) {
                            val idVal = itemId.toLongOrNull()
                            if (idVal != null) {
                                controls.skipToQueueItem(idVal)
                            }
                        } else if (index != null) {
                            // Some third-party music apps skip by index
                            controls.skipToQueueItem(index.toLong())
                        }
                    }
                    "TOGGLE_PLAY" -> {
                        val pbState = controller.playbackState?.state
                        if (pbState == PlaybackState.STATE_PLAYING) {
                            controls.pause()
                        } else {
                            controls.play()
                        }
                    }
                    "PAUSE" -> controls.pause()
                    "RESUME" -> controls.play()
                    "NEXT" -> controls.skipToNext()
                    "PREV" -> controls.skipToPrevious()
                    "SEEK" -> seekPosition?.let {
                        controls.seekTo(it)
                        pendingSeekPosition = it
                        pendingSeekSetAtRealtime = android.os.SystemClock.elapsedRealtime()
                    }
                    "TOGGLE_SHUFFLE" -> {
                        val currentShuffle = try {
                            val method = controller.javaClass.getMethod("getShuffleMode")
                            method.invoke(controller) as Int
                        } catch (e: Exception) {
                            0
                        }
                        val target = if (currentShuffle == 0) 1 else 0
                        try {
                            val setMethod = controls.javaClass.getMethod("setShuffleMode", Int::class.javaPrimitiveType)
                            setMethod.invoke(controls, target)
                        } catch (e: Exception) {
                            Log.e(TAG, "Fail to call setShuffleMode", e)
                        }
                    }
                    "TOGGLE_REPEAT" -> {
                        val currentRepeat = try {
                            val method = controller.javaClass.getMethod("getRepeatMode")
                            method.invoke(controller) as Int
                        } catch (e: Exception) {
                            0
                        }
                        val target = when (currentRepeat) {
                            0 -> 2
                            2 -> 1
                            else -> 0
                        }
                        try {
                            val setMethod = controls.javaClass.getMethod("setRepeatMode", Int::class.javaPrimitiveType)
                            setMethod.invoke(controls, target)
                        } catch (e: Exception) {
                            Log.e(TAG, "Fail to call setRepeatMode", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fail executing third-party command: $command", e)
            }
        }
    }

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        val pkg = sbn?.packageName ?: return
        if (pkg != packageName) {
            registerSessionManager()
            triggerUpdate()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        Log.d(TAG, "NotificationListener Created")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        unregisterSessionManager()
        Log.d(TAG, "NotificationListener Destroyed")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "NotificationListener Connected successfully")
        registerSessionManager()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "NotificationListener Disconnected")
        unregisterSessionManager()
    }

    private fun registerSessionManager() {
        try {
            val component = ComponentName(this, MyNotificationListener::class.java)
            mediaSessionManager.addOnActiveSessionsChangedListener(sessionsListener, component)
            
            // Fetch initial active sessions
            val controllerList = mediaSessionManager.getActiveSessions(component)
            updateActiveController(controllerList)
        } catch (e: SecurityException) {
            Log.e(TAG, "No notification permission to query active system sessions", e)
        } catch (e: Exception) {
            Log.e(TAG, "Fail registerSessionManager", e)
        }
    }

    private fun unregisterSessionManager() {
        try {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
            currentController?.unregisterCallback(controllerCallback)
            currentController = null
        } catch (e: Exception) {
            Log.e(TAG, "Fail unregisterSessionManager", e)
        }
    }

    private fun updateActiveController(controllers: List<MediaController>?) {
        // Never hook our own session (Media3 registers one in PlaybackService) - otherwise
        // BlueSync can end up "controlling itself" instead of the real third-party player.
        val candidates = controllers?.filter { it.packageName != packageName } ?: emptyList()

        // Prefer whichever session is actually playing (mirrors how the system picks the
        // "active" media notification); fall back to the first remaining candidate so a
        // paused-but-open player is still hooked when nothing else is playing.
        val newController = candidates.firstOrNull { controller ->
            controller.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: candidates.firstOrNull()

        if (newController?.packageName != currentController?.packageName) {
            currentController?.unregisterCallback(controllerCallback)
            currentController = newController
            currentController?.registerCallback(controllerCallback)
            Log.d(TAG, "Hooked active media player: ${newController?.packageName}")
            triggerUpdate()
        }
    }

    private fun triggerUpdate() {
        onDataChangedListener?.invoke()
    }
}
