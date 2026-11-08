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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.RADIOSTAR_DEFAULT_DJ_BASE_URL
import com.metrolist.music.constants.RADIOSTAR_DEFAULT_DJ_MODEL
import com.metrolist.music.constants.RADIOSTAR_DEFAULT_DJ_PROVIDER
import com.metrolist.music.constants.RadioStarDjApiKeyKey
import com.metrolist.music.constants.RadioStarDjBaseUrlKey
import com.metrolist.music.constants.RadioStarDjKey
import com.metrolist.music.constants.RadioStarDjModelKey
import com.metrolist.music.constants.RadioStarDjProviderKey
import com.metrolist.music.recommend.AiProviderCatalog
import com.metrolist.music.recommend.AiProviderClient
import com.metrolist.music.recommend.SecureKeyStore
import com.metrolist.music.ui.component.EnumDialog
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.TextFieldDialog
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DjSettingsScreen(navController: NavController) {
    var enabled by rememberPreference(RadioStarDjKey, true)
    var provider by rememberPreference(RadioStarDjProviderKey, RADIOSTAR_DEFAULT_DJ_PROVIDER)
    var baseUrl by rememberPreference(RadioStarDjBaseUrlKey, RADIOSTAR_DEFAULT_DJ_BASE_URL)
    var model by rememberPreference(RadioStarDjModelKey, RADIOSTAR_DEFAULT_DJ_MODEL)
    var keyCipher by rememberPreference(RadioStarDjApiKeyKey, "")
    val apiKey = remember(keyCipher) { SecureKeyStore.decrypt(keyCipher).orEmpty() }
    val scope = rememberCoroutineScope()

    var showProviderDialog by remember { mutableStateOf(false) }
    var showBaseDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showCustomModel by remember { mutableStateOf(false) }
    var showKeyDialog by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

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
            title = stringResource(R.string.radiostar_dj),
            items =
                listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.radio),
                        title = { Text(stringResource(R.string.radiostar_dj)) },
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
                title = stringResource(R.string.radiostar_dj_ai),
                items =
                    buildList {
                        add(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.explore_outlined),
                                title = { Text(stringResource(R.string.ai_provider)) },
                                description = { Text(provider) },
                                onClick = { showProviderDialog = true },
                            ),
                        )
                        if (provider == "Custom") {
                            add(
                                Material3SettingsItem(
                                    icon = painterResource(R.drawable.link),
                                    title = { Text(stringResource(R.string.ai_base_url)) },
                                    description = { Text(baseUrl.ifBlank { stringResource(R.string.not_set) }) },
                                    onClick = { showBaseDialog = true },
                                ),
                            )
                        }
                        add(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.discover_tune),
                                title = { Text(stringResource(R.string.ai_model)) },
                                description = { Text(model.ifBlank { stringResource(R.string.not_set) }) },
                                onClick = { showModelDialog = true },
                            ),
                        )
                        add(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.key),
                                title = { Text(stringResource(R.string.radiostar_dj_ai_key)) },
                                description = {
                                    Text(if (keyCipher.isBlank()) stringResource(R.string.not_set) else "••••••")
                                },
                                onClick = { showKeyDialog = true },
                            ),
                        )
                        add(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.sync),
                                title = { Text(stringResource(R.string.ai_test_connection)) },
                                description =
                                    if (testResult != null) {
                                        { Text(testResult.orEmpty()) }
                                    } else {
                                        null
                                    },
                                trailingContent = {
                                    if (testing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    }
                                },
                                onClick = {
                                    if (!testing) {
                                        testing = true
                                        testResult = null
                                        scope.launch {
                                            val result =
                                                AiProviderClient.testConnection(
                                                    provider = provider,
                                                    baseUrl = baseUrl,
                                                    model = model,
                                                    apiKey = apiKey,
                                                )
                                            testResult = result.message
                                            testing = false
                                        }
                                    }
                                },
                            ),
                        )
                    },
            )
        }

        Spacer(Modifier.height(16.dp))
    }

    if (showProviderDialog) {
        EnumDialog(
            onDismiss = { showProviderDialog = false },
            onSelect = { selected ->
                provider = selected
                baseUrl = AiProviderCatalog.baseUrl(selected)
                model = AiProviderCatalog.fallbackModels(selected).firstOrNull().orEmpty()
                testResult = null
                showProviderDialog = false
            },
            title = stringResource(R.string.ai_provider),
            current = provider,
            values = AiProviderCatalog.baseUrls.keys.toList(),
            valueText = { it },
        )
    }

    if (showModelDialog) {
        AiModelPickerDialog(
            provider = provider,
            baseUrl = baseUrl,
            apiKey = apiKey,
            current = model,
            fallbackModels = AiProviderCatalog.fallbackModels(provider),
            onSelect = {
                model = it
                testResult = null
                showModelDialog = false
            },
            onCustom = {
                showModelDialog = false
                showCustomModel = true
            },
            onDismiss = { showModelDialog = false },
        )
    }

    if (showCustomModel) {
        TextFieldDialog(
            title = { Text(stringResource(R.string.ai_model)) },
            icon = { Icon(painterResource(R.drawable.discover_tune), null) },
            initialTextFieldValue = TextFieldValue(text = model),
            onDone = {
                model = it
                showCustomModel = false
            },
            onDismiss = { showCustomModel = false },
        )
    }

    if (showBaseDialog) {
        TextFieldDialog(
            title = { Text(stringResource(R.string.ai_base_url)) },
            icon = { Icon(painterResource(R.drawable.link), null) },
            initialTextFieldValue = TextFieldValue(text = baseUrl),
            onDone = {
                baseUrl = it.trim().trimEnd('/')
                testResult = null
                showBaseDialog = false
            },
            onDismiss = { showBaseDialog = false },
        )
    }

    if (showKeyDialog) {
        TextFieldDialog(
            title = { Text(stringResource(R.string.radiostar_dj_ai_key)) },
            icon = { Icon(painterResource(R.drawable.key), null) },
            initialTextFieldValue = TextFieldValue(text = apiKey),
            onDone = {
                keyCipher = if (it.isBlank()) "" else SecureKeyStore.encrypt(it.trim()).orEmpty()
                testResult = null
                showKeyDialog = false
            },
            onDismiss = { showKeyDialog = false },
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.radiostar_dj)) },
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
