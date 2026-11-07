/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.AdvancedTelemetryEnabledKey
import com.metrolist.music.constants.DisableScreenshotKey
import com.metrolist.music.constants.PauseListenHistoryKey
import com.metrolist.music.constants.PauseSearchHistoryKey
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
fun PrivacySettings(
    navController: NavController
) {
    val database = LocalDatabase.current
    val (pauseListenHistory, onPauseListenHistoryChange) = rememberPreference(
        key = PauseListenHistoryKey,
        defaultValue = false
    )
    val (pauseSearchHistory, onPauseSearchHistoryChange) = rememberPreference(
        key = PauseSearchHistoryKey,
        defaultValue = false
    )
    val (disableScreenshot, onDisableScreenshotChange) = rememberPreference(
        key = DisableScreenshotKey,
        defaultValue = false
    )
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var telemetryEnabled by rememberPreference(AdvancedTelemetryEnabledKey, false)
    var telemetryCount by remember { mutableIntStateOf(0) }
    var showTelemetryInfo by remember { mutableStateOf(false) }

    LaunchedEffect(telemetryEnabled) {
        telemetryCount =
            withContext(Dispatchers.IO) {
                TelemetryStore.count(context)
            }
    }

    var showClearListenHistoryDialog by remember {
        mutableStateOf(false)
    }

    if (showClearListenHistoryDialog) {
        DefaultDialog(
            onDismiss = { showClearListenHistoryDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.clear_listen_history_confirm),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            },
            buttons = {
                TextButton(
                    onClick = { showClearListenHistoryDialog = false },
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showClearListenHistoryDialog = false
                        database.query {
                            clearListenHistory()
                        }
                    },
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            },
        )
    }

    var showClearSearchHistoryDialog by remember {
        mutableStateOf(false)
    }

    if (showClearSearchHistoryDialog) {
        DefaultDialog(
            onDismiss = { showClearSearchHistoryDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.clear_search_history_confirm),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            },
            buttons = {
                TextButton(
                    onClick = { showClearSearchHistoryDialog = false },
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showClearSearchHistoryDialog = false
                        database.query {
                            clearSearchHistory()
                        }
                    },
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            },
        )
    }

    Column(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                )
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Top
                )
            )
        )

        Material3SettingsGroup(
            title = stringResource(R.string.listen_history),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.history),
                    title = { Text(stringResource(R.string.pause_listen_history)) },
                    trailingContent = {
                        Switch(
                            checked = pauseListenHistory,
                            onCheckedChange = onPauseListenHistoryChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (pauseListenHistory) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(androidx.compose.material3.SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onPauseListenHistoryChange(!pauseListenHistory) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.delete_history),
                    title = { Text(stringResource(R.string.clear_listen_history)) },
                    onClick = { showClearListenHistoryDialog = true }
                )
            )
        )

        Spacer(modifier = Modifier.height(27.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.search_history),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.search_off),
                    title = { Text(stringResource(R.string.pause_search_history)) },
                    trailingContent = {
                        Switch(
                            checked = pauseSearchHistory,
                            onCheckedChange = onPauseSearchHistoryChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (pauseSearchHistory) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(androidx.compose.material3.SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onPauseSearchHistoryChange(!pauseSearchHistory) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.clear_all),
                    title = { Text(stringResource(R.string.clear_search_history)) },
                    onClick = { showClearSearchHistoryDialog = true }
                )
            )
        )

        Spacer(modifier = Modifier.height(27.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.radiostar_telemetry),
            items =
                buildList {
                    add(
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.info),
                            title = { Text(stringResource(R.string.radiostar_telemetry_toggle)) },
                            description = { Text(stringResource(R.string.radiostar_telemetry_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = telemetryEnabled,
                                    onCheckedChange = { telemetryEnabled = it },
                                )
                            },
                            onClick = { telemetryEnabled = !telemetryEnabled },
                        ),
                    )
                    if (telemetryEnabled) {
                        add(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.info),
                                title = { Text(stringResource(R.string.radiostar_telemetry_fields)) },
                                description = {
                                    Text(stringResource(R.string.radiostar_telemetry_count, telemetryCount))
                                },
                                onClick = { showTelemetryInfo = true },
                            ),
                        )
                        add(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.delete),
                                title = { Text(stringResource(R.string.radiostar_telemetry_clear)) },
                                onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { TelemetryStore.clear(context) }
                                        telemetryCount = 0
                                    }
                                },
                            ),
                        )
                    }
                },
        )

        Spacer(modifier = Modifier.height(27.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.misc),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.screenshot),
                    title = { Text(stringResource(R.string.disable_screenshot)) },
                    description = { Text(stringResource(R.string.disable_screenshot_desc)) },
                    trailingContent = {
                        Switch(
                            checked = disableScreenshot,
                            onCheckedChange = onDisableScreenshotChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (disableScreenshot) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(androidx.compose.material3.SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onDisableScreenshotChange(!disableScreenshot) }
                )
            )
        )
        Spacer(modifier = Modifier.height(16.dp))
    }

    if (showTelemetryInfo) {
        AlertDialog(
            onDismissRequest = { showTelemetryInfo = false },
            title = { Text(stringResource(R.string.radiostar_telemetry_fields)) },
            text = { Text(stringResource(R.string.radiostar_telemetry_fields_text)) },
            confirmButton = {
                TextButton(onClick = { showTelemetryInfo = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.privacy)) },
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
        }
    )
}
