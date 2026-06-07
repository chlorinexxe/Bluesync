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

        fun getActiveController(): MediaController? {
            return instance?.currentController
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
                    "SEEK" -> seekPosition?.let { controls.seekTo(it) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fail executing third-party command: $command", e)
            }
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
        // Try to bind target player or get the first relevant music playing app in focus
        val newController = controllers?.firstOrNull()
        
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
