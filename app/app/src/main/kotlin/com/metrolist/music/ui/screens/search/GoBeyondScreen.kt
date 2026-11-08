/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.R
import com.metrolist.music.recommend.GoBeyondCard
import com.metrolist.music.recommend.GoBeyondCatalog
import com.metrolist.music.recommend.GoBeyondFeed
import com.metrolist.music.recommend.GoBeyondSection
import com.metrolist.music.recommend.GoBeyondSelection
import com.metrolist.music.ui.component.GeneratedPlaylistArtwork
import com.metrolist.music.viewmodels.GoBeyondEvent
import com.metrolist.music.viewmodels.GoBeyondUiState
import com.metrolist.music.viewmodels.GoBeyondViewModel

/**
 * The Go Beyond tab finds an area the listener has not explored yet and turns
 * it into an ordinary local playlist. Section cards are ordered by the local
 * telemetry affinity, so areas the listener already enjoys stay in reach while
 * skipped areas move down.
 */
@Composable
fun GoBeyondScreen(
    onOpenPlaylist: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GoBeyondViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var customOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is GoBeyondEvent.Created -> onOpenPlaylist(event.playlistId)
                is GoBeyondEvent.Failed -> Unit
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        val feed = state.feed
        when {
            feed != null ->
                GoBeyondContent(
                    state = state,
                    feed = feed,
                    onCard = { card -> viewModel.generate(card.selection) },
                    onCustom = { customOpen = true },
                    onRecommended = { viewModel.generate(it) },
                )

            state.loading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

            else ->
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = state.message ?: stringResource(R.string.go_beyond_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
        }

        if (state.generating) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
                            Text(stringResource(R.string.go_beyond_building))
                        }
                    }
                }
            }
        }

        state.message?.takeIf { feed != null }?.let { message ->
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    TextButton(onClick = viewModel::clearMessage) {
                        Text(stringResource(R.string.dismiss))
                    }
                }
            }
        }
    }

    if (customOpen) {
        val artists =
            state.feed
                ?.sections
                ?.firstOrNull { it.section == GoBeyondSection.ARTIST }
                ?.cards
                ?.map { it.title }
                .orEmpty()
        CustomJourneyDialog(
            artists = artists,
            onDismiss = { customOpen = false },
            onCreate = { selection ->
                customOpen = false
                viewModel.generate(selection)
            },
        )
    }
}

@Composable
private fun GoBeyondContent(
    state: GoBeyondUiState,
    feed: GoBeyondFeed,
    onCard: (GoBeyondCard) -> Unit,
    onCustom: () -> Unit,
    onRecommended: (GoBeyondSelection) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        item(key = "go_beyond_header") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.explore_outlined),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.go_beyond_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = stringResource(R.string.go_beyond_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.size(12.dp))
                InsightsRow(state)
            }
        }

        feed.recommended?.let { recommendation ->
            item(key = "go_beyond_recommended") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.go_beyond_recommended),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.size(8.dp))
                    RecommendedCard(
                        title = recommendation.title,
                        reason = recommendation.reason,
                        hue = recommendation.accentHue,
                        onClick = { onRecommended(recommendation.selection) },
                    )
                }
            }
        }

        item(key = "go_beyond_custom") {
            CustomCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                onClick = onCustom,
            )
        }

        feed.sections.forEach { section ->
            item(key = "section_${section.section}") {
                Column(Modifier.padding(top = 18.dp)) {
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Text(
                        text = section.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(section.cards, key = { it.id }) { card ->
                            JourneyCard(card = card, onClick = { onCard(card) })
                        }
                    }
                }
            }
        }

        item(key = "go_beyond_footer") {
            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 24.dp))
            Text(
                text = stringResource(R.string.go_beyond_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun InsightsRow(state: GoBeyondUiState) {
    val insights = state.feed?.insights
    if (insights == null || insights.events == 0) {
        Text(
            text = stringResource(R.string.go_beyond_no_telemetry),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InsightPill(
            label = stringResource(R.string.go_beyond_completion),
            value = "${(insights.completionRate * 100).toInt()}%",
        )
        InsightPill(
            label = stringResource(R.string.go_beyond_skip_rate),
            value = "${(insights.skipRate * 100).toInt()}%",
        )
        InsightPill(
            label = stringResource(R.string.go_beyond_discovery),
            value = "${(insights.discoveryRate * 100).toInt()}%",
        )
        InsightPill(
            label = stringResource(R.string.go_beyond_events),
            value = insights.events.toString(),
        )
    }
}

@Composable
private fun InsightPill(
    label: String,
    value: String,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecommendedCard(
    title: String,
    reason: String,
    hue: Int,
    onClick: () -> Unit,
) {
    ElevatedCard(
        onClick = onClick,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 128.dp)) {
            GeneratedPlaylistArtwork(
                seeds = listOf(title.hashCode()),
                accent = colorFor(hue),
                opacity = 0.9f,
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, MaterialTheme.colorScheme.surface.copy(alpha = 0.84f)),
                            ),
                        ).padding(18.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.size(10.dp))
                Button(onClick = onClick) {
                    Text(stringResource(R.string.go_beyond_start))
                }
            }
        }
    }
}

@Composable
private fun CustomCard(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    ElevatedCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.discover_tune),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.go_beyond_custom),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.go_beyond_custom_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                painter = painterResource(R.drawable.arrow_forward),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun JourneyCard(
    card: GoBeyondCard,
    onClick: () -> Unit,
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.width(172.dp).heightIn(min = 132.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 132.dp)) {
            GeneratedPlaylistArtwork(
                seeds = listOf(card.id.hashCode()),
                accent = colorFor(card.accentHue),
                opacity = 0.8f,
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)),
                            ),
                        ).padding(14.dp),
            ) {
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = card.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomJourneyDialog(
    artists: List<String>,
    onDismiss: () -> Unit,
    onCreate: (GoBeyondSelection) -> Unit,
) {
    var country by rememberSaveable { mutableStateOf<String?>(null) }
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    var artist by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableStateOf<String?>(null) }
    var mood by rememberSaveable { mutableStateOf<String?>(null) }
    var freeText by rememberSaveable { mutableStateOf("") }
    var fromText by rememberSaveable { mutableStateOf("") }
    var toText by rememberSaveable { mutableStateOf("") }

    val selection =
        GoBeyondSelection(
            country = country,
            genre = genre,
            artist = artist.takeIf { it.isNotBlank() },
            fromYear = fromText.toIntOrNull(),
            toYear = toText.toIntOrNull(),
            color = color,
            mood = mood,
            freeText = freeText.takeIf { it.isNotBlank() },
        )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.go_beyond_custom)) },
        text = {
            Column(
                modifier =
                    Modifier
                        .heightIn(max = 430.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.go_beyond_custom_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FacetRow(
                    title = stringResource(R.string.go_beyond_country),
                    values = GoBeyondCatalog.countries.map { it.title },
                    selected = country,
                    onSelect = { country = it },
                )
                FacetRow(
                    title = stringResource(R.string.go_beyond_genre),
                    values = GoBeyondCatalog.genres.map { it.title },
                    selected = genre,
                    onSelect = { genre = it },
                )
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text(stringResource(R.string.go_beyond_artist)) },
                    placeholder = {
                        if (artists.isNotEmpty()) {
                            Text(artists.take(2).joinToString(" / "))
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                )
                Text(
                    text = stringResource(R.string.go_beyond_period),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GoBeyondCatalog.periods.forEach { period ->
                        val label = period.title
                        FilterChip(
                            selected = fromText == period.selection.fromYear.toString() &&
                                toText == period.selection.toYear.toString(),
                            onClick = {
                                fromText = period.selection.fromYear?.toString().orEmpty()
                                toText = period.selection.toYear?.toString().orEmpty()
                            },
                            label = { Text(label) },
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 6.dp),
                ) {
                    OutlinedTextField(
                        value = fromText,
                        onValueChange = { fromText = it.filter(Char::isDigit).take(4) },
                        label = { Text(stringResource(R.string.go_beyond_year_from)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = toText,
                        onValueChange = { toText = it.filter(Char::isDigit).take(4) },
                        label = { Text(stringResource(R.string.go_beyond_year_to)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                FacetRow(
                    title = stringResource(R.string.go_beyond_color),
                    values = GoBeyondCatalog.colors.map { it.title },
                    selected = color,
                    onSelect = { color = it },
                )
                FacetRow(
                    title = stringResource(R.string.go_beyond_mood),
                    values = GoBeyondCatalog.moods.map { it.title },
                    selected = mood,
                    onSelect = { mood = it },
                )
                OutlinedTextField(
                    value = freeText,
                    onValueChange = { freeText = it },
                    label = { Text(stringResource(R.string.go_beyond_free_text)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !selection.isBlank(),
                onClick = { onCreate(selection) },
            ) {
                Text(stringResource(R.string.go_beyond_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dismiss))
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FacetRow(
    title: String,
    values: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 8.dp),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.go_beyond_any)) },
        )
        values.forEach { value ->
            FilterChip(
                selected = selected.equals(value, ignoreCase = true),
                onClick = { onSelect(if (selected.equals(value, ignoreCase = true)) null else value) },
                label = { Text(value) },
            )
        }
    }
}

private fun colorFor(hue: Int): Color =
    Color.hsl(
        hue = hue.coerceIn(0, 359).toFloat(),
        saturation = 0.58f,
        lightness = 0.56f,
    )
