package com.example.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.example.MyNotificationListener
import com.example.haptic.PremiumHapticDriver
import kotlinx.coroutines.flow.MutableStateFlow

// BlueSync's minimal dark palette: one flat background, one accent per role (host vs remote).
// Kept deliberately small - a minimal UI doesn't need a big color system.
val MidnightSpaceBg = Color(0xFF07080D)
val DeepIndigoGlow = Color(0xFF0F1220)
val GlassSurface = Color(0x14FFFFFF)
val CyanGlow = Color(0xFF4FD1E5)
val RosePulse = Color(0xFFE85C8A)
val PureWhite = Color(0xFFFFFFFF)

@Composable
fun PremiumPlayerUI(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val hapticDriver = remember { PremiumHapticDriver(context) }

    val service by viewModel.service.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val pairedDevices by viewModel.pairedDevices.collectAsState()
    val nearbyBleDevices by viewModel.nearbyBleDevices.collectAsState()
    // Deliberately NOT collected here - it ticks every 100ms, and PlayerUiState carries a
    // List<Song> (displaySongs), which Compose can't prove immutable, making the whole state
    // object "unstable." Reading position at this level would force the entire screen -
    // header, queue list, everything - to fully recompose 10x/second. Instead the raw flow is
    // handed down to just the seek bar, which is the only thing that actually needs it.
    val positionFlow = viewModel.currentPlaybackPosition

    // Host or Client app state
    val isHostMode = service?.isHostMode?.collectAsState()?.value ?: true
    val hostUseNotificationHook = service?.hostUseNotificationHook?.collectAsState()?.value ?: false
    val bluetoothState = service?.bluetoothEngine?.connectionState?.collectAsState()?.value
        ?: com.example.bluetooth.BluetoothConnectionState.DISCONNECTED
    val connectedDevice = service?.bluetoothEngine?.connectedDeviceName?.collectAsState()?.value

    // Playback info
    val currentTrackIndex by (service?.currentTrackIndex ?: MutableStateFlow(0)).collectAsState()
    val localSongs by (service?.hostSongs ?: MutableStateFlow(emptyList())).collectAsState()
    val hostNotificationSongs by (service?.hostNotificationSongs ?: MutableStateFlow(emptyList())).collectAsState()
    val clientSongsPreview by (service?.clientSongs ?: MutableStateFlow(emptyList())).collectAsState()
    val clientLibraryPages by (service?.clientLibraryPages ?: MutableStateFlow(emptyList())).collectAsState()
    val clientState by (service?.clientPlaybackState ?: MutableStateFlow(null)).collectAsState()

    var showScanSheet by remember { mutableStateOf(false) }

    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager }
    var localVolume by remember { mutableStateOf(audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)) }
    val maxColVolume = remember { audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) }

    val maxVolume = if (isHostMode) maxColVolume else (clientState?.maxVolume ?: 15)
    val currentVolumeVal = if (isHostMode) localVolume else (clientState?.currentVolume ?: 0)

    LaunchedEffect(Unit) {
        while (true) {
            if (isHostMode) {
                val sysVol = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                if (localVolume != sysVol) {
                    localVolume = sysVol
                }
            }
            kotlinx.coroutines.delay(1000)
        }
    }

    // Now-playing metadata, derived once regardless of which layout renders it
    val currentTrackTitle = if (isHostMode) {
        if (hostUseNotificationHook) {
            MyNotificationListener.getActiveController()?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
                ?: "Nothing playing"
        } else {
            localSongs.getOrNull(currentTrackIndex)?.title ?: "Your library"
        }
    } else {
        clientState?.currentTitle ?: "Not connected"
    }

    val currentTrackArtist = if (isHostMode) {
        if (hostUseNotificationHook) {
            MyNotificationListener.getActiveController()?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
                ?: "Open a music app to begin"
        } else {
            localSongs.getOrNull(currentTrackIndex)?.artist ?: "Pick a song below"
        }
    } else {
        clientState?.currentArtist ?: "Find a host to connect"
    }

    val currentTrackAlbum = if (isHostMode) {
        if (hostUseNotificationHook) {
            MyNotificationListener.getActiveController()?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        } else {
            localSongs.getOrNull(currentTrackIndex)?.album ?: ""
        }
    } else {
        clientState?.currentAlbum ?: ""
    }

    val currentTrackGenre = if (isHostMode) {
        if (hostUseNotificationHook) {
            MyNotificationListener.getActiveController()?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_GENRE) ?: ""
        } else {
            localSongs.getOrNull(currentTrackIndex)?.genre ?: ""
        }
    } else {
        clientState?.currentGenre ?: ""
    }

    val currentTrackArtUri: Any? = if (isHostMode) {
        if (hostUseNotificationHook) {
            val metadata = MyNotificationListener.getActiveController()?.metadata
            metadata?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART)
                ?: metadata?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART)
        } else {
            localSongs.getOrNull(currentTrackIndex)?.albumArtUri
        }
    } else {
        val base64 = clientState?.currentAlbumArt
        // currentPosition updates every 100ms, recomposing this whole function - without
        // remember(), this decode was re-running on the main thread ~10x/second regardless of
        // whether the art actually changed, which is exactly the kind of sustained main-thread
        // work that trips an ANR. Only re-decode when the actual art payload changes.
        remember(base64) {
            if (base64 != null && base64.startsWith("data:image")) {
                try {
                    val clean = base64.substringAfter("base64,")
                    val bytes = android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (e: Exception) {
                    null
                }
            } else null
        }
    }

    val isShuffleActive = if (isHostMode) {
        if (hostUseNotificationHook) {
            val controller = MyNotificationListener.getActiveController()
            if (controller != null) {
                try {
                    (controller.javaClass.getMethod("getShuffleMode").invoke(controller) as Int) != 0
                } catch (e: Exception) {
                    false
                }
            } else false
        } else {
            service?.exoPlayer?.shuffleModeEnabled ?: false
        }
    } else {
        clientState?.shuffleActive ?: false
    }

    val repeatStateString = if (isHostMode) {
        if (hostUseNotificationHook) {
            val controller = MyNotificationListener.getActiveController()
            if (controller != null) {
                try {
                    when (controller.javaClass.getMethod("getRepeatMode").invoke(controller) as Int) {
                        1 -> "ONE"
                        2 -> "ALL"
                        else -> "OFF"
                    }
                } catch (e: Exception) {
                    "OFF"
                }
            } else "OFF"
        } else {
            when (service?.exoPlayer?.repeatMode) {
                androidx.media3.common.Player.REPEAT_MODE_ONE -> "ONE"
                androidx.media3.common.Player.REPEAT_MODE_ALL -> "ALL"
                else -> "OFF"
            }
        }
    } else {
        clientState?.repeatActive ?: "OFF"
    }

    val totalDuration = if (isHostMode) {
        if (hostUseNotificationHook) {
            MyNotificationListener.getActiveController()?.metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        } else {
            service?.exoPlayer?.duration ?: 0L
        }
    } else {
        clientState?.duration ?: 0L
    }

    val isPlaying = if (isHostMode) {
        if (hostUseNotificationHook) {
            MyNotificationListener.getActiveController()?.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
        } else {
            service?.exoPlayer?.isPlaying ?: false
        }
    } else {
        clientState?.status == "PLAYING"
    }

    val displaySongs = if (isHostMode) {
        if (hostUseNotificationHook) hostNotificationSongs else localSongs
    } else {
        // The regular "up next" preview first, then whatever's been paginated in by scrolling
        // - kept as two separate flows upstream so a metadata sync mid-scroll can't wipe the
        // paginated part back down to just the preview.
        clientSongsPreview + clientLibraryPages
    }

    val activeQueueIndex = if (isHostMode) currentTrackIndex else (clientState?.currentIndex ?: -1)
    val accentColor = if (isHostMode) CyanGlow else RosePulse

    // Permission handling for Bluetooth discovery/connect, media library and notifications
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { map ->
        val btOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (map[Manifest.permission.BLUETOOTH_SCAN] == true) &&
                (map[Manifest.permission.BLUETOOTH_CONNECT] == true) &&
                (map[Manifest.permission.BLUETOOTH_ADVERTISE] == true)
        } else true
        if (btOk) {
            viewModel.refreshPairedDevices()
            viewModel.startBleAdvertising()
        }
    }

    LaunchedEffect(Unit) {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    // Best-effort request to survive OEM battery-optimization killers while the screen is off
    // and the app is backgrounded - the foreground service + connection wake lock handle the
    // rest. Shown once per app open, not nagged on every recomposition.
    LaunchedEffect(Unit) {
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                    }
                )
            }
        } catch (e: Exception) {
            // Some OEMs restrict this intent entirely - not fatal, just skip it.
        }
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isCompact = isLandscape || configuration.screenHeightDp < 780
    val hookAuthorized = checkNotificationFilterAuth(context)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MidnightSpaceBg)
    ) {
        val state = PlayerUiState(
            isHostMode = isHostMode,
            hostUseNotificationHook = hostUseNotificationHook,
            bluetoothState = bluetoothState,
            connectedDevice = connectedDevice,
            accentColor = accentColor,
            trackTitle = currentTrackTitle,
            trackArtist = currentTrackArtist,
            trackAlbum = currentTrackAlbum,
            trackGenre = currentTrackGenre,
            trackArtUri = currentTrackArtUri,
            isPlaying = isPlaying,
            totalDuration = totalDuration,
            isShuffleActive = isShuffleActive,
            repeatState = repeatStateString,
            currentVolume = currentVolumeVal,
            maxVolume = maxVolume,
            displaySongs = displaySongs,
            activeQueueIndex = activeQueueIndex,
            hookAuthorized = hookAuthorized
        )

        val isConnected = bluetoothState == com.example.bluetooth.BluetoothConnectionState.CONNECTED
        val actions = PlayerActions(
            // While connected, switching the role you tap should swap live with the peer
            // instead of yanking the connection out from under both phones.
            onSelectHostMode = {
                hapticDriver.triggerClick()
                if (!isHostMode && isConnected) viewModel.swapRoles() else viewModel.toggleAppMode(true)
            },
            onSelectClientMode = {
                hapticDriver.triggerClick()
                if (isHostMode && isConnected) viewModel.swapRoles() else viewModel.toggleAppMode(false)
            },
            onToggleSource = { hook -> hapticDriver.triggerClick(); viewModel.toggleHostSource(hook) },
            onStartHostServer = { hapticDriver.triggerClick(); viewModel.startBluetoothHostServer() },
            onDisconnect = { hapticDriver.triggerClick(); viewModel.disconnectBluetooth() },
            onFindDevices = { hapticDriver.triggerClick(); showScanSheet = true },
            onPlayPause = { hapticDriver.triggerClick(); viewModel.togglePlayPause() },
            onPrevious = { hapticDriver.triggerSkipPulse(); viewModel.playPrevious() },
            onNext = { hapticDriver.triggerSkipPulse(); viewModel.playNext() },
            onToggleShuffle = { hapticDriver.triggerClick(); viewModel.toggleShuffle() },
            onToggleRepeat = { hapticDriver.triggerClick(); viewModel.toggleRepeat() },
            onSeekPreview = { hapticDriver.triggerTick() },
            onSeek = { position -> viewModel.seekToPosition(position) },
            onVolumeChange = { vol ->
                if (isHostMode) localVolume = vol
                viewModel.setVolume(vol)
                hapticDriver.triggerTick()
            },
            onGrantHookAccess = {
                hapticDriver.triggerClick()
                android.widget.Toast.makeText(context, "Find \"BlueSync Player\" in the list and turn it on", android.widget.Toast.LENGTH_LONG).show()
                context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            },
            onSelectSong = { song, idx -> hapticDriver.triggerClick(); viewModel.playSongWithId(song.id, idx) },
            onLoadMoreSongs = { viewModel.requestMoreSongs() }
        )

        if (isLandscape) {
            LandscapePlayerLayout(state = state, actions = actions, positionFlow = positionFlow)
        } else {
            PortraitPlayerLayout(state = state, actions = actions, compact = isCompact, positionFlow = positionFlow)
        }

        if (showScanSheet) {
            BluetoothScanOverlay(
                isScanning = isScanning,
                discoveredDevices = discoveredDevices,
                pairedDevices = pairedDevices,
                nearbyBleDevices = nearbyBleDevices,
                onRefreshScan = { hapticDriver.triggerClick(); viewModel.startBluetoothScan() },
                onStopScan = { viewModel.stopBluetoothScan() },
                onDeviceSelected = { dev ->
                    hapticDriver.triggerClick()
                    viewModel.connectToBluetoothDevice(dev)
                    showScanSheet = false
                },
                onDismiss = {
                    hapticDriver.triggerClick()
                    viewModel.stopBluetoothScan()
                    showScanSheet = false
                }
            )
        }
    }
}

fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val secondsTotal = ms / 1000
    val minutes = secondsTotal / 60
    val seconds = secondsTotal % 60
    return String.format("%d:%02d", minutes, seconds)
}

private fun checkNotificationFilterAuth(context: Context): Boolean {
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return flat != null && flat.contains(context.packageName)
}
