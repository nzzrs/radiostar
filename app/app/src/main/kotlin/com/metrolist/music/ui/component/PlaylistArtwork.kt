/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * A generated cover for a RadioStar playlist. Colours and positions come from
 * the tracks inside the list, so the artwork changes when the list does without
 * downloading or storing an image. The caller controls how present it is.
 */
data class PlaylistArtworkBlob(
    val x: Float,
    val y: Float,
    val radius: Float,
    val hue: Float,
)

/**
 * Deterministic artwork geometry. Kept free of Compose types so the palette
 * rules can be unit-tested and so the same playlist always looks the same.
 */
object GeneratedPlaylistArtwork {
    private const val MAX_BLOBS = 4
    private const val MIN_HUE_DISTANCE = 24f
    private const val FALLBACK_SEED = 0x5EED

    fun blobs(seeds: List<Int>): List<PlaylistArtworkBlob> {
        val source = seeds.take(MAX_BLOBS).ifEmpty { listOf(FALLBACK_SEED) }
        val hues = ArrayList<Float>(source.size)
        return source.mapIndexed { index, seed ->
            var hue = hueOf(seed)
            var attempts = 0
            while (hues.any { circularDistance(it, hue) < MIN_HUE_DISTANCE } && attempts < 12) {
                hue = (hue + 47f) % 360f
                attempts += 1
            }
            hues.add(hue)
            PlaylistArtworkBlob(
                x = 0.12f + 0.76f * unit(seed, index * 2 + 1),
                y = 0.12f + 0.76f * unit(seed, index * 2 + 2),
                radius = 0.44f + 0.4f * unit(seed, index * 2 + 7),
                hue = hue,
            )
        }
    }

    private fun hueOf(seed: Int): Float = Math.floorMod(seed, 360).toFloat()

    private fun circularDistance(
        first: Float,
        second: Float,
    ): Float = abs(first - second).let { minOf(it, 360f - it) }

    private fun unit(
        seed: Int,
        salt: Int,
    ): Float {
        var hash = seed xor (salt * -1640531527)
        hash = hash xor (hash ushr 16)
        hash *= -2048144789
        hash = hash xor (hash ushr 13)
        return (hash.toLong() and 0xFFFF_FFFFL).toFloat() / 4_294_967_295f
    }
}

@Composable
fun GeneratedPlaylistArtwork(
    seeds: List<Int>,
    accent: Color,
    opacity: Float,
    modifier: Modifier = Modifier,
) {
    val blobs = remember(seeds) { GeneratedPlaylistArtwork.blobs(seeds) }
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val visible = opacity.coerceIn(0f, 1f)

    Canvas(modifier = modifier) {
        drawRect(accent.copy(alpha = 0.1f * visible))
        blobs.forEach { blob ->
            val center = Offset(size.width * blob.x, size.height * blob.y)
            val radius = size.minDimension * blob.radius
            val tone =
                lerp(
                    accent,
                    Color.hsl(
                        blob.hue,
                        saturation = if (isDark) 0.58f else 0.46f,
                        lightness = if (isDark) 0.66f else 0.52f,
                    ),
                    0.68f,
                )
            drawCircle(
                brush =
                    Brush.radialGradient(
                        colors =
                            listOf(
                                tone.copy(alpha = 0.62f * visible),
                                tone.copy(alpha = 0.2f * visible),
                                Color.Transparent,
                            ),
                        center = center,
                        radius = radius,
                    ),
                radius = radius,
                center = center,
            )
        }

        val ringCenter = Offset(size.width * 0.84f, size.height * 0.18f)
        val ringColor = accent.copy(alpha = 0.2f * visible)
        drawCircle(
            color = ringColor,
            radius = size.minDimension * 0.42f,
            center = ringCenter,
            style = Stroke(width = 1.dp.toPx()),
        )
        drawCircle(
            color = ringColor.copy(alpha = ringColor.alpha * 0.6f),
            radius = size.minDimension * 0.3f,
            center = ringCenter,
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}
