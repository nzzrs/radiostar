/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.metrolist.music.R
import com.metrolist.music.playback.DjVoicePreview
import com.metrolist.music.recommend.DjConfig
import com.metrolist.music.recommend.DjPersona
import com.metrolist.music.recommend.DjPersonaIssue
import com.metrolist.music.recommend.DjPersonaLibrary
import com.metrolist.music.recommend.DjPersonaValidation
import com.metrolist.music.recommend.DjPresence
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import java.util.Locale

@Composable
fun DjHomeCard(
    config: DjConfig,
    error: String?,
    onStart: () -> Unit,
    onSettings: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clickable(onClick = onStart),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.2f),
                            ),
                        ),
                    ),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = config.persona.emoji, style = MaterialTheme.typography.headlineSmall)
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = config.persona.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.radiostar_dj_play),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!error.isNullOrBlank()) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Box(
                    modifier =
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.play),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = onSettings) {
                    Icon(
                        painterResource(R.drawable.settings),
                        contentDescription = stringResource(R.string.radiostar_dj_settings),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The full DJ settings panel. It is shared by the Home gear action and the
 * Settings destination so both stay in sync and use the same hierarchy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DjPanelDialog(
    config: DjConfig,
    personas: DjPersonaLibrary,
    onSelectPersona: (DjPersona) -> Unit,
    onSavePersona: (DjPersona) -> Unit,
    onDeletePersona: (DjPersona) -> Unit,
    aiEnabled: Boolean,
    onAiChange: (Boolean) -> Unit,
    onConfigChange: (DjConfig) -> Unit,
    onDismiss: () -> Unit,
    onStart: () -> Unit,
    onOpenAiProviders: () -> Unit,
    contextEnabled: Boolean = true,
    weatherEnabled: Boolean = false,
    newsEnabled: Boolean = false,
    city: String = "",
    onContextEnabledChange: (Boolean) -> Unit = {},
    onWeatherEnabledChange: (Boolean) -> Unit = {},
    onNewsEnabledChange: (Boolean) -> Unit = {},
    onCityChange: (String) -> Unit = {},
    directTtsEnabled: Boolean = true,
    onDirectTtsChange: (Boolean) -> Unit = {},
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
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                TopAppBar(
                    title = { Text(stringResource(R.string.radiostar_dj_settings)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                painter = painterResource(R.drawable.close),
                                contentDescription = stringResource(R.string.close),
                            )
                        }
                    },
                )
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                ) {
                    DjSettingsBody(
                        config = config,
                        personas = personas,
                        onSelectPersona = onSelectPersona,
                        onSavePersona = onSavePersona,
                        onDeletePersona = onDeletePersona,
                        aiEnabled = aiEnabled,
                        onAiChange = onAiChange,
                        onConfigChange = onConfigChange,
                        onOpenAiProviders = onOpenAiProviders,
                        contextEnabled = contextEnabled,
                        weatherEnabled = weatherEnabled,
                        newsEnabled = newsEnabled,
                        city = city,
                        onContextEnabledChange = onContextEnabledChange,
                        onWeatherEnabledChange = onWeatherEnabledChange,
                        onNewsEnabledChange = onNewsEnabledChange,
                        onCityChange = onCityChange,
                        directTtsEnabled = directTtsEnabled,
                        onDirectTtsChange = onDirectTtsChange,
                    )
                    Spacer(Modifier.height(16.dp))
                }
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Button(
                        onClick = onStart,
                        shape = RoundedCornerShape(26.dp),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .heightIn(min = 52.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.play),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.radiostar_dj_play))
                    }
                }
            }
        }
    }
}

@Composable
fun DjSettingsBody(
    config: DjConfig,
    personas: DjPersonaLibrary,
    onSelectPersona: (DjPersona) -> Unit,
    onSavePersona: (DjPersona) -> Unit,
    onDeletePersona: (DjPersona) -> Unit,
    aiEnabled: Boolean,
    onAiChange: (Boolean) -> Unit,
    onConfigChange: (DjConfig) -> Unit,
    onOpenAiProviders: () -> Unit,
    contextEnabled: Boolean = true,
    weatherEnabled: Boolean = false,
    newsEnabled: Boolean = false,
    city: String = "",
    onContextEnabledChange: (Boolean) -> Unit = {},
    onWeatherEnabledChange: (Boolean) -> Unit = {},
    onNewsEnabledChange: (Boolean) -> Unit = {},
    onCityChange: (String) -> Unit = {},
    directTtsEnabled: Boolean = true,
    onDirectTtsChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    var hasCoarseLocation by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasCoarseLocation = granted
        }
    val changeContext: (Boolean) -> Unit = { enabled ->
        onContextEnabledChange(enabled)
        if (enabled && !hasCoarseLocation) {
            permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<DjPersona?>(null) }
    var pendingDelete by remember { mutableStateOf<DjPersona?>(null) }
    Column(Modifier.fillMaxWidth()) {
        DjPersonaHero(config)
        Spacer(Modifier.height(24.dp))
        DjSection(title = stringResource(R.string.radiostar_dj_personality)) {
            DjPersonaGrid(
                personas = personas.all,
                selected = config.persona,
                onSelect = onSelectPersona,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        editing = null
                        editorOpen = true
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.radiostar_dj_create))
                }
                if (!config.persona.builtin) {
                    TextButton(
                        onClick = {
                            editing = config.persona
                            editorOpen = true
                        },
                    ) {
                        Text(stringResource(R.string.radiostar_dj_edit))
                    }
                    TextButton(onClick = { pendingDelete = config.persona }) {
                        Text(stringResource(R.string.delete))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            DjVoiceTestButton(persona = config.persona)
        }
        Spacer(Modifier.height(24.dp))
        DjSection(
            title = stringResource(R.string.dj_section_mix),
            description = stringResource(R.string.dj_mix_desc),
        ) {
            DjMixRow(
                title = stringResource(R.string.radiostar_dj_known),
                description = stringResource(R.string.dj_mix_known_desc),
                value = config.known,
                onChange = { onConfigChange(config.copy(known = it)) },
            )
            Spacer(Modifier.height(16.dp))
            DjMixRow(
                title = stringResource(R.string.radiostar_dj_likely),
                description = stringResource(R.string.dj_mix_likely_desc),
                value = config.likely,
                onChange = { onConfigChange(config.copy(likely = it)) },
            )
            Spacer(Modifier.height(16.dp))
            DjMixRow(
                title = stringResource(R.string.radiostar_dj_far),
                description = stringResource(R.string.dj_mix_far_desc),
                value = config.far,
                onChange = { onConfigChange(config.copy(far = it)) },
            )
        }
        Spacer(Modifier.height(24.dp))
        Material3SettingsGroup(
            title = stringResource(R.string.dj_section_voice),
            items =
                listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.mic),
                        title = { Text(stringResource(R.string.radiostar_dj_ai)) },
                        description = { Text(stringResource(R.string.dj_ai_desc)) },
                        trailingContent = {
                            Switch(
                                checked = aiEnabled,
                                onCheckedChange = onAiChange,
                            )
                        },
                        onClick = { onAiChange(!aiEnabled) },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.key_vertical),
                        title = { Text(stringResource(R.string.dj_ai_provider)) },
                        description = { Text(stringResource(R.string.dj_ai_provider_desc)) },
                        onClick = onOpenAiProviders,
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.speed),
                        title = { Text(stringResource(R.string.dj_direct_tts)) },
                        description = { Text(stringResource(R.string.dj_direct_tts_desc)) },
                        trailingContent = {
                            Switch(
                                checked = directTtsEnabled,
                                onCheckedChange = onDirectTtsChange,
                            )
                        },
                        onClick = { onDirectTtsChange(!directTtsEnabled) },
                    ),
                ),
        )
        Spacer(Modifier.height(24.dp))
        DjSection(
            title = stringResource(R.string.dj_section_context),
            description = stringResource(R.string.dj_context_privacy),
        ) {
            DjToggleRow(
                title = stringResource(R.string.dj_context_enable),
                description = stringResource(R.string.dj_context_enable_desc),
                checked = contextEnabled,
                onCheckedChange = changeContext,
            )
            Spacer(Modifier.height(12.dp))
            DjToggleRow(
                title = stringResource(R.string.dj_context_weather),
                description = stringResource(R.string.dj_context_weather_desc),
                checked = weatherEnabled,
                onCheckedChange = { onWeatherEnabledChange(it) },
                enabled = contextEnabled,
            )
            Spacer(Modifier.height(12.dp))
            DjToggleRow(
                title = stringResource(R.string.dj_context_news),
                description = stringResource(R.string.dj_context_news_desc),
                checked = newsEnabled,
                onCheckedChange = { onNewsEnabledChange(it) },
                enabled = contextEnabled,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = city,
                onValueChange = onCityChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = contextEnabled,
                singleLine = true,
                label = { Text(stringResource(R.string.dj_context_city)) },
                placeholder = { Text(stringResource(R.string.dj_context_city_hint)) },
                supportingText = { Text(stringResource(R.string.dj_context_city_desc)) },
                shape = RoundedCornerShape(16.dp),
            )
        }
    }

    if (editorOpen) {
        DjPersonaEditorDialog(
            persona = editing,
            existingIds = personas.all.map { it.id }.toSet(),
            onDismiss = { editorOpen = false },
            onSave = { persona ->
                onSavePersona(persona)
                editorOpen = false
            },
        )
    }
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.radiostar_dj_delete_title)) },
            text = { Text(stringResource(R.string.radiostar_dj_delete_message, target.displayName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeletePersona(target)
                        pendingDelete = null
                    },
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun DjToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color =
                    if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun DjPersonaEditorDialog(
    persona: DjPersona?,
    existingIds: Set<String>,
    onDismiss: () -> Unit,
    onSave: (DjPersona) -> Unit,
) {
    var name by remember(persona) { mutableStateOf(persona?.displayName.orEmpty()) }
    var emoji by remember(persona) { mutableStateOf(persona?.emoji ?: "🎧") }
    var tagline by remember(persona) { mutableStateOf(persona?.tagline.orEmpty()) }
    var pitch by remember(persona) { mutableStateOf(persona?.pitch ?: 1f) }
    var rate by remember(persona) { mutableStateOf(persona?.rate ?: 1f) }
    var issue by remember(persona) { mutableStateOf<DjPersonaIssue?>(null) }

    fun draft(): DjPersona =
        DjPersona(
            id = persona?.id ?: DjPersona.newCustomId(name, existingIds),
            emoji = emoji.trim(),
            displayName = name.trim(),
            tagline = tagline.trim(),
            pitch = pitch,
            rate = rate,
        )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (persona == null) R.string.radiostar_dj_editor_new else R.string.radiostar_dj_editor_edit,
                ),
            )
        },
        text = {
            Column(
                modifier =
                    Modifier
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        issue = null
                    },
                    label = { Text(stringResource(R.string.radiostar_dj_field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = emoji,
                    onValueChange = {
                        emoji = it.take(16)
                        issue = null
                    },
                    label = { Text(stringResource(R.string.radiostar_dj_field_emoji)) },
                    supportingText = { Text(stringResource(R.string.radiostar_dj_field_emoji_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = tagline,
                    onValueChange = {
                        tagline = it
                        issue = null
                    },
                    label = { Text(stringResource(R.string.radiostar_dj_field_style)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                DjVoiceSlider(
                    label = stringResource(R.string.radiostar_dj_field_pitch),
                    value = pitch,
                    onChange = {
                        pitch = it
                        issue = null
                    },
                )
                DjVoiceSlider(
                    label = stringResource(R.string.radiostar_dj_field_rate),
                    value = rate,
                    onChange = {
                        rate = it
                        issue = null
                    },
                )
                issue?.let { problem ->
                    Text(
                        text = personaIssueText(problem),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                DjVoiceTestButton(persona = draft())
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val candidate = draft()
                    val problem = DjPersonaValidation.problem(candidate)
                    if (problem != null) {
                        issue = problem
                    } else {
                        onSave(candidate)
                    }
                },
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun DjVoiceSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
) {
    val sliderState =
        rememberSliderState(
            value = value,
            steps = 19,
            trackRange = 0.5f..1.5f,
        )
    LaunchedEffect(value) {
        if (sliderState.value != value) sliderState.value = value
    }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, style = MaterialTheme.typography.titleSmall)
            Text(
                text = String.format(Locale.getDefault(), "%.2f", value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            state = sliderState,
            onValueChange = onChange,
        )
    }
}

@Composable
private fun DjVoiceTestButton(
    persona: DjPersona,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var playing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val sample = stringResource(R.string.radiostar_dj_voice_sample, persona.displayName)

    DisposableEffect(Unit) {
        onDispose { DjVoicePreview.stop() }
    }

    Column(modifier = modifier) {
        OutlinedButton(
            onClick = {
                error = null
                playing = true
                val failure =
                    DjVoicePreview.speak(context, persona, sample) {
                        playing = false
                    }
                if (failure != null) {
                    playing = false
                    error = failure
                }
            },
            enabled = !playing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (playing) R.string.ai_voice_test_playing else R.string.ai_voice_test,
                ),
            )
        }
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun personaIssueText(issue: DjPersonaIssue): String =
    stringResource(
        when (issue) {
            DjPersonaIssue.NAME -> R.string.radiostar_dj_error_name
            DjPersonaIssue.EMOJI -> R.string.radiostar_dj_error_emoji
            DjPersonaIssue.PITCH,
            DjPersonaIssue.RATE,
            -> R.string.radiostar_dj_error_voice_range
        },
    )

@Composable
private fun DjPersonaHero(config: DjConfig) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DjDial(config = config, size = 96.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    text = config.persona.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = config.persona.tagline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DjSection(
    title: String,
    description: String? = null,
    content: @Composable () -> Unit,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        description?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
            )
        }
        content()
    }
}

@Composable
private fun DjPersonaGrid(
    personas: List<DjPersona>,
    selected: DjPersona,
    onSelect: (DjPersona) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        personas.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { persona ->
                    val isSelected = selected == persona
                    Surface(
                        onClick = { onSelect(persona) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(20.dp),
                        color =
                            if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                        contentColor =
                            if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        border =
                            if (isSelected) {
                                BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                            } else {
                                null
                            },
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = persona.emoji,
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Text(
                                text = persona.shortName,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                repeat(3 - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DjMixRow(
    title: String,
    description: String,
    value: DjPresence,
    onChange: (DjPresence) -> Unit,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            DjPresence.values().forEachIndexed { index, option ->
                SegmentedButton(
                    selected = value == option,
                    onClick = { onChange(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = DjPresence.values().size),
                    icon = {},
                    label = { Text(presenceLabel(option)) },
                )
            }
        }
    }
}

@Composable
private fun presenceLabel(presence: DjPresence): String =
    stringResource(
        when (presence) {
            DjPresence.NEVER -> R.string.radiostar_dj_never
            DjPresence.SOMETIMES -> R.string.radiostar_dj_sometimes
            DjPresence.FREQUENT -> R.string.radiostar_dj_frequently
        },
    )

@Composable
private fun DjDial(
    config: DjConfig,
    size: Dp,
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary
    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = this.size.minDimension / 2f
            drawCircle(primary.copy(alpha = 0.25f), radius = radius)
            drawCircle(primary, radius = radius, style = Stroke(2.dp.toPx()))
            drawCircle(
                if (config.known == DjPresence.NEVER) primary.copy(alpha = 0.15f) else primary,
                radius = radius * 0.72f,
                style = Stroke((if (config.known == DjPresence.FREQUENT) 8f else 4f).dp.toPx()),
            )
            drawCircle(
                if (config.likely == DjPresence.NEVER) secondary.copy(alpha = 0.15f) else secondary,
                radius = radius * 0.5f,
                style = Stroke((if (config.likely == DjPresence.FREQUENT) 8f else 4f).dp.toPx()),
            )
            drawCircle(
                if (config.far == DjPresence.NEVER) tertiary.copy(alpha = 0.15f) else tertiary,
                radius = radius * 0.28f,
                style = Stroke((if (config.far == DjPresence.FREQUENT) 8f else 4f).dp.toPx()),
            )
            drawCircle(Color.Transparent)
        }
        Text(text = config.persona.emoji, style = MaterialTheme.typography.headlineMedium)
    }
}
