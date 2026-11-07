/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.recommend.DjConfig
import com.metrolist.music.recommend.DjPersona
import com.metrolist.music.recommend.DjPresence

@Composable
fun DjHomeCard(
    config: DjConfig,
    onClick: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = config.persona.emoji, style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = config.persona.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.radiostar_dj_play),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun DjPanelDialog(
    config: DjConfig,
    onConfigChange: (DjConfig) -> Unit,
    onDismiss: () -> Unit,
    onStart: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("${config.persona.emoji} ${config.persona.displayName}")
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                DjDial(config)
                Text(
                    text = config.persona.tagline,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                PresenceRow(
                    label = stringResource(R.string.radiostar_dj_known),
                    value = config.known,
                    onChange = { onConfigChange(config.copy(known = it)) },
                )
                PresenceRow(
                    label = stringResource(R.string.radiostar_dj_likely),
                    value = config.likely,
                    onChange = { onConfigChange(config.copy(likely = it)) },
                )
                PresenceRow(
                    label = stringResource(R.string.radiostar_dj_far),
                    value = config.far,
                    onChange = { onConfigChange(config.copy(far = it)) },
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.radiostar_dj_personality),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    DjPersona.values().toList().chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { persona ->
                                FilterChip(
                                    selected = config.persona == persona,
                                    onClick = { onConfigChange(config.copy(persona = persona)) },
                                    label = { Text("${persona.emoji} ${persona.displayName.removePrefix("DJ ")}") },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onStart) {
                Text(stringResource(R.string.radiostar_dj_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun PresenceRow(
    label: String,
    value: DjPresence,
    onChange: (DjPresence) -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        DjPresence.values().forEach { option ->
            FilterChip(
                selected = value == option,
                onClick = { onChange(option) },
                label = { Text(presenceLabel(option)) },
            )
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
private fun DjDial(config: DjConfig) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary
    Box(
        modifier = Modifier.fillMaxWidth().height(130.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(120.dp)) {
            val radius = size.minDimension / 2f
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
        Text(text = config.persona.emoji, style = MaterialTheme.typography.headlineLarge)
    }
}
