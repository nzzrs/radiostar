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
import com.metrolist.music.constants.RadioStarDjApiKeyKey
import com.metrolist.music.constants.RadioStarDjKey
import com.metrolist.music.constants.RadioStarServerKey
import com.metrolist.music.constants.ScrobblerApiKeyKey
import com.metrolist.music.constants.ScrobblerBaseUrlKey
import com.metrolist.music.constants.ScrobblerLastFmUserKey
import com.metrolist.music.constants.ScrobblerLastResultKey
import com.metrolist.music.constants.ScrobblerListenBrainzUserKey
import com.metrolist.music.constants.ScrobblerServiceKey
import com.metrolist.music.constants.ScrobblerSyncFrequencyKey
import com.metrolist.music.constants.ScrobblerTokenKey
import com.metrolist.music.recommend.ScrobblerConfig
import com.metrolist.music.recommend.ScrobblerImporter
import com.metrolist.music.recommend.RadioStarRefreshBus
import com.metrolist.music.recommend.ScrobblerSyncFrequency
import com.metrolist.music.recommend.SecureKeyStore
import com.metrolist.music.recommend.ScrobblerService
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.launch

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
    var scrobblerService by rememberPreference(ScrobblerServiceKey, ScrobblerService.NONE.id)
    var lastFmUser by rememberPreference(ScrobblerLastFmUserKey, "")
    var listenBrainzUser by rememberPreference(ScrobblerListenBrainzUserKey, "")
    var baseUrl by rememberPreference(ScrobblerBaseUrlKey, "")
    var token by rememberPreference(ScrobblerTokenKey, "")
    var apiKey by rememberPreference(ScrobblerApiKeyKey, "")
    var syncFrequency by rememberPreference(ScrobblerSyncFrequencyKey, ScrobblerSyncFrequency.SIX_HOURS.id)
    var importStatus by rememberPreference(ScrobblerLastResultKey, "")
    var importing by remember { mutableStateOf(false) }
    var progressFetched by remember { mutableStateOf(0) }
    var progressTotal by remember { mutableStateOf<Long?>(null) }
    var showServiceDialog by remember { mutableStateOf(false) }
    var djApiCipher by rememberPreference(RadioStarDjApiKeyKey, "")
    var showCredits by remember { mutableStateOf(false) }

    var showServerDialog by remember { mutableStateOf(false) }
    var serverDraft by remember { mutableStateOf("") }
    var inputTarget by remember { mutableStateOf<String?>(null) }
    var inputDraft by remember { mutableStateOf("") }
    val service =
        ScrobblerService.values().firstOrNull { it.id == scrobblerService } ?: ScrobblerService.NONE

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
                        icon = painterResource(R.drawable.lock),
                        title = { Text("DJ AI API key") },
                        description = { Text(if (djApiCipher.isBlank()) "—" else "••••••") },
                        onClick = {
                            inputTarget = "djkey"
                            inputDraft = SecureKeyStore.decrypt(djApiCipher).orEmpty()
                        },
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

        val serviceUser = if (service == ScrobblerService.LASTFM) lastFmUser else listenBrainzUser
        Material3SettingsGroup(
            title = stringResource(R.string.radiostar_scrobbler),
            items =
                buildList {
                    add(
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.radio),
                            title = { Text(stringResource(R.string.radiostar_scrobbler_service)) },
                            description = { Text(service.label) },
                            onClick = { showServiceDialog = true },
                        ),
                    )
                    if (service == ScrobblerService.LASTFM) {
                        add(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.person),
                                title = { Text(stringResource(R.string.radiostar_scrobbler_user)) },
                                description = { Text(lastFmUser.ifBlank { "—" }) },
                                onClick = { inputTarget = "user"; inputDraft = lastFmUser },
                            ),
                        )
                        add(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.lock),
                                title = { Text(stringResource(R.string.radiostar_scrobbler_api_key)) },
                                description = { Text(if (apiKey.isBlank()) "—" else "••••••") },
                                onClick = { inputTarget = "apikey"; inputDraft = apiKey },
                            ),
                        )
                    }
                    if (service == ScrobblerService.LISTENBRAINZ || service == ScrobblerService.SELF_HOSTED) {
                        add(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.person),
                                title = { Text(stringResource(R.string.radiostar_scrobbler_user)) },
                                description = { Text(listenBrainzUser.ifBlank { "—" }) },
                                onClick = { inputTarget = "user"; inputDraft = listenBrainzUser },
                            ),
                        )
                        add(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.lock),
                                title = { Text(stringResource(R.string.radiostar_scrobbler_token)) },
                                description = { Text(if (token.isBlank()) "—" else "••••••") },
                                onClick = { inputTarget = "token"; inputDraft = token },
                            ),
                        )
                    }
                    if (service == ScrobblerService.SELF_HOSTED) {
                        add(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.cloud),
                                title = { Text(stringResource(R.string.radiostar_scrobbler_url)) },
                                description = { Text(baseUrl.ifBlank { "—" }) },
                                onClick = { inputTarget = "url"; inputDraft = baseUrl },
                            ),
                        )
                    }
                    if (service != ScrobblerService.NONE) {
                        add(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.sync),
                                title = { Text(stringResource(R.string.radiostar_scrobbler_import)) },
                                description = {
                                    Column {
                                        Text(importStatus.ifBlank { stringResource(R.string.radiostar_scrobbler_never) })
                                        if (importing) {
                                            Text("$progressFetched / ${progressTotal?.toString() ?: "?"}")
                                            val total = progressTotal
                                            if (total != null && total > 0) {
                                                LinearProgressIndicator(
                                                    progress = { (progressFetched.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                                )
                                            } else {
                                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
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
                                                    service = service,
                                                    lastFmUser = lastFmUser,
                                                    listenBrainzUser = listenBrainzUser,
                                                    baseUrl = baseUrl,
                                                    token = token,
                                                    apiKey = apiKey,
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
                    }
                    if (service != ScrobblerService.NONE) {
                        val currentFrequency =
                            ScrobblerSyncFrequency.values().firstOrNull { it.id == syncFrequency }
                                ?: ScrobblerSyncFrequency.SIX_HOURS
                        add(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.sync),
                                title = { Text("Sync frequency") },
                                description = { Text(currentFrequency.label) },
                                onClick = {
                                    val next =
                                        ScrobblerSyncFrequency.values()[
                                            (currentFrequency.ordinal + 1) % ScrobblerSyncFrequency.values().size
                                        ]
                                    syncFrequency = next.id
                                },
                            ),
                        )
                    }
                },
        )

        Spacer(modifier = Modifier.height(16.dp))

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

    if (showServiceDialog) {
        AlertDialog(
            onDismissRequest = { showServiceDialog = false },
            title = { Text(stringResource(R.string.radiostar_scrobbler_service)) },
            text = {
                Column {
                    ScrobblerService.values().forEach { option ->
                        TextButton(
                            onClick = {
                                scrobblerService = option.id
                                showServiceDialog = false
                            },
                        ) { Text(option.label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showServiceDialog = false }) {
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
                        "user" -> stringResource(R.string.radiostar_scrobbler_user)
                        "url" -> stringResource(R.string.radiostar_scrobbler_url)
                        "apikey" -> stringResource(R.string.radiostar_scrobbler_api_key)
                        "djkey" -> "DJ AI API key"
                        else -> stringResource(R.string.radiostar_scrobbler_token)
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
                            "user" -> {
                                if (service == ScrobblerService.LASTFM) {
                                    lastFmUser = inputDraft.trim()
                                } else {
                                    listenBrainzUser = inputDraft.trim()
                                }
                            }
                            "url" -> baseUrl = inputDraft.trim().trimEnd('/')
                            "token" -> token = inputDraft.trim()
                        "apikey" -> apiKey = inputDraft.trim()
                        "djkey" -> djApiCipher = SecureKeyStore.encrypt(inputDraft.trim()).orEmpty()
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
