/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.AdvancedTelemetryEnabledKey
import com.metrolist.music.recommend.GoBeyondCard
import com.metrolist.music.recommend.GoBeyondCatalog
import com.metrolist.music.recommend.GoBeyondCountry
import com.metrolist.music.recommend.GoBeyondFeed
import com.metrolist.music.recommend.GoBeyondFuzzy
import com.metrolist.music.recommend.GoBeyondGeography
import com.metrolist.music.recommend.GoBeyondRecommendation
import com.metrolist.music.recommend.GoBeyondSection
import com.metrolist.music.recommend.GoBeyondSelection
import com.metrolist.music.recommend.TelemetryInsights
import com.metrolist.music.ui.component.GoBeyondCardArtwork
import com.metrolist.music.viewmodels.GoBeyondEvent
import com.metrolist.music.viewmodels.GoBeyondUiState
import com.metrolist.music.viewmodels.GoBeyondViewModel
import com.metrolist.music.utils.rememberPreference

private val JourneyCardShape = RoundedCornerShape(24.dp)
private val JourneyGroupShape = RoundedCornerShape(28.dp)

private enum class BuilderStep {
    FORM,
    COUNTRY,
    GENRE,
}

/**
 * The Go Beyond tab finds an area the listener has not explored yet and turns
 * it into an ordinary local playlist. Section cards are ordered by the local
 * telemetry affinity, so areas the listener already enjoys stay in reach while
 * skipped areas move down.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GoBeyondScreen(
    onOpenPlaylist: (String) -> Unit,
    onOpenTelemetry: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GoBeyondViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val telemetryEnabled by rememberPreference(AdvancedTelemetryEnabledKey, false)
    var customOpen by rememberSaveable { mutableStateOf(false) }
    var countryPickerOpen by rememberSaveable { mutableStateOf(false) }
    var genrePickerOpen by rememberSaveable { mutableStateOf(false) }

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
                    onRecommended = { viewModel.generate(it.selection) },
                    onAllCountries = { countryPickerOpen = true },
                    onCustomGenre = { genrePickerOpen = true },
                    onOpenTelemetry = onOpenTelemetry,
                    telemetryEnabled = telemetryEnabled,
                )

            state.loading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.go_beyond_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

            else ->
                EmptyGoBeyond(
                    message = state.message ?: stringResource(R.string.go_beyond_empty),
                )
        }

        if (state.generating) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Surface(
                        shape = JourneyGroupShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shadowElevation = 6.dp,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            LoadingIndicator(Modifier.size(26.dp))
                            Text(
                                text = stringResource(R.string.go_beyond_building),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        }

        state.message?.takeIf { feed != null }?.let { message ->
            Snackbar(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .imePadding()
                        .padding(horizontal = 16.dp, vertical = 104.dp),
                action = {
                    TextButton(onClick = viewModel::clearMessage) {
                        Text(stringResource(R.string.dismiss))
                    }
                },
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                actionContentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Text(message)
            }
        }
    }

    if (countryPickerOpen) {
        CountryPickerDialog(
            onDismiss = { countryPickerOpen = false },
            onSelect = { country ->
                countryPickerOpen = false
                viewModel.generate(GoBeyondSelection(country = country.name))
            },
        )
    }

    if (genrePickerOpen) {
        GenrePickerDialog(
            onDismiss = { genrePickerOpen = false },
            onSelect = { genre ->
                genrePickerOpen = false
                viewModel.generate(GoBeyondSelection(genre = genre))
            },
        )
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
private fun EmptyGoBeyond(message: String) {
    Box(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.explore_outlined),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GoBeyondContent(
    state: GoBeyondUiState,
    feed: GoBeyondFeed,
    onCard: (GoBeyondCard) -> Unit,
    onCustom: () -> Unit,
    onRecommended: (GoBeyondRecommendation) -> Unit,
    onAllCountries: () -> Unit,
    onCustomGenre: () -> Unit,
    onOpenTelemetry: () -> Unit,
    telemetryEnabled: Boolean,
) {
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom),
                ),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "go_beyond_intro") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = stringResource(R.string.go_beyond_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                ListeningSummary(
                    insights = feed.insights,
                    telemetryEnabled = telemetryEnabled,
                    onOpenTelemetry = onOpenTelemetry,
                )
            }
        }

        if (feed.recommended.isNotEmpty()) {
            item(key = "go_beyond_recommended") {
                Column(Modifier.padding(top = 16.dp)) {
                    SectionTitle(
                        icon = R.drawable.trending_up,
                        title = stringResource(R.string.go_beyond_recommended),
                    )
                    Spacer(Modifier.height(10.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(feed.recommended, key = { it.title + it.reason }) { recommendation ->
                            RecommendedCard(
                                recommendation = recommendation,
                                onClick = { onRecommended(recommendation) },
                            )
                        }
                    }
                }
            }
        }

        item(key = "go_beyond_custom") {
            CustomCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                onClick = onCustom,
            )
        }

        feed.sections.forEach { section ->
            item(key = "section_${section.section}") {
                Column(Modifier.padding(top = 8.dp)) {
                    SectionTitle(
                        icon = sectionIcon(section.section),
                        title = section.title,
                        action =
                            when (section.section) {
                                GoBeyondSection.COUNTRY -> {
                                    {
                                        TextButton(onClick = onAllCountries) {
                                            Icon(
                                                painter = painterResource(R.drawable.arrow_forward),
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(stringResource(R.string.go_beyond_all_countries))
                                        }
                                    }
                                }

                                GoBeyondSection.GENRE -> {
                                    {
                                        TextButton(onClick = onCustomGenre) {
                                            Icon(
                                                painter = painterResource(R.drawable.add),
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(stringResource(R.string.go_beyond_custom_genre))
                                        }
                                    }
                                }

                                else -> null
                            },
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
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
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    painter = painterResource(R.drawable.lock),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.go_beyond_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ListeningSummary(
    insights: TelemetryInsights?,
    telemetryEnabled: Boolean,
    onOpenTelemetry: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = JourneyGroupShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        if (!telemetryEnabled) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(40.dp)
                            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.stats),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.go_beyond_telemetry_off_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.go_beyond_telemetry_off_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onOpenTelemetry) {
                    Text(stringResource(R.string.enable))
                }
            }
        } else if (insights == null || insights.events == 0) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.stats),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.go_beyond_telemetry_on_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.stats),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = stringResource(R.string.go_beyond_insights),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${insights.events} ${stringResource(R.string.go_beyond_events)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    InsightMetric(
                        label = stringResource(R.string.go_beyond_completion),
                        value = "${(insights.completionRate * 100).toInt()}%",
                        modifier = Modifier.weight(1f),
                    )
                    InsightDivider()
                    InsightMetric(
                        label = stringResource(R.string.go_beyond_skip_rate),
                        value = "${(insights.skipRate * 100).toInt()}%",
                        modifier = Modifier.weight(1f),
                    )
                    InsightDivider()
                    InsightMetric(
                        label = stringResource(R.string.go_beyond_discovery),
                        value = "${(insights.discoveryRate * 100).toInt()}%",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun InsightMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InsightDivider() {
    Box(
        Modifier
            .padding(horizontal = 4.dp)
            .width(1.dp)
            .height(32.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun SectionTitle(
    icon: Int,
    title: String,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

@Composable
private fun RecommendedCard(
    recommendation: GoBeyondRecommendation,
    onClick: () -> Unit,
) {
    val card =
        GoBeyondCard(
            id = "recommended-${recommendation.title}",
            section =
                recommendation.selection.facets.firstOrNull()?.section
                    ?: GoBeyondSection.CUSTOM,
            title = recommendation.title,
            subtitle = recommendation.reason,
            description = recommendation.reason,
            selection = recommendation.selection,
            accentHue = recommendation.accentHue,
            semanticPrompt = recommendation.title,
            art = recommendation.art,
        )
    Surface(
        onClick = onClick,
        modifier = Modifier.width(248.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                GoBeyondCardArtwork(
                    card = card,
                    opacity = 0.95f,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = recommendation.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = recommendation.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CustomCard(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = JourneyCardShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .background(
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f),
                            CircleShape,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.tune),
                    contentDescription = null,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.go_beyond_custom),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.go_beyond_custom_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
            Icon(
                painter = painterResource(R.drawable.arrow_forward),
                contentDescription = null,
            )
        }
    }
}

@Composable
private fun JourneyCard(
    card: GoBeyondCard,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.width(172.dp).height(200.dp),
        shape = JourneyCardShape,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        GoBeyondCardArtwork(
            card = card,
            opacity = 0.95f,
            modifier = Modifier.fillMaxWidth().height(116.dp),
        )
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = card.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
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

private fun sectionIcon(section: GoBeyondSection): Int =
    when (section) {
        GoBeyondSection.COUNTRY -> R.drawable.language
        GoBeyondSection.GENRE -> R.drawable.library_music
        GoBeyondSection.ARTIST -> R.drawable.person
        GoBeyondSection.PERIOD -> R.drawable.history
        GoBeyondSection.COLOR -> R.drawable.palette
        GoBeyondSection.MOOD -> R.drawable.bedtime
        GoBeyondSection.CUSTOM -> R.drawable.tune
    }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CustomJourneyDialog(
    artists: List<String>,
    onDismiss: () -> Unit,
    onCreate: (GoBeyondSelection) -> Unit,
) {
    var step by rememberSaveable { mutableStateOf(BuilderStep.FORM) }
    var country by rememberSaveable { mutableStateOf<String?>(null) }
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    var artist by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableStateOf<String?>(null) }
    var mood by rememberSaveable { mutableStateOf<String?>(null) }
    var freeText by rememberSaveable { mutableStateOf("") }
    var fromText by rememberSaveable { mutableStateOf("") }
    var toText by rememberSaveable { mutableStateOf("") }
    val formListState = rememberLazyListState()

    val fromYear = fromText.toIntOrNull()
    val toYear = toText.toIntOrNull()
    val yearsInvalid = fromYear != null && toYear != null && fromYear > toYear
    val selection =
        GoBeyondSelection(
            country = country,
            genre = genre,
            artist = artist.takeIf { it.isNotBlank() },
            fromYear = fromYear,
            toYear = toYear,
            color = color,
            mood = mood,
            freeText = freeText.takeIf { it.isNotBlank() },
        )

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            when (step) {
                BuilderStep.COUNTRY ->
                    CountryPicker(
                        onBack = { step = BuilderStep.FORM },
                        onSelect = {
                            country = it.name
                            step = BuilderStep.FORM
                        },
                    )

                BuilderStep.GENRE ->
                    GenrePicker(
                        onBack = { step = BuilderStep.FORM },
                        onSelect = {
                            genre = it
                            step = BuilderStep.FORM
                        },
                    )

                BuilderStep.FORM -> {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing),
                    ) {
                        TopAppBar(
                            title = { Text(stringResource(R.string.go_beyond_custom)) },
                            navigationIcon = {
                                IconButton(onClick = onDismiss) {
                                    Icon(
                                        painter = painterResource(R.drawable.close),
                                        contentDescription = stringResource(R.string.close),
                                    )
                                }
                            },
                        )

                        LazyColumn(
                            state = formListState,
                            modifier = Modifier.weight(1f),
                            contentPadding =
                                PaddingValues(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 8.dp,
                                    bottom = 96.dp,
                                ),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            item {
                                Text(
                                    text = stringResource(R.string.go_beyond_custom_help),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            item {
                                BuilderSection(title = stringResource(R.string.go_beyond_builder_place)) {
                                    BuilderPickerRow(
                                        icon = R.drawable.language,
                                        label = stringResource(R.string.go_beyond_country),
                                        value = country,
                                        onClick = { step = BuilderStep.COUNTRY },
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    BuilderPickerRow(
                                        icon = R.drawable.library_music,
                                        label = stringResource(R.string.go_beyond_genre),
                                        value = genre,
                                        onClick = { step = BuilderStep.GENRE },
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    OutlinedTextField(
                                        value = artist,
                                        onValueChange = { artist = it },
                                        label = { Text(stringResource(R.string.go_beyond_artist)) },
                                        placeholder = {
                                            if (artists.isNotEmpty()) {
                                                Text(artists.take(2).joinToString(" / "))
                                            }
                                        },
                                        leadingIcon = {
                                            Icon(
                                                painter = painterResource(R.drawable.person),
                                                contentDescription = null,
                                            )
                                        },
                                        singleLine = true,
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }

                            item {
                                BuilderSection(title = stringResource(R.string.go_beyond_builder_time)) {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        FilterChip(
                                            selected = fromText.isBlank() && toText.isBlank(),
                                            onClick = {
                                                fromText = ""
                                                toText = ""
                                            },
                                            label = { Text(stringResource(R.string.go_beyond_any)) },
                                        )
                                        GoBeyondCatalog.periods.forEach { period ->
                                            val selected =
                                                fromText == period.selection.fromYear?.toString() &&
                                                    toText == period.selection.toYear?.toString()
                                            FilterChip(
                                                selected = selected,
                                                onClick = {
                                                    fromText =
                                                        period.selection.fromYear?.toString().orEmpty()
                                                    toText =
                                                        period.selection.toYear?.toString().orEmpty()
                                                },
                                                label = { Text(period.title) },
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        OutlinedTextField(
                                            value = fromText,
                                            onValueChange = {
                                                fromText = it.filter(Char::isDigit).take(4)
                                            },
                                            label = { Text(stringResource(R.string.go_beyond_year_from)) },
                                            singleLine = true,
                                            isError = yearsInvalid,
                                            keyboardOptions =
                                                KeyboardOptions(keyboardType = KeyboardType.Number),
                                            shape = RoundedCornerShape(16.dp),
                                            modifier = Modifier.weight(1f),
                                        )
                                        OutlinedTextField(
                                            value = toText,
                                            onValueChange = {
                                                toText = it.filter(Char::isDigit).take(4)
                                            },
                                            label = { Text(stringResource(R.string.go_beyond_year_to)) },
                                            singleLine = true,
                                            isError = yearsInvalid,
                                            keyboardOptions =
                                                KeyboardOptions(keyboardType = KeyboardType.Number),
                                            shape = RoundedCornerShape(16.dp),
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    if (yearsInvalid) {
                                        Text(
                                            text = stringResource(R.string.go_beyond_year_range_invalid),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.padding(top = 6.dp),
                                        )
                                    }
                                }
                            }

                            item {
                                BuilderSection(title = stringResource(R.string.go_beyond_builder_vibe)) {
                                    FacetChips(
                                        title = stringResource(R.string.go_beyond_color),
                                        values = GoBeyondCatalog.colors,
                                        selected = color,
                                        showColorDots = true,
                                        onSelect = { color = it },
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    FacetChips(
                                        title = stringResource(R.string.go_beyond_mood),
                                        values = GoBeyondCatalog.moods,
                                        selected = mood,
                                        onSelect = { mood = it },
                                    )
                                }
                            }

                            item {
                                OutlinedTextField(
                                    value = freeText,
                                    onValueChange = { freeText = it },
                                    label = { Text(stringResource(R.string.go_beyond_free_text)) },
                                    minLines = 2,
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }

                        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                            Button(
                                enabled = !selection.isBlank() && !yearsInvalid,
                                onClick = { onCreate(selection) },
                                shape = RoundedCornerShape(26.dp),
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp)
                                        .heightIn(min = 52.dp),
                            ) {
                                Text(stringResource(R.string.go_beyond_create))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BuilderSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        content()
    }
}

@Composable
private fun BuilderPickerRow(
    icon: Int,
    label: String,
    value: String?,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = value ?: stringResource(R.string.go_beyond_any),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                painter = painterResource(R.drawable.expand_more),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FacetChips(
    title: String,
    values: List<com.metrolist.music.recommend.GoBeyondEntry>,
    selected: String?,
    showColorDots: Boolean = false,
    onSelect: (String?) -> Unit,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.go_beyond_any)) },
            )
            values.forEach { entry ->
                val isSelected = selected.equals(entry.title, ignoreCase = true)
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelect(if (isSelected) null else entry.title) },
                    label = { Text(entry.title) },
                    leadingIcon =
                        if (showColorDots) {
                            {
                                Box(
                                    Modifier
                                        .size(14.dp)
                                        .border(
                                            1.dp,
                                            MaterialTheme.colorScheme.outlineVariant,
                                            CircleShape,
                                        )
                                        .background(swatchColor(entry.title, entry.hue), CircleShape),
                                )
                            }
                        } else {
                            null
                        },
                )
            }
        }
    }
}

private fun swatchColor(name: String, hue: Int): Color =
    when (name.lowercase()) {
        "black" -> Color(0xFF2B2B2B)
        "white" -> Color(0xFFF4F4F4)
        "grey" -> Color(0xFF9A9A9A)
        "silver" -> Color(0xFFC4C4C4)
        "gold" -> Color(0xFFD4AF37)
        "brown" -> Color(0xFF8B5A2B)
        "magenta" -> Color(0xFFE0218A)
        "pink" -> Color(0xFFF06292)
        "purple" -> Color(0xFF9C27B0)
        "indigo" -> Color(0xFF3F51B5)
        "blue" -> Color(0xFF2196F3)
        "teal" -> Color(0xFF009688)
        "turquoise" -> Color(0xFF1DE9B6)
        "green" -> Color(0xFF4CAF50)
        "yellow" -> Color(0xFFFDD835)
        "orange" -> Color(0xFFFF9800)
        "red" -> Color(0xFFE53935)
        else ->
            Color.hsl(
                hue = hue.coerceIn(0, 359).toFloat(),
                saturation = 0.58f,
                lightness = 0.56f,
            )
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CountryPickerDialog(
    onDismiss: () -> Unit,
    onSelect: (GoBeyondCountry) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            CountryPicker(onBack = onDismiss, onSelect = onSelect)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenrePickerDialog(
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            GenrePicker(onBack = onDismiss, onSelect = onSelect)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CountryPicker(
    onBack: () -> Unit,
    onSelect: (GoBeyondCountry) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(query) { GoBeyondGeography.search(query, limit = 300) }

    PickerScaffold(
        title = stringResource(R.string.go_beyond_all_countries),
        searchPlaceholder = stringResource(R.string.go_beyond_search_countries),
        query = query,
        onQueryChange = { query = it },
        onBack = onBack,
    ) { modifier ->
        if (results.isEmpty()) {
            EmptyPicker(modifier)
        } else {
            LazyColumn(modifier = modifier) {
                items(results, key = { it.code }) { country ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(country) }
                                .heightIn(min = 60.dp)
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(country.flag, fontSize = 24.sp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = country.name,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (!country.adjective.equals(country.name, ignoreCase = true)) {
                                Text(
                                    text = country.adjective,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenrePicker(
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val sanitized = remember(query) { GoBeyondFuzzy.sanitizeUserText(query) }
    val results =
        remember(query) {
            if (sanitized.isBlank()) {
                GoBeyondCatalog.genres
            } else {
                val ranked =
                    GoBeyondFuzzy.rank(
                        sanitized,
                        GoBeyondCatalog.genres.flatMap { listOf(it.title) + it.aliases },
                    )
                ranked
                    .filter { it.score >= 0.45f }
                    .mapNotNull { match ->
                        GoBeyondCatalog.genres.firstOrNull { entry ->
                            entry.title.equals(match.value, ignoreCase = true) ||
                                entry.aliases.any { it.equals(match.value, ignoreCase = true) }
                        }
                    }.distinctBy { it.title }
            }
        }

    PickerScaffold(
        title = stringResource(R.string.go_beyond_custom_genre),
        searchPlaceholder = stringResource(R.string.go_beyond_search_genres),
        query = query,
        onQueryChange = { query = it },
        onBack = onBack,
    ) { modifier ->
        LazyColumn(modifier = modifier) {
            if (sanitized.isNotBlank()) {
                item(key = "custom_genre") {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(sanitized) }
                                .heightIn(min = 60.dp)
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.add),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(R.string.go_beyond_use_genre, sanitized),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                if (results.isNotEmpty()) {
                    item(key = "divider") {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
            if (results.isEmpty() && sanitized.isBlank()) {
                item(key = "empty") {
                    EmptyPicker(Modifier.fillMaxWidth().padding(top = 48.dp))
                }
            }
            items(results, key = { it.title }) { entry ->
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(entry.title) }
                            .heightIn(min = 60.dp)
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(entry.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = entry.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerScaffold(
    title: String,
    searchPlaceholder: String,
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_back),
                        contentDescription = stringResource(R.string.back),
                    )
                }
            },
        )
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text(searchPlaceholder) },
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.search),
                    contentDescription = null,
                )
            },
            trailingIcon =
                if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(
                                painter = painterResource(R.drawable.close),
                                contentDescription = stringResource(R.string.clear),
                            )
                        }
                    }
                } else {
                    null
                },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        content(Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun EmptyPicker(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.search_off),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
            )
            Text(
                text = stringResource(R.string.go_beyond_no_results),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
