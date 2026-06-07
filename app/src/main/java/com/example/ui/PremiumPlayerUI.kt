package com.example.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.MyNotificationListener
import com.example.bluetooth.BluetoothConnectionState
import com.example.haptic.PremiumHapticDriver
import com.example.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToLong
import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.Canvas

// High-end glassmorphic UI color definitions
val MidnightSpaceBg = Color(0xFF040610)
val DeepIndigoGlow = Color(0xFF0A0F2E)
val GlassSurface = Color(0x18FFFFFF)
val GlassBorder = Color(0x1EFFFFFF)
val CyanGlow = Color(0xFF00E5FF)
val RosePulse = Color(0xFFFF2A85)
val PureWhite = Color(0xFFFFFFFF)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    val currentPosition by viewModel.currentPlaybackPosition.collectAsState()

    // Host or Client App state
    val isHostMode = service?.isHostMode?.collectAsState()?.value ?: true
    val hostUseNotificationHook = service?.hostUseNotificationHook?.collectAsState()?.value ?: false
    val bluetoothState = service?.bluetoothEngine?.connectionState?.collectAsState()?.value ?: BluetoothConnectionState.DISCONNECTED
    val connectedDevice = service?.bluetoothEngine?.connectedDeviceName?.collectAsState()?.value

    // Playback info
    val localSongs by (service?.hostSongs ?: MutableStateFlow(emptyList())).collectAsState()
    val hostNotificationSongs by (service?.hostNotificationSongs ?: MutableStateFlow(emptyList())).collectAsState()
    val clientSongs by (service?.clientSongs ?: MutableStateFlow(emptyList())).collectAsState()
    val clientState by (service?.clientPlaybackState ?: MutableStateFlow(null)).collectAsState()

    // Scopes and dialog sheets
    var showScanSheet by remember { mutableStateOf(false) }

    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager }
    var localVolume by remember { mutableStateOf(audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)) }
    val maxColVolume = remember { audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) }

    val maxVolume = if (isHostMode) {
        maxColVolume
    } else {
        clientState?.maxVolume ?: 15
    }

    val currentVolumeVal = if (isHostMode) {
        localVolume
    } else {
        clientState?.currentVolume ?: 0
    }

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

    // Dynamic coloring based on current song
    val currentTrackTitle = if (isHostMode) {
        if (hostUseNotificationHook) {
            val controller = MyNotificationListener.getActiveController()
            controller?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: "No Intercept Sync"
        } else {
            val idx = service?.exoPlayer?.currentMediaItemIndex ?: -1
            localSongs.getOrNull(idx)?.title ?: "Ready to Stream"
        }
    } else {
        clientState?.currentTitle ?: "Bridge Idle"
    }

    val currentTrackArtist = if (isHostMode) {
        if (hostUseNotificationHook) {
            val controller = MyNotificationListener.getActiveController()
            controller?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: "Poweramp Native Hook"
        } else {
            val idx = service?.exoPlayer?.currentMediaItemIndex ?: -1
            localSongs.getOrNull(idx)?.artist ?: "Offline Host"
        }
    } else {
        clientState?.currentArtist ?: "Select Host Source"
    }

    val currentTrackAlbum = if (isHostMode) {
        if (hostUseNotificationHook) {
            val controller = MyNotificationListener.getActiveController()
            controller?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        } else {
            val idx = service?.exoPlayer?.currentMediaItemIndex ?: -1
            localSongs.getOrNull(idx)?.album ?: "Unknown Album"
        }
    } else {
        clientState?.currentAlbum ?: ""
    }

    val currentTrackGenre = if (isHostMode) {
        if (hostUseNotificationHook) {
            val controller = MyNotificationListener.getActiveController()
            controller?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_GENRE) ?: "Various"
        } else {
            val idx = service?.exoPlayer?.currentMediaItemIndex ?: -1
            localSongs.getOrNull(idx)?.genre ?: "Industrial"
        }
    } else {
        clientState?.currentGenre ?: "Ambient"
    }

    val currentTrackArtUri: Any? = if (isHostMode) {
        if (hostUseNotificationHook) {
            val controller = MyNotificationListener.getActiveController()
            val metadata = controller?.metadata
            val bitmap = metadata?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART)
                ?: metadata?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART)
            bitmap
        } else {
            val idx = service?.exoPlayer?.currentMediaItemIndex ?: -1
            localSongs.getOrNull(idx)?.albumArtUri
        }
    } else {
        val base64 = clientState?.currentAlbumArt
        if (base64 != null && base64.startsWith("data:image")) {
            try {
                val clean = base64.substringAfter("base64,")
                val bytes = android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    val isShuffleActive = if (isHostMode) {
        if (hostUseNotificationHook) {
            val controller = MyNotificationListener.getActiveController()
            if (controller != null) {
                try {
                    val method = controller.javaClass.getMethod("getShuffleMode")
                    val modeValue = method.invoke(controller) as Int
                    modeValue != 0
                } catch (e: Exception) {
                    false
                }
            } else {
                false
            }
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
            } else {
                "OFF"
            }
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
            val controller = MyNotificationListener.getActiveController()
            controller?.metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        } else {
            service?.exoPlayer?.duration ?: 0L
        }
    } else {
        clientState?.duration ?: 0L
    }

    val isPlaying = if (isHostMode) {
        if (hostUseNotificationHook) {
            val controller = MyNotificationListener.getActiveController()
            val state = controller?.playbackState?.state
            state == android.media.session.PlaybackState.STATE_PLAYING
        } else {
            service?.exoPlayer?.isPlaying ?: false
        }
    } else {
        clientState?.status == "PLAYING"
    }

    // Permission launcher for Bluetooth discovery and connect
    var bluetoothPermissionGranted by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { map ->
        val ok = map.values.all { it }
        bluetoothPermissionGranted = ok
        if (ok) {
            viewModel.refreshPairedDevices()
        }
    }

    // Direct check on Compose startup
    LaunchedEffect(Unit) {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (perms.isNotEmpty()) {
            permissionLauncher.launch(perms.toTypedArray())
        } else {
            bluetoothPermissionGranted = true
            viewModel.refreshPairedDevices()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(MidnightSpaceBg, DeepIndigoGlow, MidnightSpaceBg)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Master Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "BLUESYNC BRIDGE",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        color = PureWhite,
                        style = MaterialTheme.typography.titleMedium.copy(
                            shadow = Shadow(
                                color = CyanGlow.copy(alpha = 0.5f),
                                offset = Offset(0f, 4f),
                                blurRadius = 8f
                            )
                        )
                    )
                    Text(
                        text = if (isHostMode) "Primary Broadcast Terminal" else "Client Remote Interception Hub",
                        fontSize = 11.sp,
                        color = Color.LightGray.copy(alpha = 0.6f),
                        fontWeight = FontWeight.Medium
                    )
                }

                // Mode Swapping Toggle Slider
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(GlassSurface)
                        .padding(3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "HOST",
                        color = if (isHostMode) CyanGlow else Color.Gray,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isHostMode) GlassSurface else Color.Transparent)
                            .clickable {
                                hapticDriver.triggerClick()
                                viewModel.toggleAppMode(true)
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                    Text(
                        text = "CLIENT",
                        color = if (!isHostMode) RosePulse else Color.Gray,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (!isHostMode) GlassSurface else Color.Transparent)
                            .clickable {
                                hapticDriver.triggerClick()
                                viewModel.toggleAppMode(false)
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            // Connection Status Panel
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = GlassSurface),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val connectionIcon = when (bluetoothState) {
                            BluetoothConnectionState.DISCONNECTED -> Icons.Rounded.BluetoothDisabled
                            BluetoothConnectionState.CONNECTING -> Icons.Rounded.BluetoothSearching
                            BluetoothConnectionState.CONNECTED -> Icons.Rounded.BluetoothConnected
                            BluetoothConnectionState.LISTENING -> Icons.Rounded.CellTower
                        }
                        val connectionColor = when (bluetoothState) {
                            BluetoothConnectionState.DISCONNECTED -> Color.Gray
                            BluetoothConnectionState.CONNECTING -> CyanGlow
                            BluetoothConnectionState.CONNECTED -> RosePulse
                            BluetoothConnectionState.LISTENING -> CyanGlow
                        }
                        val connectionMsg = when (bluetoothState) {
                            BluetoothConnectionState.DISCONNECTED -> "Offline / Idle"
                            BluetoothConnectionState.CONNECTING -> "Registering Socket..."
                            BluetoothConnectionState.CONNECTED -> "Bridge Established"
                            BluetoothConnectionState.LISTENING -> "Broadcasting (UUID-Spp)"
                        }

                        Icon(
                            imageVector = connectionIcon,
                            contentDescription = "Bluetooth Status icon",
                            tint = connectionColor,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = connectionMsg,
                                color = PureWhite,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                            if (bluetoothState == BluetoothConnectionState.CONNECTED && connectedDevice != null) {
                                Text(
                                    text = "Device: $connectedDevice",
                                    color = Color.LightGray.copy(alpha = 0.5f),
                                    fontSize = 10.sp
                                )
                            } else if (bluetoothState == BluetoothConnectionState.LISTENING) {
                                Text(
                                    text = "RFCOMM Port open - listening",
                                    color = Color.LightGray.copy(alpha = 0.5f),
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }

                    // Main Action button for connections inside status card
                    if (isHostMode) {
                        if (bluetoothState == BluetoothConnectionState.DISCONNECTED) {
                            Button(
                                onClick = {
                                    hapticDriver.triggerClick()
                                    viewModel.startBluetoothHostServer()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CyanGlow),
                                modifier = Modifier.testTag("open_host_button")
                            ) {
                                Text("Host Server", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    hapticDriver.triggerClick()
                                    viewModel.disconnectBluetooth()
                                },
                                modifier = Modifier.testTag("disconnect_host_button")
                            ) {
                                Text("Close", color = PureWhite, fontSize = 11.sp)
                            }
                        }
                    } else {
                        if (bluetoothState == BluetoothConnectionState.DISCONNECTED) {
                            Button(
                                onClick = {
                                    hapticDriver.triggerClick()
                                    showScanSheet = true
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = RosePulse)
                            ) {
                                Text("Find Hosts", color = PureWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    hapticDriver.triggerClick()
                                    viewModel.disconnectBluetooth()
                                },
                                modifier = Modifier.testTag("disconnect_client_button")
                            ) {
                                Text("Disconnect", color = PureWhite, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(visible = isHostMode) {
                // Host Mode specific controls: Notification Listener check & mode swap
                HostControlsSelectionCard(
                    useNotificationHook = hostUseNotificationHook,
                    onToggle = { hook ->
                        hapticDriver.triggerClick()
                        viewModel.toggleHostSource(hook)
                    }
                )
            }

            // Central Dynamic Media Screen Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.3f)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = GlassSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Album art cover slot (Fully supports MediaStore and missing states gracefully)
                    Box(
                        modifier = Modifier
                            .size(125.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.05f))
                            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (currentTrackArtUri != null) {
                             AsyncImage(
                                 model = currentTrackArtUri,
                                 contentDescription = "Intercepted album art",
                                 contentScale = ContentScale.Crop,
                                 modifier = Modifier.fillMaxSize()
                             )
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = if (hostUseNotificationHook) Icons.Rounded.WifiTethering else Icons.Rounded.Headphones,
                                    contentDescription = "Fallback album art icon",
                                    tint = if (isHostMode) CyanGlow else RosePulse,
                                    modifier = Modifier.size(40.dp)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = currentTrackGenre,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.LightGray.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }

                    // Metadata Text Grouping (Title & Artist)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = currentTrackTitle,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = PureWhite,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .basicMarquee()
                                .padding(horizontal = 10.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = currentTrackArtist,
                            fontSize = 13.sp,
                            color = Color.LightGray.copy(alpha = 0.7f),
                            maxLines = 1
                        )
                        if (currentTrackAlbum.isNotEmpty()) {
                            Text(
                                text = "Album: $currentTrackAlbum",
                                fontSize = 11.sp,
                                color = Color.LightGray.copy(alpha = 0.4f),
                                maxLines = 1
                            )
                        }
                    }

                    // Sliding Progressive Media Timers with Micro-haptic updates and animated wave!
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = "Active Seek Label Icon",
                                tint = if (isHostMode) CyanGlow else RosePulse,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "LIVE AUDIO PLAYHEAD",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isHostMode) CyanGlow.copy(alpha = 0.8f) else RosePulse.copy(alpha = 0.8f),
                                letterSpacing = 1.sp
                            )
                        }

                        val totalDurationSec = totalDuration / 1000f
                        val currentPositionSec = currentPosition / 1000f
                        val rawSliderVal = if (totalDurationSec > 0f) currentPositionSec / totalDurationSec else 0f
                        var localDragFraction by remember { mutableStateOf<Float?>(null) }
                        val activeSliderValue = localDragFraction ?: rawSliderVal

                        MaterialWaveSeekBar(
                            value = activeSliderValue.coerceIn(0f, 1f),
                            onValueChange = { fraction ->
                                localDragFraction = fraction
                                hapticDriver.triggerTick()
                            },
                            onValueChangeFinished = {
                                localDragFraction?.let { fraction ->
                                    val newPos = (fraction * totalDuration).roundToLong()
                                    viewModel.seekToPosition(newPos)
                                }
                                localDragFraction = null
                            },
                            isPlaying = isPlaying,
                            activeColor = if (isHostMode) CyanGlow else RosePulse,
                            inactiveColor = Color.White.copy(alpha = 0.08f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp)
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatDuration(currentPosition),
                                fontSize = 11.sp,
                                color = Color.LightGray.copy(alpha = 0.6f)
                            )
                            Text(
                                text = formatDuration(totalDuration),
                                fontSize = 11.sp,
                                color = Color.LightGray.copy(alpha = 0.6f)
                            )
                        }
                    }

                    // Master Control Row with Shuffle, Prev, Play/Pause, Next, and Repeat!
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Shuffle Button
                        IconButton(
                            onClick = {
                                hapticDriver.triggerClick()
                                viewModel.toggleShuffle()
                            },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Shuffle,
                                contentDescription = "Toggle shuffle",
                                tint = if (isShuffleActive) {
                                    if (isHostMode) CyanGlow else RosePulse
                                } else {
                                    Color.Gray
                                },
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Skip Previous
                        IconButton(
                            onClick = {
                                hapticDriver.triggerSkipPulse()
                                viewModel.playPrevious()
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SkipPrevious,
                                contentDescription = "Skip previous button",
                                tint = PureWhite,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        // Play/Pause FAB
                        FloatingActionButton(
                            onClick = {
                                hapticDriver.triggerClick()
                                viewModel.togglePlayPause()
                            },
                            containerColor = if (isHostMode) CyanGlow else RosePulse,
                            shape = CircleShape,
                            modifier = Modifier.size(54.dp)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = "Play toggle pause button",
                                tint = if (isHostMode) Color.Black else PureWhite,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        // Skip Next
                        IconButton(
                            onClick = {
                                hapticDriver.triggerSkipPulse()
                                viewModel.playNext()
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SkipNext,
                                contentDescription = "Skip next button",
                                tint = PureWhite,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        // Repeat/Replay Button
                        IconButton(
                            onClick = {
                                hapticDriver.triggerClick()
                                viewModel.toggleRepeat()
                            },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = if (repeatStateString == "ONE") Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                                contentDescription = "Toggle repeat",
                                tint = if (repeatStateString != "OFF") {
                                    if (isHostMode) CyanGlow else RosePulse
                                } else {
                                    Color.Gray
                                },
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.VolumeUp,
                            contentDescription = "Active Volume Label Icon",
                            tint = if (isHostMode) CyanGlow else RosePulse,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "REMOTE AUDIO VOLUME CONTROL",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isHostMode) CyanGlow.copy(alpha = 0.8f) else RosePulse.copy(alpha = 0.8f),
                            letterSpacing = 1.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Volume Control Slider Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (currentVolumeVal == 0) Icons.Rounded.VolumeMute else Icons.Rounded.VolumeUp,
                            contentDescription = "Volume State Icon",
                            tint = if (isHostMode) CyanGlow else RosePulse,
                            modifier = Modifier.size(20.dp)
                        )

                        Slider(
                            value = currentVolumeVal.toFloat(),
                            valueRange = 0f..maxVolume.toFloat(),
                            onValueChange = { newValue ->
                                val targetVol = newValue.toInt()
                                if (isHostMode) {
                                    localVolume = targetVol
                                }
                                viewModel.setVolume(targetVol)
                                hapticDriver.triggerTick()
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = if (isHostMode) CyanGlow else RosePulse,
                                activeTrackColor = if (isHostMode) CyanGlow else RosePulse,
                                inactiveTrackColor = Color.White.copy(alpha = 0.1f)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 10.dp)
                        )

                        Icon(
                            imageVector = Icons.Rounded.VolumeUp,
                            contentDescription = "Volume Up Icon",
                            tint = if (isHostMode) CyanGlow else RosePulse,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Lower Screen: Remote Tracks or local MediaStore Sequence List
            Text(
                text = if (isHostMode) {
                    if (hostUseNotificationHook) "Intercepted Sync Sequence" else "Native Host Library"
                } else {
                    "Synchronized Upcoming Queue (Next 5)"
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (isHostMode) CyanGlow else RosePulse,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
            )

            val systemSongs = hostNotificationSongs

            // Dynamic Song sequence drawer column list
            val displaySongs = if (isHostMode) {
                if (hostUseNotificationHook) {
                    systemSongs
                } else {
                    localSongs
                }
            } else {
                clientSongs
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.9f)
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(Color.White.copy(alpha = 0.03f))
                    .border(1.dp, GlassBorder, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            ) {
                val auth = checkNotificationFilterAuth(context)
                if (isHostMode && hostUseNotificationHook && (!auth || displaySongs.isEmpty())) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Icon(
                                imageVector = if (auth) Icons.Rounded.CheckCircle else Icons.Rounded.LockOpen,
                                tint = if (auth) CyanGlow else Color.Gray,
                                contentDescription = "Auth indicator",
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (auth) "SYSTEM INTERCEPTION HOOK CONNECTED" else "NOTIFICATION ACCESS REQUIRED",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = PureWhite,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (auth) "Poweramp list controls are bound. Play music in your third-party player and client will align!" else "To fetch lists from Poweramp or system active controllers, tap below. Look for \"BlueSync Player\" on the following screen and enable its switch.",
                                fontSize = 11.sp,
                                color = Color.LightGray.copy(alpha = 0.6f),
                                textAlign = TextAlign.Center
                            )
                            if (!auth) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = {
                                        hapticDriver.triggerClick()
                                        android.widget.Toast.makeText(context, "Please find and toggle on 'BlueSync Player' to grant access", android.widget.Toast.LENGTH_LONG).show()
                                        context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = CyanGlow)
                                ) {
                                    Text("Grant Hook Access", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "🔒 Security Note: The standard system alert is shown for all active listeners. BlueSync only intercepts music player details (cannot read/write messages or personal files) and is entirely safe to allow.",
                                    fontSize = 11.sp,
                                    color = Color.LightGray.copy(alpha = 0.5f),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 14.dp)
                                )
                            }
                        }
                    }
                } else {
                    if (displaySongs.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = if (isHostMode) "Reading Android MediaStore...\nIf empty, load music onto storage." else "Bridge active - waiting for track packet...",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(displaySongs) { idx, song ->
                                val activeMatch = if (isHostMode) {
                                    val currentIdx = service?.exoPlayer?.currentMediaItemIndex ?: -1
                                    currentIdx == idx
                                } else {
                                    clientState?.currentIndex == idx
                                }

                                val itemArtModel: Any? = remember(song.albumArtUri) {
                                    val artUri = song.albumArtUri
                                    if (artUri != null && artUri.startsWith("data:image")) {
                                        try {
                                            val clean = artUri.substringAfter("base64,")
                                            val bytes = android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
                                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                        } catch (e: Exception) {
                                            null
                                        }
                                    } else {
                                        artUri
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (activeMatch) Color.White.copy(alpha = 0.08f) else Color.Transparent)
                                        .clickable {
                                            hapticDriver.triggerClick()
                                            viewModel.playSongWithId(song.id, idx)
                                        }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = String.format("%02d", idx + 1),
                                            color = if (activeMatch) (if (isHostMode) CyanGlow else RosePulse) else Color.Gray,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            modifier = Modifier.width(28.dp)
                                        )

                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color.White.copy(alpha = 0.05f))
                                        ) {
                                            if (itemArtModel != null) {
                                                AsyncImage(
                                                    model = itemArtModel,
                                                    contentDescription = "Track cover thumbnail",
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Rounded.MusicNote,
                                                    contentDescription = "Default cover thumbnail",
                                                    tint = Color.Gray.copy(alpha = 0.6f),
                                                    modifier = Modifier.size(18.dp).align(Alignment.Center)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(12.dp))

                                        Column {
                                            Text(
                                                text = song.title,
                                                color = if (activeMatch) PureWhite else Color.LightGray,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = song.artist,
                                                    color = Color.Gray,
                                                    fontSize = 11.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                                if (song.genre.isNotEmpty()) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = "• ${song.genre}",
                                                        color = (if (isHostMode) CyanGlow else RosePulse).copy(alpha = 0.5f),
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Text(
                                        text = formatDuration(song.duration),
                                        color = Color.Gray,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Bluetooth Active Remote Device Scanning Dialog Sheet Overlay
        if (showScanSheet) {
            BluetoothScanOverlay(
                isScanning = isScanning,
                discoveredDevices = discoveredDevices,
                pairedDevices = pairedDevices,
                onRefreshScan = {
                    hapticDriver.triggerClick()
                    viewModel.startBluetoothScan()
                },
                onStopScan = {
                    viewModel.stopBluetoothScan()
                },
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

@Composable
fun HostControlsSelectionCard(
    useNotificationHook: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.02f)),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Broadcast Source",
                    color = PureWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
                Text(
                    text = if (useNotificationHook) "Hooked into Poweramp (Third Party)" else "Native Local Media3 Player",
                    color = Color.LightGray.copy(alpha = 0.5f),
                    fontSize = 10.sp
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Local",
                    fontSize = 10.sp,
                    color = if (!useNotificationHook) CyanGlow else Color.Gray,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onToggle(false) }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )

                Switch(
                    checked = useNotificationHook,
                    onCheckedChange = { onToggle(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = CyanGlow,
                        checkedTrackColor = CyanGlow.copy(alpha = 0.3f),
                        uncheckedThumbColor = Color.LightGray,
                        uncheckedTrackColor = Color.Gray.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.scale(0.8f)
                )

                Text(
                    text = "Intercept",
                    fontSize = 10.sp,
                    color = if (useNotificationHook) CyanGlow else Color.Gray,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onToggle(true) }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothScanOverlay(
    isScanning: Boolean,
    discoveredDevices: List<BluetoothDevice>,
    pairedDevices: List<BluetoothDevice>,
    onRefreshScan: () -> Unit,
    onStopScan: () -> Unit,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MidnightSpaceBg.copy(alpha = 0.95f))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f),
            colors = CardDefaults.cardColors(containerColor = DeepIndigoGlow),
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Modal Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "RFCOMM Host Discovery",
                            color = PureWhite,
                            fontWeight = FontWeight.Black,
                            fontSize = 18.sp
                        )
                        Text(
                            text = "Connect to secondary bridge server",
                            color = Color.LightGray.copy(alpha = 0.6f),
                            fontSize = 11.sp
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close scan layout", tint = PureWhite)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Scanning Activity Area
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                        .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = RosePulse,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Rounded.BluetoothSearching, contentDescription = "Scan Idle icon", tint = Color.Gray)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = if (isScanning) "Searching for active hosts..." else "Discovery is quiet",
                            color = PureWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Button(
                        onClick = { if (isScanning) onStopScan() else onRefreshScan() },
                        colors = ButtonDefaults.buttonColors(containerColor = if (isScanning) Color.DarkGray else RosePulse)
                    ) {
                        Text(
                            text = if (isScanning) "Stop" else "Scan",
                            color = PureWhite,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Scrollable scanned results segments
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text(
                            text = "DISCOVERED DEVICES",
                            color = RosePulse,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    if (discoveredDevices.isEmpty()) {
                        item {
                            Text(
                                text = "No active devices detected. Click Scan above.",
                                color = Color.Gray,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        itemsIndexed(discoveredDevices) { _, device ->
                            DeviceRowItem(device = device, onClick = { onDeviceSelected(device) })
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "PAIRED SYSTEM ACCESSORIES",
                            color = Color.LightGray,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    if (pairedDevices.isEmpty()) {
                        item {
                            Text(
                                text = "No paired devices found.",
                                color = Color.Gray,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        itemsIndexed(pairedDevices) { _, device ->
                            DeviceRowItem(device = device, onClick = { onDeviceSelected(device) })
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun DeviceRowItem(
    device: BluetoothDevice,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Smartphone, contentDescription = "Device icon", tint = Color.LightGray)
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = device.name ?: "Unnamed Device",
                    color = PureWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = device.address,
                    color = Color.LightGray.copy(alpha = 0.4f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Icon(Icons.Rounded.ChevronRight, contentDescription = "Connect arrow", tint = Color.Gray)
    }
}

fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val secondsTotal = ms / 1000
    val minutes = secondsTotal / 60
    val seconds = secondsTotal % 60
    return String.format("%02d:%02d", minutes, seconds)
}

private fun checkNotificationFilterAuth(context: Context): Boolean {
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return flat != null && flat.contains(context.packageName)
}

private fun checkRequiredPermissions(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }
    return true
}

@Composable
fun MaterialWaveSeekBar(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    isPlaying: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableStateOf(0f) }
    val displayValue = if (isDragging) dragProgress else value

    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    val phaseShift by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { offset ->
                        isDragging = true
                        val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                        dragProgress = fraction
                        onValueChange(fraction)
                        tryAwaitRelease()
                        isDragging = false
                        onValueChangeFinished()
                    }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        dragProgress = (offset.x / size.width).coerceIn(0f, 1f)
                        onValueChange(dragProgress)
                    },
                    onDragEnd = {
                        isDragging = false
                        onValueChangeFinished()
                    },
                    onDragCancel = {
                        isDragging = false
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val newProgress = (dragProgress + dragAmount.x / size.width).coerceIn(0f, 1f)
                        dragProgress = newProgress
                        onValueChange(newProgress)
                    }
                )
            }
    ) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val centerY = height / 2f
        val activeWidth = displayValue * width

        Canvas(modifier = Modifier.fillMaxSize()) {
            // Draw Inactive Track: Simple clean line
            drawLine(
                color = inactiveColor,
                start = Offset(activeWidth, centerY),
                end = Offset(width, centerY),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )

            // Draw Active Track: Wavy Path
            if (activeWidth > 0f) {
                val wavePath = Path()
                wavePath.moveTo(0f, centerY)

                val amplitude = if (isPlaying) 3.5.dp.toPx() else 1.2.dp.toPx()
                val wavelength = 24.dp.toPx()

                var x = 0f
                val step = 2.dp.toPx()
                while (x < activeWidth) {
                    val phase = x / wavelength * (2 * Math.PI.toFloat()) - phaseShift
                    val y = centerY + amplitude * kotlin.math.sin(phase)
                    wavePath.lineTo(x, y)
                    x += step
                }
                wavePath.lineTo(activeWidth, centerY) // snap to center for thumb attachment

                drawPath(
                    path = wavePath,
                    color = activeColor,
                    style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }

            // Draw Thumb
            drawCircle(
                color = activeColor,
                radius = 6.dp.toPx(),
                center = Offset(activeWidth, centerY)
            )
        }
    }
}
