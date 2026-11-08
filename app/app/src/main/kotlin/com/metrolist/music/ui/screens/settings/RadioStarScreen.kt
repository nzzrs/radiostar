/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.ListenTogetherServerUrlKey
import com.metrolist.music.constants.RADIOSTAR_DEFAULT_SERVER
import com.metrolist.music.constants.RADIOSTAR_LISTEN_TOGETHER_URL
import com.metrolist.music.constants.RADIOSTAR_SHARE_BASE_URL
import com.metrolist.music.constants.RadioStarArtworkOpacityKey
import com.metrolist.music.constants.RadioStarServerKey
import com.metrolist.music.constants.RadioStarShareBaseUrlKey
import com.metrolist.music.constants.RadioStarSyncEnabledKey
import com.metrolist.music.constants.RadioStarSyncLastResultKey
import com.metrolist.music.constants.RadioStarSyncMirrorScrobblerKey
import com.metrolist.music.constants.RadioStarSyncMirrorYoutubeKey
import com.metrolist.music.recommend.RadioStarRefreshBus
import com.metrolist.music.recommend.RadioStarSync
import com.metrolist.music.recommend.ServerAuthClient
import com.metrolist.music.recommend.ServerAuthStore
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RadioStarAuthEntryPoint {
    fun serverAuthStore(): ServerAuthStore

    fun radioStarSync(): RadioStarSync
}

private enum class AuthMode {
    CODE,
    PASSWORD,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioStarScreen(navController: NavController) {
    val context = LocalContext.current
    val auth =
        remember {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                RadioStarAuthEntryPoint::class.java,
            ).serverAuthStore()
        }
    val radioStarSync =
        remember {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                RadioStarAuthEntryPoint::class.java,
            ).radioStarSync()
        }
    val scope = rememberCoroutineScope()
    var syncEnabled by rememberPreference(RadioStarSyncEnabledKey, false)
    var syncMirrorYoutube by rememberPreference(RadioStarSyncMirrorYoutubeKey, true)
    var syncMirrorScrobbler by rememberPreference(RadioStarSyncMirrorScrobblerKey, true)
    val lastSyncResult by rememberPreference(RadioStarSyncLastResultKey, "")
    var shareBaseUrl by rememberPreference(RadioStarShareBaseUrlKey, RADIOSTAR_SHARE_BASE_URL)
    var listenTogetherUrl by rememberPreference(ListenTogetherServerUrlKey, RADIOSTAR_LISTEN_TOGETHER_URL)
    var hasPassphrase by remember { mutableStateOf(false) }
    var syncBusy by remember { mutableStateOf(false) }
    var showPassphraseDialog by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var showListenTogetherDialog by remember { mutableStateOf(false) }
    var infraDraft by remember { mutableStateOf("") }
    var passphraseDraft by remember { mutableStateOf("") }
    var passphraseError by remember { mutableStateOf<String?>(null) }
    val globalServer =
        remember {
            Settings.Global
                .getString(context.contentResolver, "radiostar_embed_server")
                ?.trim()
                ?.trimEnd('/')
                .orEmpty()
        }
    var server by rememberPreference(RadioStarServerKey, globalServer.ifBlank { RADIOSTAR_DEFAULT_SERVER })
    var artworkOpacity by rememberPreference(RadioStarArtworkOpacityKey, 0.45f)
    val artworkSlider = rememberSliderState(value = artworkOpacity)
    var authStatus by remember { mutableStateOf<ServerAuthStore.Status?>(null) }
    var showServerDialog by remember { mutableStateOf(false) }
    var serverDraft by remember { mutableStateOf("") }
    var serverError by remember { mutableStateOf<String?>(null) }
    var showAuthDialog by remember { mutableStateOf(false) }

    LaunchedEffect(server) {
        authStatus = auth.status(server)
        hasPassphrase = runCatching { radioStarSync.hasPassphrase() }.getOrDefault(false)
    }
    LaunchedEffect(showPassphraseDialog) {
        if (showPassphraseDialog) {
            hasPassphrase = runCatching { radioStarSync.hasPassphrase() }.getOrDefault(false)
        }
    }
    LaunchedEffect(artworkOpacity) {
        if (artworkSlider.value != artworkOpacity) {
            artworkSlider.value = artworkOpacity
        }
    }

    val accountDescription =
        when {
            server.isBlank() -> stringResource(R.string.radiostar_account_no_server)
            authStatus?.linked == true ->
                stringResource(
                    R.string.radiostar_account_linked,
                    authStatus?.username.orEmpty(),
                    authStatus?.deviceName.orEmpty(),
                )
            else -> stringResource(R.string.radiostar_account_not_linked)
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

        Material3SettingsGroup(
            title = stringResource(R.string.radiostar_engine),
            items =
                listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.radio),
                        title = { Text(stringResource(R.string.radiostar_server)) },
                        description = {
                            Text(server.ifBlank { stringResource(R.string.radiostar_server_empty) })
                        },
                        onClick = {
                            serverDraft = server
                            serverError = null
                            showServerDialog = true
                        },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.person),
                        title = { Text(stringResource(R.string.radiostar_account)) },
                        description = { Text(accountDescription) },
                        onClick = { showAuthDialog = true },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.sync),
                        title = { Text(stringResource(R.string.radiostar_refresh_now)) },
                        onClick = { RadioStarRefreshBus.request() },
                    ),
                ),
        )

        Spacer(Modifier.height(16.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.radiostar_sync),
            items =
                listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.sync),
                        title = { Text(stringResource(R.string.radiostar_sync_enable)) },
                        description = {
                            Text(
                                when {
                                    !syncEnabled -> stringResource(R.string.radiostar_sync_enable_desc)
                                    lastSyncResult.isNotBlank() -> lastSyncResult
                                    else -> stringResource(R.string.radiostar_sync_enable_desc)
                                },
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = syncEnabled,
                                onCheckedChange = {
                                    syncEnabled = it
                                    scope.launch {
                                        runCatching { radioStarSync.reschedule() }
                                        if (it) {
                                            syncBusy = true
                                            runCatching { radioStarSync.syncNow(force = true) }
                                            syncBusy = false
                                        }
                                    }
                                },
                            )
                        },
                        onClick = {
                            val next = !syncEnabled
                            syncEnabled = next
                            scope.launch {
                                runCatching { radioStarSync.reschedule() }
                                if (next) {
                                    syncBusy = true
                                    runCatching { radioStarSync.syncNow(force = true) }
                                    syncBusy = false
                                }
                            }
                        },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.lock),
                        title = { Text(stringResource(R.string.radiostar_sync_passphrase)) },
                        description = {
                            Text(
                                if (hasPassphrase) {
                                    stringResource(R.string.radiostar_sync_passphrase_set)
                                } else {
                                    stringResource(R.string.radiostar_sync_passphrase_missing)
                                },
                            )
                        },
                        onClick = {
                            infraDraft = ""
                            showPassphraseDialog = true
                        },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.sync),
                        title = { Text(stringResource(R.string.radiostar_sync_now)) },
                        description = { Text(if (syncBusy) "…" else lastSyncResult) },
                        onClick = {
                            if (!syncBusy) {
                                scope.launch {
                                    syncBusy = true
                                    runCatching { radioStarSync.syncNow(force = true) }
                                    syncBusy = false
                                }
                            }
                        },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.favorite),
                        title = { Text(stringResource(R.string.radiostar_sync_mirror_youtube)) },
                        description = { Text(stringResource(R.string.radiostar_sync_mirror_youtube_desc)) },
                        trailingContent = {
                            Switch(
                                checked = syncMirrorYoutube,
                                onCheckedChange = { syncMirrorYoutube = it },
                            )
                        },
                        onClick = { syncMirrorYoutube = !syncMirrorYoutube },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.thumb_down),
                        title = { Text(stringResource(R.string.radiostar_sync_mirror_scrobbler)) },
                        description = { Text(stringResource(R.string.radiostar_sync_mirror_scrobbler_desc)) },
                        trailingContent = {
                            Switch(
                                checked = syncMirrorScrobbler,
                                onCheckedChange = { syncMirrorScrobbler = it },
                            )
                        },
                        onClick = { syncMirrorScrobbler = !syncMirrorScrobbler },
                    ),
                ),
        )

        Spacer(Modifier.height(16.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.radiostar_infra),
            items =
                listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.share),
                        title = { Text(stringResource(R.string.radiostar_share_base)) },
                        description = { Text(shareBaseUrl) },
                        onClick = {
                            infraDraft = shareBaseUrl
                            showShareDialog = true
                        },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.cloud),
                        title = { Text(stringResource(R.string.radiostar_listen_together_server)) },
                        description = { Text(listenTogetherUrl) },
                        onClick = {
                            infraDraft = listenTogetherUrl
                            showListenTogetherDialog = true
                        },
                    ),
                ),
        )

        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier =
                            Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.palette),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(Modifier.size(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.radiostar_artwork),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.radiostar_artwork_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = "${(artworkOpacity * 100).toInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    state = artworkSlider,
                    onValueChange = { artworkOpacity = it },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    if (showServerDialog) {
        AlertDialog(
            onDismissRequest = { showServerDialog = false },
            title = { Text(stringResource(R.string.radiostar_server)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = serverDraft,
                        onValueChange = {
                            serverDraft = it
                            serverError = null
                        },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.radiostar_server_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    serverError?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val normalized = ServerAuthStore.normalizeServer(serverDraft)
                        if (normalized == null) {
                            serverError = context.getString(R.string.radiostar_server_invalid)
                        } else {
                            if (normalized != server) {
                                scope.launch { auth.clearLocal() }
                            }
                            server = normalized
                            showServerDialog = false
                        }
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

    if (showPassphraseDialog) {
        AlertDialog(
            onDismissRequest = { showPassphraseDialog = false },
            title = { Text(stringResource(R.string.radiostar_sync_passphrase)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.radiostar_sync_passphrase_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = passphraseDraft,
                        onValueChange = {
                            passphraseDraft = it
                            passphraseError = null
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    passphraseError?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = passphraseDraft.length >= 8 && !syncBusy,
                    onClick = {
                        syncBusy = true
                        passphraseError = null
                        scope.launch {
                            val result =
                                runCatching { radioStarSync.setPassphrase(passphraseDraft) }
                                    .getOrElse {
                                        RadioStarSync.Result(
                                            false,
                                            it.message ?: "Could not set the passphrase",
                                        )
                                    }
                            syncBusy = false
                            if (result.ok) {
                                hasPassphrase = true
                                showPassphraseDialog = false
                            } else {
                                passphraseError = result.message
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.radiostar_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPassphraseDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showShareDialog) {
        AlertDialog(
            onDismissRequest = { showShareDialog = false },
            title = { Text(stringResource(R.string.radiostar_share_base)) },
            text = {
                OutlinedTextField(
                    value = infraDraft,
                    onValueChange = { infraDraft = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        shareBaseUrl = infraDraft.trim().trimEnd('/')
                        showShareDialog = false
                    },
                ) {
                    Text(stringResource(R.string.radiostar_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showShareDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showListenTogetherDialog) {
        AlertDialog(
            onDismissRequest = { showListenTogetherDialog = false },
            title = { Text(stringResource(R.string.radiostar_listen_together_server)) },
            text = {
                OutlinedTextField(
                    value = infraDraft,
                    onValueChange = { infraDraft = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val value = infraDraft.trim()
                        if (value.isNotBlank()) {
                            listenTogetherUrl = value
                        }
                        showListenTogetherDialog = false
                    },
                ) {
                    Text(stringResource(R.string.radiostar_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showListenTogetherDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showAuthDialog) {
        ServerAuthDialog(
            server = server,
            auth = auth,
            linked = authStatus?.linked == true,
            onDismiss = { showAuthDialog = false },
            onChanged = {
                scope.launch { authStatus = auth.status(server) }
            },
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.radiostar_engine)) },
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
private fun ServerAuthDialog(
    server: String,
    auth: ServerAuthStore,
    linked: Boolean,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(AuthMode.CODE) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf(Build.MODEL.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pairingCode by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (server.isBlank()) {
            error = authErrorText(context, null)
            return
        }
        busy = true
        error = null
        scope.launch {
            val result =
                if (mode == AuthMode.CODE) {
                    auth.pair(server, code, deviceName)
                } else {
                    auth.login(server, username, password, deviceName)
                }
            busy = false
            result
                .onSuccess {
                    onChanged()
                    onDismiss()
                }.onFailure {
                    error = authErrorText(context, it)
                }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.radiostar_account)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                if (server.isBlank()) {
                    Text(
                        text = stringResource(R.string.radiostar_account_no_server),
                        color = MaterialTheme.colorScheme.error,
                    )
                    return@Column
                }
                Text(
                    text = server,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (linked) {
                    Text(
                        text = stringResource(R.string.radiostar_account_linked_hint),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    pairingCode?.let {
                        OutlinedTextField(
                            value = it,
                            onValueChange = {},
                            readOnly = true,
                            singleLine = true,
                            label = { Text(stringResource(R.string.radiostar_pairing_code)) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                val result = auth.createPairingCode(server)
                                busy = false
                                result
                                    .onSuccess { pairingCode = it.code }
                                    .onFailure { error = authErrorText(context, it) }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.radiostar_create_pairing_code))
                    }
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                auth.logout(server)
                                busy = false
                                onChanged()
                                onDismiss()
                            }
                        },
                    ) {
                        Text(stringResource(R.string.radiostar_logout))
                    }
                    return@Column
                }

                Row {
                    FilterChip(
                        selected = mode == AuthMode.CODE,
                        onClick = { mode = AuthMode.CODE },
                        label = { Text(stringResource(R.string.radiostar_auth_code)) },
                    )
                    FilterChip(
                        selected = mode == AuthMode.PASSWORD,
                        onClick = { mode = AuthMode.PASSWORD },
                        label = { Text(stringResource(R.string.radiostar_auth_password)) },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (mode == AuthMode.CODE) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.radiostar_pairing_code)) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                } else {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.radiostar_username)) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.radiostar_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = { deviceName = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.radiostar_device_name)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(top = 12.dp).size(24.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (!linked && server.isNotBlank()) {
                TextButton(
                    enabled = !busy,
                    onClick = ::submit,
                ) {
                    Text(stringResource(R.string.radiostar_auth_connect))
                }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !busy,
                onClick = onDismiss,
            ) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

private fun authErrorText(context: android.content.Context, error: Throwable?): String =
    when (error) {
        is ServerAuthClient.AuthException ->
            if (error.network) {
                context.getString(R.string.radiostar_auth_network_error)
            } else {
                error.message ?: context.getString(R.string.radiostar_auth_failed)
            }
        null -> context.getString(R.string.radiostar_auth_set_server)
        else -> error.message ?: context.getString(R.string.radiostar_auth_failed)
    }
