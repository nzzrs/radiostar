/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.recommend.DjSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.cos
import kotlin.math.sin

/**
 * Full-screen speaking view. It is shown only when the song/player sheet is
 * open, so speech never steals focus from settings or search. Drag down or
 * press back to dismiss the overlay for the current utterance.
 */
@Composable
fun DjSpeakingOverlay(visible: Boolean) {
    val state by DjSessionState.state.collectAsStateWithLifecycle()
    if (!visible || !state.speaking) return

    val playerConnection = LocalPlayerConnection.current
    val metadata by (playerConnection?.mediaMetadata ?: remember { MutableStateFlow<MediaMetadata?>(null) })
        .collectAsStateWithLifecycle()

    var dragOffset by remember(state.persona) { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val offset by animateFloatAsState(
        targetValue = if (dragging) dragOffset else 0f,
        animationSpec =
            if (dragging) {
                snap()
            } else {
                spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
            },
        label = "dj-dismiss-offset",
    )
    BackHandler { DjSessionState.dismissSpeaking() }

    val colors = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "dj-voice")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(tween(1_600, easing = LinearEasing)),
        label = "voice-phase",
    )
    val breathe by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                tween(3_200, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)),
                RepeatMode.Reverse,
            ),
        label = "voice-breathe",
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(18_000, easing = LinearEasing)),
        label = "voice-spin",
    )
    val wave by transition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(tween(1_300, easing = LinearEasing)),
        label = "voice-bars",
    )

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = offset }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { dragging = true },
                        onVerticalDrag = { _, amount ->
                            dragOffset = (dragOffset + amount).coerceAtLeast(0f)
                        },
                        onDragEnd = {
                            if (dragOffset > 140.dp.toPx()) {
                                DjSessionState.dismissSpeaking()
                            }
                            dragging = false
                            dragOffset = 0f
                        },
                        onDragCancel = {
                            dragging = false
                            dragOffset = 0f
                        },
                    )
                }
                .background(
                    Brush.verticalGradient(
                        listOf(
                            colors.surfaceContainerLowest,
                            colors.surface,
                            colors.surfaceContainerLow,
                        ),
                    ),
                ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(colors.primaryContainer.copy(alpha = 0.42f), Color.Transparent),
                            radius = 1_200f,
                        ),
                    ),
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(colors.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = state.persona.emoji,
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.persona.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.radiostar_dj_speaking),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                SpeakingBars(
                    color = colors.primary,
                    phase = wave,
                )
            }

            BoxWithConstraints(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                val side = minOf(maxWidth, maxHeight).coerceAtMost(420.dp)
                VoiceReactor(
                    phase = phase,
                    breathe = breathe,
                    spin = spin,
                    primary = colors.primary,
                    tertiary = colors.tertiary,
                    modifier = Modifier.size(side),
                )
            }

            metadata?.let { song ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.extraLarge)
                            .background(colors.surfaceContainerHigh)
                            .padding(12.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(44.dp)
                                .clip(MaterialTheme.shapes.small)
                                .background(colors.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = state.persona.emoji,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (song.thumbnailUrl != null) {
                            AsyncImage(
                                model = song.thumbnailUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val artist = song.artists.joinToString { it.name }
                        if (artist.isNotBlank()) {
                            Text(
                                text = artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.radiostar_dj_swipe_close),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }

        Box(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(colors.onSurfaceVariant.copy(alpha = 0.22f)),
        )
    }
}

@Composable
private fun VoiceReactor(
    phase: Float,
    breathe: Float,
    spin: Float,
    primary: Color,
    tertiary: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val centre = center
        val span = size.minDimension
        val core = span * (0.155f + breathe * 0.012f)
        val ring = span * (0.29f + breathe * 0.012f)

        drawCircle(
            brush =
                Brush.radialGradient(
                    colors = listOf(primary.copy(alpha = 0.26f), Color.Transparent),
                    center = centre,
                    radius = core * 3.1f,
                ),
            radius = core * 3.1f,
            center = centre,
        )

        val bars = 56
        val pulse = (phase / (Math.PI * 2)).toFloat()
        drawCircle(
            color = primary.copy(alpha = (1f - pulse) * 0.22f),
            radius = span * (0.2f + pulse * 0.28f),
            center = centre,
            style = Stroke(width = 1.5.dp.toPx()),
        )
        repeat(bars) { index ->
            val angle = index * (Math.PI * 2 / bars).toFloat()
            val energy = ((sin(phase + index * 0.68f) + 1f) / 2f)
            val start = ring
            val end = ring + span * (0.022f + energy * 0.075f)
            val direction = Offset(cos(angle), sin(angle))
            drawLine(
                color = primary.copy(alpha = 0.18f + energy * 0.58f),
                start = centre + direction * start,
                end = centre + direction * end,
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        drawCircle(
            color = primary.copy(alpha = 0.32f),
            radius = ring,
            center = centre,
            style = Stroke(width = 1.dp.toPx()),
        )

        rotate(degrees = spin, pivot = centre) {
            drawArc(
                color = tertiary.copy(alpha = 0.75f),
                startAngle = 0f,
                sweepAngle = 74f,
                useCenter = false,
                topLeft = centre - Offset(ring * 1.22f, ring * 1.22f),
                size = Size(ring * 2.44f, ring * 2.44f),
                style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
            )
            drawArc(
                color = primary.copy(alpha = 0.5f),
                startAngle = 190f,
                sweepAngle = 96f,
                useCenter = false,
                topLeft = centre - Offset(ring * 1.4f, ring * 1.4f),
                size = Size(ring * 2.8f, ring * 2.8f),
                style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        drawCircle(
            brush =
                Brush.radialGradient(
                    colors = listOf(primary, primary.copy(alpha = 0.62f), Color.Transparent),
                    center = centre,
                    radius = core * 1.7f,
                ),
            radius = core * 1.7f,
            center = centre,
        )
        drawCircle(
            color = primary.copy(alpha = 0.9f),
            radius = core * 0.52f,
            center = centre,
        )
    }
}

@Composable
private fun SpeakingBars(
    color: Color,
    phase: Float,
) {
    Canvas(
        modifier =
            Modifier
                .width(40.dp)
                .height(24.dp),
    ) {
        val bars = 5
        val gap = size.width / (bars * 2 - 1)
        repeat(bars) { index ->
            val wave = kotlin.math.abs(sin(phase + index * 0.8f))
            val barHeight = size.height * (0.24f + wave * 0.76f)
            drawRoundRect(
                color = color.copy(alpha = 0.5f + wave * 0.5f),
                topLeft = Offset(index * 2 * gap, (size.height - barHeight) / 2f),
                size = Size(gap, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(gap / 2f),
            )
        }
    }
}
