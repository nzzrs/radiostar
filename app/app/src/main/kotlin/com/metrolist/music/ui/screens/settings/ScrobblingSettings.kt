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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.ScrobbleCustomTokenKey
import com.metrolist.music.constants.ScrobbleCustomUrlKey
import com.metrolist.music.constants.ScrobbleDestinationKey
import com.metrolist.music.constants.ScrobbleTokenKey
import com.metrolist.music.constants.ScrobblerTokenKey
import com.metrolist.music.recommend.ScrobbleDestination
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScrobblingSettings(navController: NavController) {
    var destinationId by rememberPreference(ScrobbleDestinationKey, ScrobbleDestination.OFF.id)
    var outboundListens by rememberPreference(
        com.metrolist.music.constants.ScrobbleOutboundListensKey,
        true,
    )
    var scrobbleToken by rememberPreference(ScrobbleTokenKey, "")
    var customUrl by rememberPreference(ScrobbleCustomUrlKey, "")
    var customToken by rememberPreference(ScrobbleCustomTokenKey, "")
    var importToken by rememberPreference(ScrobblerTokenKey, "")
    var showDestinationDialog by remember { mutableStateOf(false) }
    var inputTarget by remember { mutableStateOf<String?>(null) }
    var inputDraft by remember { mutableStateOf("") }
    val destination = ScrobbleDestination.fromId(destinationId)

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
                    add(
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.sync),
                            title = { Text(stringResource(R.string.radiostar_scrobble_outbound)) },
                            description = {
                                Text(stringResource(R.string.radiostar_scrobble_outbound_desc))
                            },
                            trailingContent = {
                                Switch(
                                    checked = outboundListens,
                                    onCheckedChange = { outboundListens = it },
                                )
                            },
                            onClick = { outboundListens = !outboundListens },
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
                            add(
                                Material3SettingsItem(
                                    icon = painterResource(R.drawable.info),
                                    title = { Text(stringResource(R.string.radiostar_lastfm_unlove_title)) },
                                    description = {
                                        Text(stringResource(R.string.radiostar_lastfm_unlove_warning))
                                    },
                                    enabled = false,
                                ),
                            )
                        }
                        ScrobbleDestination.LISTENBRAINZ ->
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

        Spacer(Modifier.height(16.dp))
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

    TopAppBar(
        title = { Text(stringResource(R.string.radiostar_scrobbling)) },
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
