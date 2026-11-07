/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.recommend.DjSessionState

/**
 * Full-screen speaking view. It is shown only when the song/player sheet is
 * open, so speech never steals focus from settings or search. Drag down or
 * press back to dismiss the overlay for the current utterance.
 */
@Composable
fun DjSpeakingOverlay(visible: Boolean) {
    val state by DjSessionState.state.collectAsStateWithLifecycle()
    if (!visible || !state.speaking) return

    var dragOffset by remember(state.persona) { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val offset by animateFloatAsState(
        targetValue = if (dragging) dragOffset else 0f,
        animationSpec = tween(220),
        label = "dj-dismiss-offset",
    )
    BackHandler { DjSessionState.dismissSpeaking() }

    val transition = rememberInfiniteTransition(label = "dj-sphere")
    val pulse by transition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val drift by transition.animateFloat(
        initialValue = -12f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift",
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
                        listOf(Color(0xFF120A18), Color(0xFF25102A), Color(0xFF0B0A12)),
                    ),
                ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Canvas(modifier = Modifier.size(260.dp)) {
                val centre = Offset(size.width / 2 + drift, size.height / 2)
                val base = size.minDimension * 0.28f * pulse
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color(0xFFFF6B8A).copy(alpha = 0.75f), Color.Transparent),
                        center = centre,
                        radius = base * 1.9f,
                    ),
                    radius = base * 1.9f,
                    center = centre,
                )
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color(0xFFB388FF).copy(alpha = 0.65f), Color.Transparent),
                        center = centre + Offset(-drift, base * 0.2f),
                        radius = base * 1.3f,
                    ),
                    radius = base * 1.3f,
                    center = centre + Offset(-drift, base * 0.2f),
                )
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color(0xFF64D8FF).copy(alpha = 0.55f), Color.Transparent),
                        center = centre + Offset(drift, -base * 0.25f),
                        radius = base,
                    ),
                    radius = base,
                    center = centre + Offset(drift, -base * 0.25f),
                )
            }
            Text(
                text = "${state.persona.emoji} ${state.persona.displayName}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                text = "Speaking…",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
            Text(
                text = "Swipe down to close",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
