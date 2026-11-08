/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
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
import com.metrolist.music.constants.ScrobblerApiKeyKey
import com.metrolist.music.constants.ScrobblerBaseUrlKey
import com.metrolist.music.constants.ScrobblerLastFmUserKey
import com.metrolist.music.constants.ScrobblerLastResultKey
import com.metrolist.music.constants.ScrobblerLastSyncKey
import com.metrolist.music.constants.ScrobblerLastTotalKey
import com.metrolist.music.constants.ScrobblerLastTotalServiceKey
import com.metrolist.music.constants.ScrobblerListenBrainzUserKey
import com.metrolist.music.constants.ScrobblerServiceKey
import com.metrolist.music.constants.ScrobblerSyncFrequencyKey
import com.metrolist.music.constants.ScrobblerTokenKey
import com.metrolist.music.recommend.ListenStore
import com.metrolist.music.recommend.RadioStarRefreshBus
import com.metrolist.music.recommend.ScrobblerConfig
import com.metrolist.music.recommend.ScrobblerImporter
import com.metrolist.music.recommend.ScrobblerService
import com.metrolist.music.recommend.ScrobblerSync
import com.metrolist.music.recommend.ScrobblerSyncFrequency
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.EnumDialog
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListeningHistorySettings(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var importServiceId by rememberPreference(ScrobblerServiceKey, ScrobblerService.NONE.id)
    var lastFmUser by rememberPreference(ScrobblerLastFmUserKey, "")
    var listenBrainzUser by rememberPreference(ScrobblerListenBrainzUserKey, "")
    var importBaseUrl by rememberPreference(ScrobblerBaseUrlKey, "")
    var importToken by rememberPreference(ScrobblerTokenKey, "")
    var importApiKey by rememberPreference(ScrobblerApiKeyKey, "")
    var syncFrequency by rememberPreference(ScrobblerSyncFrequencyKey, ScrobblerSyncFrequency.SIX_HOURS.id)
    var importStatus by rememberPreference(ScrobblerLastResultKey, "")
    var lastChecked by rememberPreference(ScrobblerLastSyncKey, 0L)
    var serviceTotal by rememberPreference(ScrobblerLastTotalKey, 0L)
    var serviceTotalService by rememberPreference(ScrobblerLastTotalServiceKey, "")
    var importing by remember { mutableStateOf(false) }
    var progressFetched by remember { mutableIntStateOf(0) }
    var progressTotal by remember { mutableStateOf<Long?>(null) }
    var localCount by remember { mutableIntStateOf(0) }
    var showImportServiceDialog by remember { mutableStateOf(false) }
    var showFrequencyDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var inputTarget by remember { mutableStateOf<String?>(null) }
    var inputDraft by remember { mutableStateOf("") }

    val importService =
        ScrobblerService.values().firstOrNull { it.id == importServiceId } ?: ScrobblerService.NONE

    LaunchedEffect(importServiceId, lastChecked) {
        localCount =
            withContext(Dispatchers.IO) {
                ListenStore(context).count(importService.id)
            }
    }

    LaunchedEffect(importServiceId, syncFrequency) {
        withContext(Dispatchers.IO) {
            ScrobblerSync.reschedule(context)
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
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top),
            ),
        )

        Material3SettingsGroup(
            title = stringResource(R.string.radiostar_scrobbler),
            items =
                buildList {
                    add(
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.radio),
                            title = { Text(stringResource(R.string.radiostar_listens_source)) },
                            description = { Text(importService.label) },
                            onClick = { showImportServiceDialog = true },
                        ),
                    )
                    if (importService == ScrobblerService.LASTFM) {
                        add(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.person),
                                title = { Text(stringResource(R.string.radiostar_scrobbler_user)) },
                                description = { Text(lastFmUser.ifBlank { "—" }) },
                                onClick = { inputTarget = "importuser"; inputDraft = lastFmUser },
                            ),
                        )
                        add(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.lock),
                                title = { Text(stringResource(R.string.radiostar_scrobbler_api_key)) },
                                description = { Text(if (importApiKey.isBlank()) "—" else "••••••") },
                                onClick = { inputTarget = "importapikey"; inputDraft = importApiKey },
                            ),
                        )
                    }
                    if (importService == ScrobblerService.LISTENBRAINZ ||
                        importService == ScrobblerService.SELF_HOSTED
                    ) {
                        add(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.person),
                                title = { Text(stringResource(R.string.radiostar_scrobbler_user)) },
                                description = { Text(listenBrainzUser.ifBlank { "—" }) },
                                onClick = { inputTarget = "importuser"; inputDraft = listenBrainzUser },
                            ),
                        )
                        add(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.lock),
                                title = { Text(stringResource(R.string.radiostar_scrobbler_token)) },
                                description = { Text(if (importToken.isBlank()) "—" else "••••••") },
                                onClick = { inputTarget = "importtoken"; inputDraft = importToken },
                            ),
                        )
                    }
                    if (importService == ScrobblerService.SELF_HOSTED) {
                        add(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.cloud),
                                title = { Text(stringResource(R.string.radiostar_scrobbler_url)) },
                                description = { Text(importBaseUrl.ifBlank { "—" }) },
                                onClick = { inputTarget = "importurl"; inputDraft = importBaseUrl },
                            ),
                        )
                    }
                    if (importService != ScrobblerService.NONE) {
                        add(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.sync),
                                title = { Text(stringResource(R.string.radiostar_scrobbler_import)) },
                                onClick = {
                                    if (!importing) {
                                        importing = true
                                        progressFetched = 0
                                        progressTotal = null
                                        importStatus = context.getString(R.string.radiostar_scrobbler_importing)
                                        scope.launch {
                                            val result =
                                                ScrobblerImporter.import(
                                                    context,
                                                    ScrobblerConfig(
                                                        service = importService,
                                                        lastFmUser = lastFmUser,
                                                        listenBrainzUser = listenBrainzUser,
                                                        baseUrl = importBaseUrl,
                                                        token = importToken,
                                                        apiKey = importApiKey,
                                                    ),
                                                ) { fetched, total ->
                                                    scope.launch {
                                                        progressFetched = fetched
                                                        progressTotal = total
                                                    }
                                                }
                                            importStatus = result.message
                                            localCount = result.localTotal
                                            result.account?.let { listenBrainzUser = it }
                                            importing = false
                                            if (result.complete) RadioStarRefreshBus.request()
                                        }
                                    }
                                },
                            ),
                        )
                        add(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.music_note),
                                title = { Text(stringResource(R.string.radiostar_local_listens)) },
                                description = { Text(localCount.toString()) },
                            ),
                        )
                        add(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.cloud),
                                title = { Text(stringResource(R.string.radiostar_service_listens)) },
                                description = {
                                    Text(
                                        if (serviceTotal > 0L && serviceTotalService == importService.id) {
                                            serviceTotal.toString()
                                        } else {
                                            stringResource(R.string.not_set)
                                        },
                                    )
                                },
                            ),
                        )
                        if (localCount > 0) {
                            add(
                                Material3SettingsItem(
                                    icon = painterResource(R.drawable.delete),
                                    title = { Text(stringResource(R.string.radiostar_import_clear)) },
                                    onClick = { showClearDialog = true },
                                ),
                            )
                        }
                        add(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.sync),
                                title = { Text(stringResource(R.string.radiostar_last_checked)) },
                                description = {
                                    Text(
                                        if (lastChecked > 0L) {
                                            DateFormat
                                                .getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                                .format(Date(lastChecked))
                                        } else {
                                            stringResource(R.string.radiostar_scrobbler_never)
                                        },
                                    )
                                },
                            ),
                        )
                        val currentFrequency =
                            ScrobblerSyncFrequency.values().firstOrNull { it.id == syncFrequency }
                                ?: ScrobblerSyncFrequency.SIX_HOURS
                        add(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.sync),
                                title = { Text(stringResource(R.string.radiostar_sync_frequency)) },
                                description = { Text(currentFrequency.label) },
                                onClick = { showFrequencyDialog = true },
                            ),
                        )
                    }
                },
        )

        if (importService != ScrobblerService.NONE &&
            serviceTotal > 0L &&
            serviceTotalService == importService.id &&
            localCount > serviceTotal
        ) {
            Text(
                text = stringResource(R.string.radiostar_service_total_lag, serviceTotal, localCount),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (importStatus.isNotBlank() || importing) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = importStatus.ifBlank { stringResource(R.string.radiostar_scrobbler_never) },
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (importing) {
                    Text("$progressFetched / ${progressTotal?.toString() ?: "?"}")
                    val total = progressTotal
                    if (total != null && total > 0) {
                        LinearProgressIndicator(
                            progress = {
                                (progressFetched.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    if (showImportServiceDialog) {
        AlertDialog(
            onDismissRequest = { showImportServiceDialog = false },
            title = { Text(stringResource(R.string.radiostar_listens_source)) },
            text = {
                Column {
                    ScrobblerService.values().forEach { option ->
                        TextButton(
                            onClick = {
                                importServiceId = option.id
                                showImportServiceDialog = false
                            },
                        ) { Text(option.label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showImportServiceDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.radiostar_import_clear)) },
            text = { Text(stringResource(R.string.radiostar_import_clear_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        scope.launch {
                            ScrobblerImporter.clearImported(context, importService)
                            localCount = 0
                            serviceTotal = 0L
                            serviceTotalService = importService.id
                            importStatus = context.getString(R.string.radiostar_import_clear_done)
                        }
                    },
                ) { Text(stringResource(R.string.radiostar_import_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showFrequencyDialog) {
        EnumDialog(
            onDismiss = { showFrequencyDialog = false },
            onSelect = {
                syncFrequency = it
                showFrequencyDialog = false
            },
            title = stringResource(R.string.radiostar_sync_frequency),
            current = syncFrequency,
            values = ScrobblerSyncFrequency.values().map { it.id },
            valueText = { id ->
                ScrobblerSyncFrequency.values().firstOrNull { it.id == id }?.label ?: id
            },
        )
    }

    if (inputTarget != null) {
        AlertDialog(
            onDismissRequest = { inputTarget = null },
            title = {
                Text(
                    when (inputTarget) {
                        "importuser" -> stringResource(R.string.radiostar_scrobbler_user)
                        "importurl" -> stringResource(R.string.radiostar_scrobbler_url)
                        "importapikey" -> stringResource(R.string.radiostar_scrobbler_api_key)
                        "importtoken" -> stringResource(R.string.radiostar_scrobbler_token)
                        else -> ""
                    },
                )
            },
            text = {
                OutlinedTextField(
                    value = inputDraft,
                    onValueChange = { inputDraft = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (inputTarget) {
                            "importuser" -> {
                                if (importService == ScrobblerService.LASTFM) {
                                    lastFmUser = inputDraft.trim()
                                } else {
                                    listenBrainzUser = inputDraft.trim()
                                }
                            }
                            "importurl" -> importBaseUrl = inputDraft.trim().trimEnd('/')
                            "importtoken" -> importToken = inputDraft.trim()
                            "importapikey" -> importApiKey = inputDraft.trim()
                        }
                        inputTarget = null
                    },
                ) { Text(stringResource(R.string.radiostar_save)) }
            },
            dismissButton = {
                TextButton(onClick = { inputTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.radiostar_listening_history)) },
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
