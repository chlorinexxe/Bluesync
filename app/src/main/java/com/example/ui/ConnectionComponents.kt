package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import com.example.PlaybackService
import com.example.bluetooth.BluetoothConnectionState
import com.example.bluetooth.SpeakerSyncEngine

@Composable
fun AppHeader(
    isHostMode: Boolean,
    onSelectHost: () -> Unit,
    onSelectClient: () -> Unit,
    bluetoothState: BluetoothConnectionState,
    hostUseNotificationHook: Boolean,
    connectedSpeakerCount: Int,
    speakerClientState: SpeakerSyncEngine.SpeakerClientState,
    speakerSyncStatus: PlaybackService.SpeakerSyncStatus,
    onJoinSpeaker: () -> Unit,
    onLeaveSpeaker: () -> Unit,
    onForceSyncSpeaker: () -> Unit,
    onKillSwitch: () -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "BlueSync",
                fontSize = if (compact) 16.sp else 20.sp,
                fontWeight = FontWeight.Bold,
                color = PureWhite
            )
            Text(
                text = if (isHostMode) "Host" else "Remote",
                fontSize = if (compact) 10.sp else 12.sp,
                color = Color.LightGray.copy(alpha = 0.55f)
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(GlassSurface)
                    .padding(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ModeChip(text = "Host", selected = isHostMode, color = CyanGlow, compact = compact, onClick = onSelectHost)
                ModeChip(text = "Remote", selected = !isHostMode, color = RosePulse, compact = compact, onClick = onSelectClient)
            }

            val speakerAvailable = bluetoothState == BluetoothConnectionState.CONNECTED && !(isHostMode && hostUseNotificationHook)
            if (speakerAvailable) {
                Spacer(modifier = Modifier.width(6.dp))
                SpeakerHeaderButton(
                    isHostMode = isHostMode,
                    connectedSpeakerCount = connectedSpeakerCount,
                    speakerClientState = speakerClientState,
                    compact = compact,
                    onJoin = onJoinSpeaker,
                    onLeave = onLeaveSpeaker
                )
            }

            if (!isHostMode && speakerClientState is SpeakerSyncEngine.SpeakerClientState.Ready) {
                Spacer(modifier = Modifier.width(6.dp))
                SpeakerSyncStatusButton(status = speakerSyncStatus, compact = compact, onClick = onForceSyncSpeaker)
            }

            if (bluetoothState != BluetoothConnectionState.DISCONNECTED) {
                Spacer(modifier = Modifier.width(6.dp))
                KillSwitchButton(compact = compact, onClick = onKillSwitch)
            }
        }
    }
}

/** One-tap panic button: stops the connection, speaker mode (either side), and playback in one
 * shot. Only shown once there's actually something active to stop. */
@Composable
private fun KillSwitchButton(compact: Boolean, onClick: () -> Unit) {
    val size = if (compact) 30.dp else 34.dp
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(DangerRed.copy(alpha = 0.15f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Rounded.PowerSettingsNew,
            contentDescription = "Stop everything",
            tint = DangerRed,
            modifier = Modifier.width(16.dp)
        )
    }
}

@Composable
private fun ModeChip(text: String, selected: Boolean, color: Color, compact: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        color = if (selected) color else Color.Gray,
        fontWeight = FontWeight.SemiBold,
        fontSize = if (compact) 10.sp else 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color.White.copy(alpha = 0.06f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

@Composable
fun ConnectionStatusCard(
    bluetoothState: BluetoothConnectionState,
    connectedDevice: String?,
    isHostMode: Boolean,
    onStartHostServer: () -> Unit,
    onDisconnect: () -> Unit,
    onFindDevices: () -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(GlassSurface)
            .padding(if (compact) 10.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            val (icon, tint, message) = when (bluetoothState) {
                BluetoothConnectionState.DISCONNECTED -> Triple(Icons.Rounded.BluetoothDisabled, Color.Gray, "Not connected")
                BluetoothConnectionState.CONNECTING -> Triple(Icons.AutoMirrored.Rounded.BluetoothSearching, CyanGlow, "Connecting…")
                BluetoothConnectionState.CONNECTED -> Triple(Icons.Rounded.BluetoothConnected, CyanGlow, "Connected")
                BluetoothConnectionState.LISTENING -> Triple(Icons.Rounded.CellTower, CyanGlow, "Waiting for remote…")
            }
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.width(if (compact) 16.dp else 20.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(message, color = PureWhite, fontWeight = FontWeight.SemiBold, fontSize = if (compact) 11.sp else 13.sp)
                if (bluetoothState == BluetoothConnectionState.CONNECTED && connectedDevice != null) {
                    Text(
                        text = connectedDevice,
                        color = Color.LightGray.copy(alpha = 0.5f),
                        fontSize = if (compact) 9.sp else 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        val buttonPadding = if (compact) PaddingValues(horizontal = 10.dp, vertical = 2.dp) else ButtonDefaults.ContentPadding
        val buttonHeight = if (compact) Modifier.height(30.dp) else Modifier

        if (isHostMode) {
            if (bluetoothState == BluetoothConnectionState.DISCONNECTED) {
                Button(
                    onClick = onStartHostServer,
                    colors = ButtonDefaults.buttonColors(containerColor = CyanGlow),
                    contentPadding = buttonPadding,
                    modifier = buttonHeight.testTag("open_host_button")
                ) { Text("Start hosting", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
            } else {
                OutlinedButton(
                    onClick = onDisconnect,
                    contentPadding = buttonPadding,
                    modifier = buttonHeight.testTag("disconnect_host_button")
                ) { Text("Stop", color = PureWhite, fontSize = 11.sp) }
            }
        } else {
            if (bluetoothState == BluetoothConnectionState.DISCONNECTED) {
                Button(
                    onClick = onFindDevices,
                    colors = ButtonDefaults.buttonColors(containerColor = RosePulse),
                    contentPadding = buttonPadding,
                    modifier = buttonHeight
                ) { Text("Find a host", color = PureWhite, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
            } else {
                OutlinedButton(
                    onClick = onDisconnect,
                    contentPadding = buttonPadding,
                    modifier = buttonHeight.testTag("disconnect_client_button")
                ) { Text("Disconnect", color = PureWhite, fontSize = 11.sp) }
            }
        }
    }
}

@Composable
fun SourceToggleRow(
    useNotificationHook: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Music source", color = PureWhite, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text(
                text = if (useNotificationHook) "Following your music app" else "BlueSync's own library",
                color = Color.LightGray.copy(alpha = 0.5f),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(
            checked = useNotificationHook,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = CyanGlow,
                checkedTrackColor = CyanGlow.copy(alpha = 0.3f),
                uncheckedThumbColor = Color.LightGray,
                uncheckedTrackColor = Color.Gray.copy(alpha = 0.3f)
            ),
            modifier = Modifier.scale(0.85f)
        )
    }
}

/**
 * Compact speaker-mode toggle shown right next to the Host/Remote pill in [AppHeader], so it's
 * always visible once connected instead of scrolled away in a separate card. On the host it's a
 * read-only badge of how many phones are listening in sync; on a connected remote, tapping it
 * joins/leaves. Speaker mode only has anything to stream when the host plays its own local
 * library (a hooked third-party app's audio can't be captured), so the caller hides this button
 * entirely in that case rather than showing a button that silently does nothing.
 */
@Composable
private fun SpeakerHeaderButton(
    isHostMode: Boolean,
    connectedSpeakerCount: Int,
    speakerClientState: SpeakerSyncEngine.SpeakerClientState,
    compact: Boolean,
    onJoin: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val active = if (isHostMode) connectedSpeakerCount > 0 else speakerClientState is SpeakerSyncEngine.SpeakerClientState.Ready
    val isConnecting = !isHostMode && speakerClientState is SpeakerSyncEngine.SpeakerClientState.Connecting
    val tint = if (active) CyanGlow else Color.LightGray.copy(alpha = 0.6f)
    val size = if (compact) 30.dp else 34.dp

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(GlassSurface)
            .then(
                if (isHostMode) Modifier
                else Modifier.clickable {
                    when (speakerClientState) {
                        is SpeakerSyncEngine.SpeakerClientState.Disconnected,
                        is SpeakerSyncEngine.SpeakerClientState.Failed -> onJoin()
                        is SpeakerSyncEngine.SpeakerClientState.Ready -> onLeave()
                        is SpeakerSyncEngine.SpeakerClientState.Connecting -> Unit
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isConnecting) {
            CircularProgressIndicator(modifier = Modifier.width(14.dp).height(14.dp), color = RosePulse, strokeWidth = 2.dp)
        } else {
            Icon(
                Icons.AutoMirrored.Rounded.VolumeUp,
                contentDescription = if (isHostMode) "Speaker mode" else if (active) "Leave speaker mode" else "Join speaker mode",
                tint = tint,
                modifier = Modifier.width(16.dp)
            )
        }
        if (isHostMode && connectedSpeakerCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(CyanGlow),
                contentAlignment = Alignment.Center
            ) {
                Text(connectedSpeakerCount.toString(), fontSize = 8.sp, color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Speaker sync is otherwise an invisible background process - this makes it visible (green =
 * synced, amber = actively correcting drift, gray = waiting for the current track to finish
 * loading before it's safe to check) and gives a manual "force it now" escape hatch for anyone
 * who doesn't want to wait for or trust the automatic correction. Only shown once actually
 * playing as a speaker - there's nothing to sync before then.
 */
@Composable
private fun SpeakerSyncStatusButton(status: PlaybackService.SpeakerSyncStatus, compact: Boolean, onClick: () -> Unit) {
    val size = if (compact) 30.dp else 34.dp
    val tint = when (status) {
        is PlaybackService.SpeakerSyncStatus.Synced -> CyanGlow
        is PlaybackService.SpeakerSyncStatus.Correcting -> Color(0xFFFFB74D)
        is PlaybackService.SpeakerSyncStatus.WaitingForTrack -> Color.LightGray.copy(alpha = 0.5f)
        is PlaybackService.SpeakerSyncStatus.Idle -> Color.LightGray.copy(alpha = 0.5f)
    }
    val description = when (status) {
        is PlaybackService.SpeakerSyncStatus.Synced -> "In sync - tap to force re-sync"
        is PlaybackService.SpeakerSyncStatus.Correcting -> "Correcting drift (${status.driftMs}ms) - tap to force re-sync"
        else -> "Waiting to sync - tap to force re-sync"
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(GlassSurface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Rounded.Sync,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.width(16.dp)
        )
    }
}
