/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.RadioStarArtworkOpacityKey
import com.metrolist.music.constants.RadioStarServerKey
import com.metrolist.music.recommend.RadioStarRefreshBus
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioStarScreen(navController: NavController) {
    val context = LocalContext.current
    val globalServer =
        remember {
            Settings.Global
                .getString(context.contentResolver, "radiostar_embed_server")
                ?.trim()
                ?.trimEnd('/')
                .orEmpty()
        }
    var server by rememberPreference(RadioStarServerKey, globalServer)
    var artworkOpacity by rememberPreference(RadioStarArtworkOpacityKey, 0.45f)
    val artworkSlider = rememberSliderState(value = artworkOpacity)
    LaunchedEffect(artworkOpacity) {
        if (artworkSlider.value != artworkOpacity) {
            artworkSlider.value = artworkOpacity
        }
    }
    var showServerDialog by remember { mutableStateOf(false) }
    var serverDraft by remember { mutableStateOf("") }

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
                            showServerDialog = true
                        },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.sync),
                        title = { Text(stringResource(R.string.radiostar_refresh_now)) },
                        onClick = { RadioStarRefreshBus.request() },
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
