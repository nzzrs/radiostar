/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.metrolist.music.constants.RadioStarDjKey
import com.metrolist.music.constants.RadioStarEnergyKey
import com.metrolist.music.constants.RadioStarHistoryKey
import com.metrolist.music.constants.RadioStarMixKey
import com.metrolist.music.constants.RadioStarServerKey
import com.metrolist.music.recommend.ListenHistory
import com.metrolist.music.recommend.MixKind
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_HISTORY_KEYS = 5_000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioStarScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Debug/test builds may configure the engine through globals. Until the
    // user changes something here, show the value the service is using.
    val globalMix =
        remember {
            when (Settings.Global.getString(context.contentResolver, "radiostar_mix")?.lowercase()) {
                "jams" -> MixKind.JAMS
                "explore" -> MixKind.EXPLORE
                else -> MixKind.DISCOVER
            }
        }
    val globalDj = remember { Settings.Global.getInt(context.contentResolver, "radiostar_dj", 0) == 1 }
    val globalEnergy = remember { Settings.Global.getInt(context.contentResolver, "radiostar_energy", 0) == 1 }
    val globalServer =
        remember {
            Settings.Global
                .getString(context.contentResolver, "radiostar_embed_server")
                ?.trim()
                ?.trimEnd('/')
                .orEmpty()
        }

    var mix by rememberEnumPreference(RadioStarMixKey, globalMix)
    var dj by rememberPreference(RadioStarDjKey, globalDj)
    var energy by rememberPreference(RadioStarEnergyKey, globalEnergy)
    var server by rememberPreference(RadioStarServerKey, globalServer)
    var history by rememberPreference(RadioStarHistoryKey, emptySet<String>())

    var showServerDialog by remember { mutableStateOf(false) }
    var serverDraft by remember { mutableStateOf("") }
    var importFailed by remember { mutableStateOf(false) }

    val importer =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val text =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver
                                .openInputStream(uri)
                                ?.bufferedReader()
                                ?.use { it.readText() }
                        }.getOrNull()
                    }
                if (text.isNullOrBlank()) {
                    importFailed = true
                    return@launch
                }
                val keys = ListenHistory.historyKeys(ListenHistory.parseCsv(text))
                history = keys.take(MAX_HISTORY_KEYS).toSet()
                importFailed = false
            }
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
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Top,
                ),
            ),
        )

        Text(
            text = stringResource(R.string.radiostar_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )

        Text(
            text = stringResource(R.string.radiostar_mix),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MixKind.values().forEach { kind ->
                FilterChip(
                    selected = mix == kind,
                    onClick = { mix = kind },
                    label = { Text(mixLabel(kind)) },
                )
            }
        }
        Text(
            text = mixDescription(mix),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )

        Material3SettingsGroup(
            items =
                listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.music_note),
                        title = { Text(stringResource(R.string.radiostar_dj)) },
                        description = { Text(stringResource(R.string.radiostar_dj_desc)) },
                        trailingContent = {
                            Switch(checked = dj, onCheckedChange = { dj = it })
                        },
                        onClick = { dj = !dj },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.equalizer),
                        title = { Text(stringResource(R.string.radiostar_energy)) },
                        description = { Text(stringResource(R.string.radiostar_energy_desc)) },
                        trailingContent = {
                            Switch(checked = energy, onCheckedChange = { energy = it })
                        },
                        onClick = { energy = !energy },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.radio),
                        title = { Text(stringResource(R.string.radiostar_server)) },
                        description = {
                            Text(server.ifBlank { stringResource(R.string.radiostar_server_empty) })
                        },
                        onClick = {
                            serverDraft = server
                            showServerDialog = true
                        },
                    ),
                ),
        )

        Spacer(modifier = Modifier.height(16.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.radiostar_history),
            items =
                listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.queue_music),
                        title = { Text(stringResource(R.string.radiostar_history)) },
                        description = {
                            Text(
                                if (importFailed) {
                                    stringResource(R.string.radiostar_history_failed)
                                } else if (history.isEmpty()) {
                                    stringResource(R.string.radiostar_history_help)
                                } else {
                                    stringResource(R.string.radiostar_history_count, history.size)
                                },
                            )
                        },
                        onClick = { importer.launch(arrayOf("text/csv", "text/plain", "*/*")) },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.clear_all),
                        title = { Text(stringResource(R.string.radiostar_history_clear)) },
                        enabled = history.isNotEmpty(),
                        onClick = { history = emptySet() },
                    ),
                ),
        )

        Spacer(modifier = Modifier.height(16.dp))
    }

    if (showServerDialog) {
        AlertDialog(
            onDismissRequest = { showServerDialog = false },
            title = { Text(stringResource(R.string.radiostar_server)) },
            text = {
                OutlinedTextField(
                    value = serverDraft,
                    onValueChange = { serverDraft = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.radiostar_server_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        server = serverDraft.trim().trimEnd('/')
                        showServerDialog = false
                    },
                ) {
                    Text(stringResource(R.string.radiostar_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showServerDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.radiostar)) },
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
private fun mixLabel(kind: MixKind): String =
    stringResource(
        when (kind) {
            MixKind.DISCOVER -> R.string.radiostar_mix_discover
            MixKind.JAMS -> R.string.radiostar_mix_jams
            MixKind.EXPLORE -> R.string.radiostar_mix_explore
        },
    )

@Composable
private fun mixDescription(kind: MixKind): String =
    stringResource(
        when (kind) {
            MixKind.DISCOVER -> R.string.radiostar_mix_discover_desc
            MixKind.JAMS -> R.string.radiostar_mix_jams_desc
            MixKind.EXPLORE -> R.string.radiostar_mix_explore_desc
        },
    )
