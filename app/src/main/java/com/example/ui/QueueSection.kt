package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.model.Song

@Composable
fun QueueSection(
    state: PlayerUiState,
    actions: PlayerActions,
    compact: Boolean,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState()
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Up next",
            fontSize = if (compact) 11.sp else 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.LightGray.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = if (compact) 4.dp else 18.dp, vertical = 6.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxSize()
                .clip(RoundedCornerShape(if (compact) 12.dp else 20.dp))
                .background(Color.White.copy(alpha = 0.03f))
        ) {
            val needsHookAccess = state.isHostMode && state.hostUseNotificationHook &&
                (!state.hookAuthorized || state.displaySongs.isEmpty())

            when {
                needsHookAccess -> HookAccessPrompt(authorized = state.hookAuthorized, compact = compact, onGrantAccess = actions.onGrantHookAccess)
                state.displaySongs.isEmpty() -> EmptyQueueMessage(isHostMode = state.isHostMode, compact = compact)
                else -> QueueList(state = state, actions = actions, compact = compact, listState = listState)
            }
        }
    }
}

@Composable
private fun HookAccessPrompt(authorized: Boolean, compact: Boolean, onGrantAccess: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            if (!compact) {
                Icon(
                    imageVector = if (authorized) Icons.Rounded.MusicNote else Icons.Rounded.LockOpen,
                    tint = if (authorized) CyanGlow else Color.Gray,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
            Text(
                text = if (authorized) "Waiting for a song" else "Notification access needed",
                fontSize = if (compact) 11.sp else 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = PureWhite,
                textAlign = TextAlign.Center
            )
            if (!compact) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (authorized) {
                        "Play something in your music app and BlueSync will follow along."
                    } else {
                        "Grant access so BlueSync can see what's playing and control any music app."
                    },
                    fontSize = 11.sp,
                    color = Color.LightGray.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center
                )
            }
            if (!authorized) {
                Spacer(modifier = Modifier.height(if (compact) 8.dp else 16.dp))
                Button(
                    onClick = onGrantAccess,
                    colors = ButtonDefaults.buttonColors(containerColor = CyanGlow),
                    contentPadding = if (compact) PaddingValues(horizontal = 12.dp, vertical = 4.dp) else ButtonDefaults.ContentPadding
                ) {
                    Text("Grant access", color = Color.Black, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                }
                if (!compact) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "BlueSync only reads playback info - never your messages or files.",
                        fontSize = 10.sp,
                        color = Color.LightGray.copy(alpha = 0.4f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyQueueMessage(isHostMode: Boolean, compact: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = if (isHostMode) "No songs found on this device" else "Waiting to connect…",
            color = Color.Gray,
            fontSize = if (compact) 10.sp else 12.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun QueueList(state: PlayerUiState, actions: PlayerActions, compact: Boolean, listState: LazyListState) {
    // Only the remote side has anything to page in - the host already holds its whole library
    // in memory locally, nothing to fetch over the wire for itself.
    if (!state.isHostMode) {
        // Guards against re-firing for a size we already requested - belt-and-suspenders on
        // top of keeping paginated songs in a separate accumulator (see PlaybackService).
        // Without this, a host that's slow to respond (or that never grows the list for some
        // other reason) would have this re-fire on every recomposition at the same size,
        // which is exactly the runaway request loop this was built to prevent.
        var lastRequestedSize by remember { mutableStateOf(-1) }
        LaunchedEffect(listState, state.displaySongs.size) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                .collect { lastVisibleIndex ->
                    val size = state.displaySongs.size
                    if (lastVisibleIndex != null && lastVisibleIndex >= size - 5 && size != lastRequestedSize) {
                        lastRequestedSize = size
                        actions.onLoadMoreSongs()
                    }
                }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(if (compact) 6.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)
    ) {
        itemsIndexed(state.displaySongs, key = { idx, song -> "${song.id}_$idx" }) { idx, song ->
            QueueRow(song = song, index = idx, isActive = idx == state.activeQueueIndex, accentColor = state.accentColor, compact = compact) {
                actions.onSelectSong(song, idx)
            }
        }
    }
}

@Composable
private fun QueueRow(song: Song, index: Int, isActive: Boolean, accentColor: Color, compact: Boolean, onClick: () -> Unit) {
    val artModel: Any? = remember(song.albumArtUri) {
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
            .clip(RoundedCornerShape(if (compact) 8.dp else 12.dp))
            .background(if (isActive) Color.White.copy(alpha = 0.07f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 8.dp else 12.dp, vertical = if (compact) 6.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = String.format("%02d", index + 1),
                color = if (isActive) accentColor else Color.Gray,
                fontWeight = FontWeight.SemiBold,
                fontSize = if (compact) 10.sp else 11.sp,
                modifier = Modifier.width(if (compact) 20.dp else 26.dp)
            )

            Box(
                modifier = Modifier
                    .size(if (compact) 26.dp else 38.dp)
                    .clip(RoundedCornerShape(if (compact) 6.dp else 8.dp))
                    .background(Color.White.copy(alpha = 0.05f))
            ) {
                if (artModel != null) {
                    AsyncImage(model = artModel, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = Color.Gray.copy(alpha = 0.6f),
                        modifier = Modifier.size(if (compact) 12.dp else 16.dp).align(Alignment.Center)
                    )
                }
            }

            Spacer(modifier = Modifier.width(if (compact) 8.dp else 12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    color = if (isActive) PureWhite else Color.LightGray,
                    fontWeight = FontWeight.Medium,
                    fontSize = if (compact) 11.sp else 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.artist,
                    color = Color.Gray,
                    fontSize = if (compact) 9.sp else 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(text = formatDuration(song.duration), color = Color.Gray, fontSize = if (compact) 9.sp else 11.sp)
    }
}
