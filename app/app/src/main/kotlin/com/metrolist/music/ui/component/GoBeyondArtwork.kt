/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.metrolist.music.recommend.GoBeyondArt
import com.metrolist.music.recommend.GoBeyondCard

/**
 * Artwork for a Go Beyond card.
 *
 * Countries use their flag, artists use a local artist photo when Room has one,
 * colours use their real palette, eras use a large year, and genres/moods use
 * generated abstract art. The caller draws the title gradient over it, so the
 * artwork keeps a slightly reduced opacity.
 */
@Composable
fun GoBeyondCardArtwork(
    card: GoBeyondCard,
    opacity: Float = 0.82f,
    modifier: Modifier = Modifier,
) {
    val visible = opacity.coerceIn(0f, 1f)
    val art = card.art
    when (art) {
        is GoBeyondArt.Flag -> {
            Box(modifier = modifier.background(colorFor(art.hue).copy(alpha = 0.24f))) {
                AbstractArt(
                    seed = card.id.hashCode(),
                    hue = art.hue,
                    opacity = visible * 0.55f,
                    modifier = Modifier.fillMaxSize(),
                )
                Text(
                    text = art.flag.ifBlank { card.title.take(2).uppercase() },
                    fontSize = 54.sp,
                    modifier = Modifier.align(Alignment.Center).alpha(visible.coerceAtLeast(0.75f)),
                )
            }
        }

        is GoBeyondArt.Photo -> {
            Box(modifier = modifier.background(colorFor(art.hue).copy(alpha = 0.3f))) {
                if (!art.imageUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = art.imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().alpha(visible),
                    )
                } else {
                    AbstractArt(
                        seed = card.id.hashCode(),
                        hue = art.hue,
                        opacity = visible,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Text(
                        text = initials(card.title),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                        fontSize = 38.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }

        is GoBeyondArt.Color -> {
            Box(modifier = modifier.background(colorFor(art.hue).copy(alpha = 0.42f))) {
                ColorArt(
                    seed = card.id.hashCode(),
                    hue = art.hue,
                    opacity = visible,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        is GoBeyondArt.Era -> {
            Box(modifier = modifier.background(colorFor(art.hue).copy(alpha = 0.18f))) {
                EraArt(
                    hue = art.hue,
                    opacity = visible,
                    modifier = Modifier.fillMaxSize(),
                )
                Text(
                    text = art.label,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        is GoBeyondArt.Mood -> {
            Box(modifier = modifier.background(colorFor(art.hue).copy(alpha = 0.2f))) {
                AbstractArt(
                    seed = card.id.hashCode(),
                    hue = art.hue,
                    opacity = visible,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        is GoBeyondArt.Abstract -> {
            AbstractArt(
                seed = card.id.hashCode(),
                hue = art.hue,
                opacity = visible,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun AbstractArt(
    seed: Int,
    hue: Int,
    opacity: Float,
    modifier: Modifier = Modifier,
) {
    GeneratedPlaylistArtwork(
        seeds = listOf(seed),
        accent = colorFor(hue),
        opacity = opacity,
        modifier = modifier,
    )
}

@Composable
private fun ColorArt(
    seed: Int,
    hue: Int,
    opacity: Float,
    modifier: Modifier = Modifier,
) {
    val base = colorFor(hue)
    val second = colorFor((hue + 28) % 360)
    Canvas(modifier = modifier) {
        drawRect(
            brush =
                Brush.linearGradient(
                    colors =
                        listOf(
                            base.copy(alpha = 0.72f * opacity),
                            second.copy(alpha = 0.5f * opacity),
                            Color.Transparent,
                        ),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
        )
        drawCircle(
            color = base.copy(alpha = 0.36f * opacity),
            radius = size.minDimension * 0.44f,
            center = Offset(size.width * 0.28f, size.height * 0.32f),
        )
        drawCircle(
            color = second.copy(alpha = 0.26f * opacity),
            radius = size.minDimension * 0.34f,
            center = Offset(size.width * 0.76f, size.height * 0.68f),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.12f * opacity),
            radius = size.minDimension * 0.22f,
            center = Offset(size.width * 0.68f, size.height * 0.22f),
        )
    }
}

@Composable
private fun EraArt(
    hue: Int,
    opacity: Float,
    modifier: Modifier = Modifier,
) {
    val tone = colorFor(hue)
    Canvas(modifier = modifier) {
        drawCircle(
            color = tone.copy(alpha = 0.28f * opacity),
            radius = size.minDimension * 0.44f,
            center = Offset(size.width * 0.5f, size.height * 0.44f),
            style = Stroke(width = size.minDimension * 0.055f),
        )
        drawCircle(
            color = tone.copy(alpha = 0.2f * opacity),
            radius = size.minDimension * 0.3f,
            center = Offset(size.width * 0.5f, size.height * 0.44f),
            style = Stroke(width = size.minDimension * 0.03f),
        )
        drawCircle(
            color = tone.copy(alpha = 0.16f * opacity),
            radius = size.minDimension * 0.12f,
            center = Offset(size.width * 0.5f, size.height * 0.44f),
        )
        val dots = 12
        for (index in 0 until dots) {
            val angle = (index * 360.0 / dots) * (Math.PI / 180.0)
            val radius = size.minDimension * 0.44f
            drawCircle(
                color = Color.White.copy(alpha = 0.16f * opacity),
                radius = size.minDimension * 0.012f,
                center =
                    Offset(
                        size.width * 0.5f + (Math.cos(angle) * radius).toFloat(),
                        size.height * 0.44f + (Math.sin(angle) * radius).toFloat(),
                    ),
            )
        }
    }
}

private fun initials(value: String): String {
    val words = value.trim().split(' ').filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words.first().take(2).uppercase()
        else -> "${words.first().first()}${words.last().first()}".uppercase()
    }
}

private fun colorFor(hue: Int): Color =
    Color.hsl(
        hue = hue.coerceIn(0, 359).toFloat(),
        saturation = 0.58f,
        lightness = 0.56f,
    )
