package com.example.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.automirrored.rounded.VolumeMute
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlin.math.roundToLong

/**
 * The main now-playing surface: art, title/artist, seek bar, transport controls, volume.
 * Shared by both the portrait and landscape layouts via [compact], which trims sizes and drops
 * secondary text (album line, genre caption) when vertical space is tight.
 */
@Composable
fun NowPlayingCard(
    state: PlayerUiState,
    actions: PlayerActions,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(GlassSurface)
            .verticalScroll(rememberScrollState())
            .padding(if (compact) 14.dp else 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp)
    ) {
        AlbumArt(state = state, compact = compact)
        TrackMetadata(state = state, compact = compact)
        SeekBarSection(state = state, actions = actions)
        TransportControlsRow(state = state, actions = actions, compact = compact)
        VolumeRow(state = state, actions = actions, compact = compact)
    }
}

@Composable
private fun AlbumArt(state: PlayerUiState, compact: Boolean) {
    val size = if (compact) 84.dp else 132.dp
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(if (compact) 16.dp else 20.dp))
            .background(Color.White.copy(alpha = 0.05f)),
        contentAlignment = Alignment.Center
    ) {
        if (state.trackArtUri != null) {
            AsyncImage(
                model = state.trackArtUri,
                contentDescription = "Album art",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = if (state.hostUseNotificationHook) Icons.Rounded.WifiTethering else Icons.Rounded.Headphones,
                    contentDescription = null,
                    tint = state.accentColor,
                    modifier = Modifier.size(if (compact) 26.dp else 36.dp)
                )
                if (!compact && state.trackGenre.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(state.trackGenre, fontSize = 10.sp, color = Color.LightGray.copy(alpha = 0.4f))
                }
            }
        }
    }
}

@Composable
private fun TrackMetadata(state: PlayerUiState, compact: Boolean) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = state.trackTitle,
            fontSize = if (compact) 16.sp else 20.sp,
            fontWeight = FontWeight.Bold,
            color = PureWhite,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.basicMarquee().padding(horizontal = 8.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = state.trackArtist,
            fontSize = if (compact) 12.sp else 13.sp,
            color = Color.LightGray.copy(alpha = 0.65f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!compact && state.trackAlbum.isNotEmpty()) {
            Text(
                text = state.trackAlbum,
                fontSize = 11.sp,
                color = Color.LightGray.copy(alpha = 0.4f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SeekBarSection(state: PlayerUiState, actions: PlayerActions) {
    val totalDurationSec = state.totalDuration / 1000f
    val currentPositionSec = state.currentPosition / 1000f
    val rawSliderVal = if (totalDurationSec > 0f) currentPositionSec / totalDurationSec else 0f
    var localDragFraction by remember { mutableStateOf<Float?>(null) }
    val activeSliderValue = localDragFraction ?: rawSliderVal

    Column(modifier = Modifier.fillMaxWidth()) {
        MaterialWaveSeekBar(
            value = activeSliderValue.coerceIn(0f, 1f),
            onValueChange = { fraction ->
                localDragFraction = fraction
                actions.onSeekPreview()
            },
            onValueChangeFinished = {
                localDragFraction?.let { fraction ->
                    actions.onSeek((fraction * state.totalDuration).roundToLong())
                }
                localDragFraction = null
            },
            isPlaying = state.isPlaying,
            activeColor = state.accentColor,
            inactiveColor = Color.White.copy(alpha = 0.1f),
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(state.currentPosition), fontSize = 11.sp, color = Color.LightGray.copy(alpha = 0.55f))
            Text(formatDuration(state.totalDuration), fontSize = 11.sp, color = Color.LightGray.copy(alpha = 0.55f))
        }
    }
}

@Composable
private fun TransportControlsRow(state: PlayerUiState, actions: PlayerActions, compact: Boolean) {
    val sideButtonSize = if (compact) 34.dp else 40.dp
    val sideIconSize = if (compact) 17.dp else 20.dp
    val skipButtonSize = if (compact) 40.dp else 48.dp
    val skipIconSize = if (compact) 22.dp else 28.dp
    val fabSize = if (compact) 46.dp else 56.dp
    val fabIconSize = if (compact) 22.dp else 28.dp

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = actions.onToggleShuffle, modifier = Modifier.size(sideButtonSize)) {
            Icon(
                Icons.Rounded.Shuffle,
                contentDescription = "Shuffle",
                tint = if (state.isShuffleActive) state.accentColor else Color.Gray,
                modifier = Modifier.size(sideIconSize)
            )
        }
        IconButton(onClick = actions.onPrevious, modifier = Modifier.size(skipButtonSize)) {
            Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", tint = PureWhite, modifier = Modifier.size(skipIconSize))
        }
        FloatingActionButton(
            onClick = actions.onPlayPause,
            containerColor = state.accentColor,
            shape = CircleShape,
            modifier = Modifier.size(fabSize)
        ) {
            Icon(
                imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = "Play or pause",
                tint = if (state.isHostMode) Color.Black else PureWhite,
                modifier = Modifier.size(fabIconSize)
            )
        }
        IconButton(onClick = actions.onNext, modifier = Modifier.size(skipButtonSize)) {
            Icon(Icons.Rounded.SkipNext, contentDescription = "Next", tint = PureWhite, modifier = Modifier.size(skipIconSize))
        }
        IconButton(onClick = actions.onToggleRepeat, modifier = Modifier.size(sideButtonSize)) {
            Icon(
                imageVector = if (state.repeatState == "ONE") Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                contentDescription = "Repeat",
                tint = if (state.repeatState != "OFF") state.accentColor else Color.Gray,
                modifier = Modifier.size(sideIconSize)
            )
        }
    }
}

@Composable
private fun VolumeRow(state: PlayerUiState, actions: PlayerActions, compact: Boolean) {
    val iconSize = if (compact) 16.dp else 20.dp
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (state.currentVolume == 0) Icons.AutoMirrored.Rounded.VolumeMute else Icons.AutoMirrored.Rounded.VolumeUp,
            contentDescription = "Volume",
            tint = state.accentColor,
            modifier = Modifier.size(iconSize)
        )
        Slider(
            value = state.currentVolume.toFloat(),
            valueRange = 0f..state.maxVolume.toFloat(),
            onValueChange = { actions.onVolumeChange(it.toInt()) },
            colors = SliderDefaults.colors(
                thumbColor = state.accentColor,
                activeTrackColor = state.accentColor,
                inactiveTrackColor = Color.White.copy(alpha = 0.1f)
            ),
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
        )
    }
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
        animationSpec = infiniteRepeatable(animation = tween(1200, easing = LinearEasing), repeatMode = RepeatMode.Restart),
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
                    onDragCancel = { isDragging = false },
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
            drawLine(
                color = inactiveColor,
                start = Offset(activeWidth, centerY),
                end = Offset(width, centerY),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )

            if (activeWidth > 0f) {
                val wavePath = Path()
                wavePath.moveTo(0f, centerY)

                val amplitude = if (isPlaying) 2.5.dp.toPx() else 1.dp.toPx()
                val wavelength = 24.dp.toPx()

                var x = 0f
                val step = 2.dp.toPx()
                while (x < activeWidth) {
                    val phase = x / wavelength * (2 * Math.PI.toFloat()) - phaseShift
                    val y = centerY + amplitude * kotlin.math.sin(phase)
                    wavePath.lineTo(x, y)
                    x += step
                }
                wavePath.lineTo(activeWidth, centerY)

                drawPath(
                    path = wavePath,
                    color = activeColor,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }

            drawCircle(color = activeColor, radius = 6.dp.toPx(), center = Offset(activeWidth, centerY))
        }
    }
}
