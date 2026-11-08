/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.AdvancedTelemetryEnabledKey
import com.metrolist.music.recommend.TelemetryInsights
import com.metrolist.music.recommend.TelemetryStore
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelemetrySettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by rememberPreference(AdvancedTelemetryEnabledKey, false)
    var count by remember { mutableIntStateOf(0) }
    var insights by remember { mutableStateOf<TelemetryInsights?>(null) }
    var showInfo by remember { mutableStateOf(false) }
    var showClear by remember { mutableStateOf(false) }

    LaunchedEffect(enabled) {
        val events =
            withContext(Dispatchers.IO) {
                if (enabled) TelemetryStore.load(context) else emptyList()
            }
        count = events.size
        insights = if (enabled) TelemetryInsights.fromEvents(events) else null
    }

    Column(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                ),
            ).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top),
            ),
        )

        Material3SettingsGroup(
            items =
                listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.info),
                        title = { Text(stringResource(R.string.radiostar_telemetry_toggle)) },
                        trailingContent = {
                            Switch(checked = enabled, onCheckedChange = { enabled = it })
                        },
                        onClick = { enabled = !enabled },
                    ),
                ),
        )

        Spacer(Modifier.height(27.dp))

        if (enabled) {
            Material3SettingsGroup(
                items =
                    listOf(
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.history),
                            title = { Text(stringResource(R.string.radiostar_telemetry_fields)) },
                            description = {
                                Text(stringResource(R.string.radiostar_telemetry_count, count))
                            },
                            onClick = { showInfo = true },
                        ),
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.delete),
                            title = { Text(stringResource(R.string.radiostar_telemetry_clear)) },
                            onClick = { showClear = true },
                        ),
                    ),
            )
            Spacer(Modifier.height(16.dp))
            insights?.takeIf { it.events > 0 }?.let { summary ->
                TelemetrySignal(summary)
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(stringResource(R.string.radiostar_telemetry_fields)) },
            text = { Text(stringResource(R.string.radiostar_telemetry_fields_text)) },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    if (showClear) {
        DefaultDialog(
            onDismiss = { showClear = false },
            content = {
                Text(
                    text = stringResource(R.string.radiostar_telemetry_clear_confirm),
                    style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            },
            buttons = {
                TextButton(onClick = { showClear = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(
                    onClick = {
                        showClear = false
                        scope.launch {
                            withContext(Dispatchers.IO) { TelemetryStore.clear(context) }
                            count = 0
                        }
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.radiostar_telemetry)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
    )
}

@Composable
private fun TelemetrySignal(insights: TelemetryInsights) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                text = stringResource(R.string.radiostar_telemetry_signal),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text =
                    stringResource(
                        R.string.radiostar_telemetry_signal_line,
                        (insights.completionRate * 100).toInt(),
                        (insights.skipRate * 100).toInt(),
                        (insights.discoveryRate * 100).toInt(),
                    ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text =
                    stringResource(
                        R.string.radiostar_telemetry_sessions,
                        insights.sessions,
                        insights.distinctSongs,
                        insights.distinctArtists,
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            insights.topArtists.take(3).takeIf { it.isNotEmpty() }?.let { artists ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text =
                        stringResource(
                            R.string.radiostar_telemetry_top_artists,
                            artists.joinToString(", ") { it.displayName },
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
