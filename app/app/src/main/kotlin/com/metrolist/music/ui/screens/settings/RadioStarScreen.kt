/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import android.provider.Settings
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.metrolist.music.constants.RadioStarDjKey
import com.metrolist.music.constants.RadioStarServerKey
import com.metrolist.music.constants.ScrobbleCustomTokenKey
import com.metrolist.music.constants.ScrobbleCustomUrlKey
import com.metrolist.music.constants.ScrobbleDestinationKey
import com.metrolist.music.constants.ScrobbleTokenKey
import com.metrolist.music.constants.ScrobblerApiKeyKey
import com.metrolist.music.constants.ScrobblerBaseUrlKey
import com.metrolist.music.constants.ScrobblerLastFmUserKey
import com.metrolist.music.constants.ScrobblerLastResultKey
import com.metrolist.music.constants.ScrobblerListenBrainzUserKey
import com.metrolist.music.constants.ScrobblerServiceKey
import com.metrolist.music.constants.ScrobblerSyncFrequencyKey
import com.metrolist.music.constants.ScrobblerTokenKey
import com.metrolist.music.recommend.ScrobbleDestination
import com.metrolist.music.recommend.ScrobblerConfig
import com.metrolist.music.recommend.ScrobblerImporter
import com.metrolist.music.recommend.RadioStarRefreshBus
import com.metrolist.music.recommend.ScrobblerService
import com.metrolist.music.recommend.ScrobblerSyncFrequency
import com.metrolist.music.recommend.TelemetryStore
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
fun RadioStarScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Debug/test builds may configure the engine through globals. Until the
    // user changes something here, show the value the service is using.
    val globalDj = remember { Settings.Global.getInt(context.contentResolver, "radiostar_dj", 0) == 1 }
    val globalServer =
        remember {
            Settings.Global
                .getString(context.contentResolver, "radiostar_embed_server")
                ?.trim()
                ?.trimEnd('/')
                .orEmpty()
        }

    var dj by rememberPreference(RadioStarDjKey, globalDj)
    var server by rememberPreference(RadioStarServerKey, globalServer)

    // Import source (where listens are read from).
    var importServiceId by rememberPreference(ScrobblerServiceKey, ScrobblerService.NONE.id)
    var lastFmUser by rememberPreference(ScrobblerLastFmUserKey, "")
    var listenBrainzUser by rememberPreference(ScrobblerListenBrainzUserKey, "")
    var importBaseUrl by rememberPreference(ScrobblerBaseUrlKey, "")
    var importToken by rememberPreference(ScrobblerTokenKey, "")
    var importApiKey by rememberPreference(ScrobblerApiKeyKey, "")
    var syncFrequency by rememberPreference(ScrobblerSyncFrequencyKey, ScrobblerSyncFrequency.SIX_HOURS.id)
    var importStatus by rememberPreference(ScrobblerLastResultKey, "")
    var importing by remember { mutableStateOf(false) }
    var progressFetched by remember { mutableIntStateOf(0) }
    var progressTotal by remember { mutableStateOf<Long?>(null) }

    // Outbound scrobbling (where new plays are sent).
    var destinationId by rememberPreference(ScrobbleDestinationKey, ScrobbleDestination.OFF.id)
    var scrobbleToken by rememberPreference(ScrobbleTokenKey, "")
    var customUrl by rememberPreference(ScrobbleCustomUrlKey, "")
    var customToken by rememberPreference(ScrobbleCustomTokenKey, "")

    // Local-only telemetry.
    var telemetryEnabled by rememberPreference(AdvancedTelemetryEnabledKey, false)
    var telemetryCount by remember { mutableIntStateOf(0) }

    var showImportServiceDialog by remember { mutableStateOf(false) }
    var showDestinationDialog by remember { mutableStateOf(false) }
    var showCredits by remember { mutableStateOf(false) }
    var showTelemetryInfo by remember { mutableStateOf(false) }
    var showServerDialog by remember { mutableStateOf(false) }
    var serverDraft by remember { mutableStateOf("") }
    var inputTarget by remember { mutableStateOf<String?>(null) }
    var inputDraft by remember { mutableStateOf("") }

    val importService =
        ScrobblerService.values().firstOrNull { it.id == importServiceId } ?: ScrobblerService.NONE
    val destination = ScrobbleDestination.fromId(destinationId)

    LaunchedEffect(telemetryEnabled) {
        telemetryCount =
            withContext(Dispatchers.IO) {
                TelemetryStore.count(context)
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
                        icon = painterResource(R.drawable.info),
                        title = { Text(stringResource(R.string.radiostar_credits)) },
                        onClick = { showCredits = true },
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

        // ---------------------------------------------------------------- import
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
                                description = {
                                    Column {
                                        Text(
                                            importStatus.ifBlank {
                                                stringResource(R.string.radiostar_scrobbler_never)
                                            },
                                        )
                                        if (importing) {
                                            Text("$progressFetched / ${progressTotal?.toString() ?: "?"}")
                                            val total = progressTotal
                                            if (total != null && total > 0) {
                                                LinearProgressIndicator(
                                                    progress = {
                                                        (progressFetched.toFloat() / total.toFloat())
                                                            .coerceIn(0f, 1f)
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
                                },
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
                                            importing = false
                                            if (result.complete) RadioStarRefreshBus.request()
                                        }
                                    }
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
                                onClick = {
                                    val next =
                                        ScrobblerSyncFrequency.values()[
                                            (currentFrequency.ordinal + 1) %
                                                ScrobblerSyncFrequency.values().size
                                        ]
                                    syncFrequency = next.id
                                },
                            ),
                        )
                    }
                },
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ------------------------------------------------------------- scrobbling
        Material3SettingsGroup(
            title = stringResource(R.string.radiostar_scrobble_service),
            items =
                buildList {
                    add(
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.radio),
                            title = { Text(stringResource(R.string.radiostar_scrobble_destination)) },
                            description = { Text(destination.label) },
                            onClick = { showDestinationDialog = true },
                        ),
                    )
                    when (destination) {
                        ScrobbleDestination.LASTFM -> {
                            add(
                                Material3SettingsItem(
                                    icon = painterResource(R.drawable.person),
                                    title = { Text(stringResource(R.string.radiostar_scrobble_lastfm_account)) },
                                    description = {
                                        Text(stringResource(R.string.radiostar_scrobble_lastfm_hint))
                                    },
                                    onClick = {
                                        navController.navigate("settings/integrations/lastfm")
                                    },
                                ),
                            )
                        }
                        ScrobbleDestination.LISTENBRAINZ -> {
                            add(
                                Material3SettingsItem(
                                    icon = painterResource(R.drawable.lock),
                                    title = { Text(stringResource(R.string.radiostar_scrobble_token)) },
                                    description = {
                                        Text(
                                            if (scrobbleToken.isNotBlank()) {
                                                "••••••"
                                            } else if (importToken.isNotBlank()) {
                                                stringResource(R.string.radiostar_scrobble_uses_import_token)
                                            } else {
                                                "—"
                                            },
                                        )
                                    },
                                    onClick = {
                                        inputTarget = "scrobbletoken"
                                        inputDraft = scrobbleToken.ifBlank { importToken }
                                    },
                                ),
                            )
                        }
                        ScrobbleDestination.CUSTOM -> {
                            add(
                                Material3SettingsItem(
                                    icon = painterResource(R.drawable.cloud),
                                    title = { Text(stringResource(R.string.radiostar_scrobble_custom_url)) },
                                    description = { Text(customUrl.ifBlank { "—" }) },
                                    onClick = { inputTarget = "customurl"; inputDraft = customUrl },
                                ),
                            )
                            add(
                                Material3SettingsItem(
                                    icon = painterResource(R.drawable.lock),
                                    title = { Text(stringResource(R.string.radiostar_scrobble_token)) },
                                    description = { Text(if (customToken.isBlank()) "—" else "••••••") },
                                    onClick = { inputTarget = "customtoken"; inputDraft = customToken },
                                ),
                            )
                        }
                        ScrobbleDestination.OFF,
                        ScrobbleDestination.EXTERNAL,
                        -> Unit
                    }
                },
        )
        Text(
            text =
                when (destination) {
                    ScrobbleDestination.OFF ->
                        stringResource(R.string.radiostar_scrobble_off_hint)
                    ScrobbleDestination.EXTERNAL ->
                        stringResource(R.string.radiostar_scrobble_external_hint)
                    ScrobbleDestination.LASTFM ->
                        stringResource(R.string.radiostar_scrobble_lastfm_hint)
                    ScrobbleDestination.LISTENBRAINZ ->
                        stringResource(R.string.radiostar_scrobble_listenbrainz_hint)
                    ScrobbleDestination.CUSTOM ->
                        stringResource(R.string.radiostar_scrobble_custom_hint)
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )

        Spacer(modifier = Modifier.height(16.dp))

        // --------------------------------------------------------------- telemetry
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

        Spacer(modifier = Modifier.height(16.dp))
    }

    if (showCredits) {
        AlertDialog(
            onDismissRequest = { showCredits = false },
            title = { Text(stringResource(R.string.radiostar_credits)) },
            text = { Text(stringResource(R.string.radiostar_credits_text)) },
            confirmButton = {
                TextButton(onClick = { showCredits = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
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

    if (showDestinationDialog) {
        AlertDialog(
            onDismissRequest = { showDestinationDialog = false },
            title = { Text(stringResource(R.string.radiostar_scrobble_destination)) },
            text = {
                Column {
                    ScrobbleDestination.values().forEach { option ->
                        TextButton(
                            onClick = {
                                destinationId = option.id
                                showDestinationDialog = false
                            },
                        ) { Text(option.label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDestinationDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
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
                        "scrobbletoken" -> stringResource(R.string.radiostar_scrobble_token)
                        "customurl" -> stringResource(R.string.radiostar_scrobble_custom_url)
                        "customtoken" -> stringResource(R.string.radiostar_scrobble_token)
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
                            "scrobbletoken" -> scrobbleToken = inputDraft.trim()
                            "customurl" -> customUrl = inputDraft.trim().trimEnd('/')
                            "customtoken" -> customToken = inputDraft.trim()
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
