package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Tall-screen orientation: header, connection status, and a scrollable now-playing card
 * followed by the queue, stacked vertically. Scrolling the queue collapses the now-playing
 * card into a compact [MiniPlayerBar] so browsing songs isn't fighting it for space. */
@Composable
fun PortraitPlayerLayout(
    state: PlayerUiState,
    actions: PlayerActions,
    compact: Boolean,
    positionFlow: StateFlow<Long>,
    modifier: Modifier = Modifier
) {
    val queueListState = rememberLazyListState()
    val isCollapsed by remember {
        derivedStateOf {
            queueListState.firstVisibleItemIndex > 0 || queueListState.firstVisibleItemScrollOffset > 80
        }
    }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        AppHeader(
            isHostMode = state.isHostMode,
            onSelectHost = actions.onSelectHostMode,
            onSelectClient = actions.onSelectClientMode,
            bluetoothState = state.bluetoothState,
            hostUseNotificationHook = state.hostUseNotificationHook,
            connectedSpeakerCount = state.connectedSpeakerCount,
            speakerClientState = state.speakerClientState,
            onJoinSpeaker = actions.onJoinSpeakerMode,
            onLeaveSpeaker = actions.onLeaveSpeakerMode,
            onKillSwitch = actions.onKillSwitch,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
        )

        ConnectionStatusCard(
            bluetoothState = state.bluetoothState,
            connectedDevice = state.connectedDevice,
            isHostMode = state.isHostMode,
            onStartHostServer = actions.onStartHostServer,
            onDisconnect = actions.onDisconnect,
            onFindDevices = actions.onFindDevices,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        AnimatedVisibility(visible = state.isHostMode && !isCollapsed) {
            SourceToggleRow(
                useNotificationHook = state.hostUseNotificationHook,
                onToggle = actions.onToggleSource,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        Crossfade(targetState = isCollapsed, label = "nowPlayingCollapse") { collapsed ->
            if (collapsed) {
                MiniPlayerBar(
                    state = state,
                    actions = actions,
                    onExpand = { coroutineScope.launch { queueListState.animateScrollToItem(0) } },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            } else {
                // Fixed natural height, never weighted/clipped - the play/pause button and
                // the rest of the transport controls must always render in full, never get
                // scrolled out of view or clipped by a height budget that doesn't fit.
                NowPlayingCard(
                    state = state,
                    actions = actions,
                    compact = compact,
                    positionFlow = positionFlow,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        QueueSection(
            state = state,
            actions = actions,
            compact = false,
            listState = queueListState,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }
}

/** Wide-screen orientation: now-playing on the left, header/status/queue stacked on the right. */
@Composable
fun LandscapePlayerLayout(
    state: PlayerUiState,
    actions: PlayerActions,
    positionFlow: StateFlow<Long>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            NowPlayingCard(state = state, actions = actions, compact = true, positionFlow = positionFlow, modifier = Modifier.fillMaxSize())
        }

        Column(
            modifier = Modifier.weight(1.1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AppHeader(
                isHostMode = state.isHostMode,
                onSelectHost = actions.onSelectHostMode,
                onSelectClient = actions.onSelectClientMode,
                bluetoothState = state.bluetoothState,
                hostUseNotificationHook = state.hostUseNotificationHook,
                connectedSpeakerCount = state.connectedSpeakerCount,
                speakerClientState = state.speakerClientState,
                onJoinSpeaker = actions.onJoinSpeakerMode,
                onLeaveSpeaker = actions.onLeaveSpeakerMode,
                onKillSwitch = actions.onKillSwitch,
                compact = true
            )

            ConnectionStatusCard(
                bluetoothState = state.bluetoothState,
                connectedDevice = state.connectedDevice,
                isHostMode = state.isHostMode,
                onStartHostServer = actions.onStartHostServer,
                onDisconnect = actions.onDisconnect,
                onFindDevices = actions.onFindDevices,
                compact = true
            )

            if (state.isHostMode) {
                SourceToggleRow(useNotificationHook = state.hostUseNotificationHook, onToggle = actions.onToggleSource)
            }

            QueueSection(
                state = state,
                actions = actions,
                compact = true,
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        }
    }
}
