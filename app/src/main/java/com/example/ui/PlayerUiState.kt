package com.example.ui

import androidx.compose.ui.graphics.Color
import com.example.bluetooth.BluetoothConnectionState
import com.example.model.Song

/**
 * Everything the portrait and landscape layouts need to render the current screen, computed
 * once in [PremiumPlayerUI] regardless of which layout ends up drawing it. Bundling this avoids
 * threading ~20 individual parameters through both layouts (and every shared component).
 */
data class PlayerUiState(
    val isHostMode: Boolean,
    val hostUseNotificationHook: Boolean,
    val bluetoothState: BluetoothConnectionState,
    val connectedDevice: String?,
    val accentColor: Color,
    val trackTitle: String,
    val trackArtist: String,
    val trackAlbum: String,
    val trackGenre: String,
    val trackArtUri: Any?,
    val isPlaying: Boolean,
    val totalDuration: Long,
    val isShuffleActive: Boolean,
    val repeatState: String,
    val currentVolume: Int,
    val maxVolume: Int,
    val displaySongs: List<Song>,
    val activeQueueIndex: Int,
    val hookAuthorized: Boolean
)

/** User-triggered actions, pre-wired with haptics/viewmodel calls by [PremiumPlayerUI]. */
data class PlayerActions(
    val onSelectHostMode: () -> Unit,
    val onSelectClientMode: () -> Unit,
    val onToggleSource: (Boolean) -> Unit,
    val onStartHostServer: () -> Unit,
    val onDisconnect: () -> Unit,
    val onFindDevices: () -> Unit,
    val onPlayPause: () -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onToggleShuffle: () -> Unit,
    val onToggleRepeat: () -> Unit,
    val onSeekPreview: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onVolumeChange: (Int) -> Unit,
    val onGrantHookAccess: () -> Unit,
    val onSelectSong: (Song, Int) -> Unit,
    val onLoadMoreSongs: () -> Unit
)
