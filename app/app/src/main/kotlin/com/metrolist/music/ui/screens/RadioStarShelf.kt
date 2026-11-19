/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.recommend.PlaylistHorizon
import com.metrolist.music.recommend.RadioStarPlaylistRepository
import com.metrolist.music.ui.component.GeneratedPlaylistArtwork

/**
 * Home shelf with the generated playlists. The DJ hero sits with the RadioStar
 * block instead of trailing it, and two horizon sections keep daily and weekly
 * lists from competing in one long row. Tapping a card opens it; the play button
 * starts it without navigating.
 */
@Composable
fun RadioStarShelf(
    cards: List<RadioStarPlaylistRepository.Card>,
    onOpen: (String) -> Unit,
    onPlay: (RadioStarPlaylistRepository.Card) -> Unit,
    modifier: Modifier = Modifier,
    artworkOpacity: Float = 0.45f,
    djContent: (@Composable () -> Unit)? = null,
) {
    val daily = cards.filter { it.horizon == PlaylistHorizon.DAILY }
    val weekly = cards.filter { it.horizon == PlaylistHorizon.WEEKLY }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.radio),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.radiostar_playlists),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        djContent?.invoke()

        if (cards.isEmpty()) {
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    ),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(R.drawable.radio),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.radiostar_playlists_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            if (daily.isNotEmpty()) {
                HorizonSection(
                    title = stringResource(R.string.radiostar_daily),
                    cards = daily,
                    artworkOpacity = artworkOpacity,
                    onOpen = onOpen,
                    onPlay = onPlay,
                )
            }
            if (weekly.isNotEmpty()) {
                HorizonSection(
                    title = stringResource(R.string.radiostar_weekly),
                    cards = weekly,
                    artworkOpacity = artworkOpacity,
                    onOpen = onOpen,
                    onPlay = onPlay,
                )
            }
        }
    }
}

@Composable
private fun HorizonSection(
    title: String,
    cards: List<RadioStarPlaylistRepository.Card>,
    artworkOpacity: Float,
    onOpen: (String) -> Unit,
    onPlay: (RadioStarPlaylistRepository.Card) -> Unit,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 6.dp),
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(cards, key = { it.id }) { card ->
            PlaylistCard(
                card = card,
                artworkOpacity = artworkOpacity,
                onClick = { onOpen(card.id) },
                onPlay = { onPlay(card) },
            )
        }
    }
}

@Composable
private fun PlaylistCard(
    card: RadioStarPlaylistRepository.Card,
    artworkOpacity: Float,
    onClick: () -> Unit,
    onPlay: () -> Unit,
) {
    val accent = accentFor(card.horizon)
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.width(184.dp).height(148.dp),
        shape = RoundedCornerShape(28.dp),
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            GeneratedPlaylistArtwork(
                seeds = card.artworkSeeds,
                accent = accent,
                opacity = artworkOpacity,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                                0.4f to Color.Transparent,
                                0.65f to Color.Transparent,
                                1f to MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                            ),
                        ),
            )
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp),
            ) {
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.radiostar_track_count, card.trackCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    FilledTonalIconButton(onClick = onPlay) {
                        Icon(
                            painterResource(R.drawable.play),
                            contentDescription = stringResource(R.string.radiostar_play_playlist),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun accentFor(horizon: PlaylistHorizon): Color =
    when (horizon) {
        PlaylistHorizon.DAILY -> MaterialTheme.colorScheme.primary
        PlaylistHorizon.WEEKLY -> MaterialTheme.colorScheme.tertiary
        PlaylistHorizon.SPECIAL -> MaterialTheme.colorScheme.secondary
    }
