/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.metrolist.music.R
import com.metrolist.music.recommend.DjConfig
import com.metrolist.music.recommend.DjPersona
import com.metrolist.music.recommend.DjPresence
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem

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
    aiEnabled: Boolean,
    onAiChange: (Boolean) -> Unit,
    onConfigChange: (DjConfig) -> Unit,
    onDismiss: () -> Unit,
    onStart: () -> Unit,
    onOpenAiProviders: () -> Unit,
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
                        aiEnabled = aiEnabled,
                        onAiChange = onAiChange,
                        onConfigChange = onConfigChange,
                        onOpenAiProviders = onOpenAiProviders,
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
    aiEnabled: Boolean,
    onAiChange: (Boolean) -> Unit,
    onConfigChange: (DjConfig) -> Unit,
    onOpenAiProviders: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        DjPersonaHero(config)
        Spacer(Modifier.height(24.dp))
        DjSection(title = stringResource(R.string.radiostar_dj_personality)) {
            DjPersonaGrid(
                selected = config.persona,
                onSelect = { onConfigChange(config.copy(persona = it)) },
            )
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
                ),
        )
    }
}

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
    selected: DjPersona,
    onSelect: (DjPersona) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DjPersona.values().toList().chunked(3).forEach { row ->
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
                                text = persona.displayName.removePrefix("DJ "),
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
