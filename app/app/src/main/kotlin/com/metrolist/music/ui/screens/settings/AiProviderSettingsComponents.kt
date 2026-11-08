/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.constants.AiProviderKey
import com.metrolist.music.constants.DeeplApiKey
import com.metrolist.music.constants.OpenRouterApiKey
import com.metrolist.music.constants.OpenRouterBaseUrlKey
import com.metrolist.music.constants.OpenRouterDefaultBaseUrl
import com.metrolist.music.constants.OpenRouterDefaultModel
import com.metrolist.music.constants.OpenRouterModelKey
import com.metrolist.music.recommend.AiFeature
import com.metrolist.music.recommend.AiProviderCatalog
import com.metrolist.music.recommend.AiProviderClient
import com.metrolist.music.recommend.AiProviderResolver
import com.metrolist.music.recommend.AiResolvedConfig
import com.metrolist.music.recommend.AiTtsClient
import com.metrolist.music.recommend.SecureKeyStore
import com.metrolist.music.ui.component.EnumDialog
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.TextFieldDialog
import com.metrolist.music.ui.component.rememberAiResolvedConfig
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.launch

/** Global provider and key used by every feature with the default switch on. */
@Composable
fun AiDefaultProviderGroup() {
    var provider by rememberPreference(AiProviderKey, AiProviderResolver.DEFAULT_PROVIDER)
    var baseUrl by rememberPreference(OpenRouterBaseUrlKey, OpenRouterDefaultBaseUrl)
    var model by rememberPreference(OpenRouterModelKey, OpenRouterDefaultModel)
    var openRouterKey by rememberPreference(OpenRouterApiKey, "")
    var deeplKey by rememberPreference(DeeplApiKey, "")

    var showProviderDialog by rememberSaveable { mutableStateOf(false) }
    var showProviderHelpDialog by rememberSaveable { mutableStateOf(false) }
    var showBaseUrlDialog by rememberSaveable { mutableStateOf(false) }
    var showKeyDialog by rememberSaveable { mutableStateOf(false) }
    var showModelDialog by rememberSaveable { mutableStateOf(false) }
    var showCustomModel by rememberSaveable { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val usesDeepL = provider == "DeepL"
    val apiKey = if (usesDeepL) deeplKey else openRouterKey

    if (showProviderHelpDialog) {
        AiProviderHelpDialog(onDismiss = { showProviderHelpDialog = false })
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
    if (showBaseUrlDialog) {
        TextFieldDialog(
            title = { Text(stringResource(R.string.ai_base_url)) },
            icon = { Icon(painterResource(R.drawable.link), null) },
            initialTextFieldValue = TextFieldValue(text = baseUrl),
            onDone = {
                baseUrl = it.trim()
                testResult = null
                showBaseUrlDialog = false
            },
            onDismiss = { showBaseUrlDialog = false },
        )
    }
    if (showKeyDialog) {
        TextFieldDialog(
            title = {
                Text(if (usesDeepL) "DeepL ${stringResource(R.string.ai_api_key)}" else stringResource(R.string.ai_api_key))
            },
            icon = { Icon(painterResource(R.drawable.key), null) },
            initialTextFieldValue = TextFieldValue(text = apiKey),
            onDone = {
                if (usesDeepL) deeplKey = it.trim() else openRouterKey = it.trim()
                testResult = null
                showKeyDialog = false
            },
            onDismiss = { showKeyDialog = false },
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
                model = it.trim()
                showCustomModel = false
            },
            onDismiss = { showCustomModel = false },
        )
    }

    Material3SettingsGroup(
        title = stringResource(R.string.ai_default_provider),
        items =
            buildList {
                add(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.explore_outlined),
                        title = { Text(stringResource(R.string.ai_provider)) },
                        description = { Text(provider) },
                        onClick = { showProviderDialog = true },
                        trailingContent = {
                            IconButton(onClick = { showProviderHelpDialog = true }) {
                                Icon(
                                    painterResource(R.drawable.info),
                                    contentDescription = stringResource(R.string.ai_provider_help),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        },
                    ),
                )
                if (provider == "Custom") {
                    add(
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.link),
                            title = { Text(stringResource(R.string.ai_base_url)) },
                            description = { Text(baseUrl.ifBlank { stringResource(R.string.not_set) }) },
                            onClick = { showBaseUrlDialog = true },
                        ),
                    )
                }
                add(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.key),
                        title = {
                            Text(if (usesDeepL) "DeepL ${stringResource(R.string.ai_api_key)}" else stringResource(R.string.ai_api_key))
                        },
                        description = {
                            Text(if (apiKey.isBlank()) stringResource(R.string.not_set) else "•".repeat(minOf(apiKey.length, 8)))
                        },
                        onClick = { showKeyDialog = true },
                    ),
                )
                if (!usesDeepL) {
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
                            icon = painterResource(R.drawable.sync),
                            title = { Text(stringResource(R.string.ai_test_connection)) },
                            description = if (testResult != null) {
                                { Text(testResult.orEmpty()) }
                            } else {
                                null
                            },
                            trailingContent = {
                                if (testing) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                }
                            },
                            onClick = {
                                if (!testing) {
                                    testing = true
                                    testResult = null
                                    scope.launch {
                                        testResult =
                                            AiProviderClient
                                                .testConnection(provider, baseUrl, model, apiKey)
                                                .message
                                        testing = false
                                    }
                                }
                            },
                        ),
                    )
                }
            },
    )
}

/**
 * Provider rows for one feature: default/custom switch, key, base URL, model,
 * and a connection test. The model stays editable even with the default on.
 */
@Composable
fun AiFeatureConfigSection(
    feature: AiFeature,
    title: String,
    leadingItems: List<Material3SettingsItem> = emptyList(),
    extraItems: List<Material3SettingsItem> = emptyList(),
) {
    val resolved = rememberAiResolvedConfig(feature)
    var useDefault by rememberPreference(AiProviderResolver.useDefaultKey(feature), true)
    var provider by rememberPreference(AiProviderResolver.providerKey(feature), AiProviderResolver.defaultProvider(feature))
    var baseUrl by rememberPreference(AiProviderResolver.baseUrlKey(feature), AiProviderResolver.defaultBaseUrl(feature))
    var model by rememberPreference(AiProviderResolver.modelKey(feature), "")
    var rawKey by rememberPreference(AiProviderResolver.apiKeyKey(feature), "")
    var deeplKey by rememberPreference(DeeplApiKey, "")

    val encrypted = AiProviderResolver.encryptedKey(feature)
    val featureKey =
        remember(rawKey, encrypted) {
            if (encrypted) SecureKeyStore.decrypt(rawKey).orEmpty() else rawKey
        }
    val effectiveProvider = if (useDefault) resolved.provider else provider
    val effectiveBaseUrl = if (useDefault) resolved.baseUrl else baseUrl
    val effectiveModel =
        model.ifBlank {
            if (useDefault) resolved.model else AiProviderResolver.defaultModel(feature)
        }
    val usesDeepLKey = effectiveProvider == "DeepL"
    val effectiveKey =
        when {
            useDefault -> resolved.apiKey
            usesDeepLKey -> deeplKey
            else -> featureKey
        }

    var showProviderDialog by rememberSaveable { mutableStateOf(false) }
    var showBaseUrlDialog by rememberSaveable { mutableStateOf(false) }
    var showKeyDialog by rememberSaveable { mutableStateOf(false) }
    var showModelDialog by rememberSaveable { mutableStateOf(false) }
    var showCustomModel by rememberSaveable { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    if (showProviderDialog) {
        EnumDialog(
            onDismiss = { showProviderDialog = false },
            onSelect = { selected ->
                provider = selected
                baseUrl =
                    when {
                        selected == "Custom" -> ""
                        feature == AiFeature.TTS -> AiProviderCatalog.ttsBaseUrl(selected)
                        else -> AiProviderCatalog.baseUrl(selected)
                    }
                model = featureModels(feature, selected).firstOrNull().orEmpty()
                testResult = null
                showProviderDialog = false
            },
            title = stringResource(R.string.ai_provider),
            current = provider,
            values = featureProviders(feature),
            valueText = { it },
        )
    }
    if (showBaseUrlDialog) {
        TextFieldDialog(
            title = { Text(stringResource(R.string.ai_base_url)) },
            icon = { Icon(painterResource(R.drawable.link), null) },
            initialTextFieldValue = TextFieldValue(text = baseUrl),
            onDone = {
                baseUrl = it.trim()
                testResult = null
                showBaseUrlDialog = false
            },
            onDismiss = { showBaseUrlDialog = false },
        )
    }
    if (showKeyDialog) {
        TextFieldDialog(
            title = {
                Text(if (usesDeepLKey) "DeepL ${stringResource(R.string.ai_api_key)}" else stringResource(R.string.ai_api_key))
            },
            icon = { Icon(painterResource(R.drawable.key), null) },
            initialTextFieldValue = TextFieldValue(text = if (usesDeepLKey) deeplKey else featureKey),
            onDone = {
                if (usesDeepLKey) {
                    deeplKey = it.trim()
                } else if (encrypted) {
                    rawKey = it.trim().takeIf(String::isNotBlank)?.let(SecureKeyStore::encrypt).orEmpty()
                } else {
                    rawKey = it.trim()
                }
                testResult = null
                showKeyDialog = false
            },
            onDismiss = { showKeyDialog = false },
        )
    }
    if (showModelDialog) {
        AiModelPickerDialog(
            provider = effectiveProvider,
            baseUrl = effectiveBaseUrl,
            apiKey = effectiveKey,
            current = effectiveModel,
            fallbackModels = featureModels(feature, effectiveProvider),
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
                model = it.trim()
                showCustomModel = false
            },
            onDismiss = { showCustomModel = false },
        )
    }

    Material3SettingsGroup(
        title = title,
        items =
            leadingItems +
                buildList {
                    add(
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.tune),
                            title = { Text(stringResource(R.string.ai_use_default)) },
                            description = {
                                Text(
                                    if (useDefault) {
                                        stringResource(R.string.ai_using_default, resolved.provider)
                                    } else {
                                        stringResource(R.string.ai_custom_provider)
                                    },
                                )
                            },
                            trailingContent = {
                                Switch(checked = useDefault, onCheckedChange = { useDefault = it })
                            },
                            onClick = { useDefault = !useDefault },
                        ),
                    )
                    if (!useDefault) {
                        add(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.explore_outlined),
                                title = { Text(stringResource(R.string.ai_provider)) },
                                description = { Text(effectiveProvider) },
                                onClick = { showProviderDialog = true },
                            ),
                        )
                        if (provider == "Custom") {
                            add(
                                Material3SettingsItem(
                                    icon = painterResource(R.drawable.link),
                                    title = { Text(stringResource(R.string.ai_base_url)) },
                                    description = { Text(baseUrl.ifBlank { stringResource(R.string.not_set) }) },
                                    onClick = { showBaseUrlDialog = true },
                                ),
                            )
                        }
                        add(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.key),
                                title = {
                                    Text(
                                        if (usesDeepLKey) {
                                            "DeepL ${stringResource(R.string.ai_api_key)}"
                                        } else {
                                            stringResource(R.string.ai_api_key)
                                        },
                                    )
                                },
                                description = {
                                    val key = if (usesDeepLKey) deeplKey else featureKey
                                    Text(if (key.isBlank()) stringResource(R.string.not_set) else "•".repeat(minOf(key.length, 8)))
                                },
                                onClick = { showKeyDialog = true },
                            ),
                        )
                    }
                    if (!usesDeepLKey) {
                        add(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.discover_tune),
                                title = { Text(stringResource(R.string.ai_model)) },
                                description = {
                                    Text(
                                        model.ifBlank {
                                            stringResource(R.string.ai_default_model_value, effectiveModel)
                                        },
                                    )
                                },
                                onClick = { showModelDialog = true },
                            ),
                        )
                        add(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.sync),
                                title = { Text(stringResource(R.string.ai_test_connection)) },
                                description = if (testResult != null) {
                                    { Text(testResult.orEmpty()) }
                                } else {
                                    null
                                },
                                trailingContent = {
                                    if (testing) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    }
                                },
                                onClick = {
                                    if (!testing) {
                                        testing = true
                                        testResult = null
                                        scope.launch {
                                            testResult =
                                                if (feature == AiFeature.TTS) {
                                                    AiProviderClient
                                                        .listModels(effectiveProvider, effectiveBaseUrl, effectiveKey)
                                                        .message
                                                } else {
                                                    AiProviderClient
                                                        .testConnection(effectiveProvider, effectiveBaseUrl, effectiveModel, effectiveKey)
                                                        .message
                                                }
                                            testing = false
                                        }
                                    }
                                },
                            ),
                        )
                    }
                } +
                extraItems,
    )
}

/** Voice picker: lists the speech service voices, falling back to the OpenAI set. */
@Composable
fun AiVoicePickerDialog(
    config: AiResolvedConfig,
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var voices by remember { mutableStateOf(emptyList<String>()) }
    var lookupFailed by remember { mutableStateOf(false) }

    LaunchedEffect(config) {
        loading = true
        lookupFailed = false
        val found = AiTtsClient.listVoices(config)
        val gemini = AiTtsClient.isGemini(config)
        lookupFailed = found.isEmpty() && gemini
        voices = found.ifEmpty { AiTtsClient.fallbackVoices(if (gemini) "Gemini" else config.provider) }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        icon = { Icon(painterResource(R.drawable.radio), null) },
        title = { Text(stringResource(R.string.ai_voice)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.ai_voice_loading))
                    }
                } else {
                    if (lookupFailed) {
                        Text(
                            text = stringResource(R.string.ai_voice_lookup_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    voices.forEach { voice ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSelect(voice)
                                        onDismiss()
                                    }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = voice == current,
                                onClick = {
                                    onSelect(voice)
                                    onDismiss()
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(voice)
                        }
                    }
                }
            }
        },
    )
}

private fun featureProviders(feature: AiFeature): List<String> =
    AiProviderCatalog.baseUrls.keys.filter { feature == AiFeature.TRANSLATION || it != "DeepL" }

private fun featureModels(
    feature: AiFeature,
    provider: String,
): List<String> =
    if (feature == AiFeature.TTS) {
        AiTtsClient.modelSuggestions(provider)
    } else {
        AiProviderCatalog.fallbackModels(provider)
    }

@Composable
private fun AiProviderHelpDialog(onDismiss: () -> Unit) {
    val helpText =
        mapOf(
            "OpenRouter" to stringResource(R.string.ai_provider_openrouter_help),
            "OpenAI" to stringResource(R.string.ai_provider_openai_help),
            "Perplexity" to stringResource(R.string.ai_provider_perplexity_help),
            "Claude" to stringResource(R.string.ai_provider_claude_help),
            "Gemini" to stringResource(R.string.ai_provider_gemini_help),
            "XAi" to stringResource(R.string.ai_provider_xai_help),
            "Mistral" to stringResource(R.string.ai_provider_mistral_help),
            "Inception" to stringResource(R.string.ai_provider_inception_help),
            "DeepL" to stringResource(R.string.ai_provider_deepl_help),
            "Custom" to "",
        )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        },
        icon = { Icon(painterResource(R.drawable.info), null) },
        title = { Text(stringResource(R.string.ai_provider_help)) },
        text = {
            Column {
                helpText.forEach { (provider, help) ->
                    if (help.isNotEmpty()) {
                        val primaryColor = MaterialTheme.colorScheme.primary
                        val annotatedString =
                            buildAnnotatedString {
                                append("$provider: ")
                                val urlRegex = "https?://[^\\s]+".toRegex()
                                val match = urlRegex.find(help)
                                if (match != null) {
                                    val url = match.value
                                    append(help.substring(0, match.range.first))
                                    val linkStart = length
                                    append(url)
                                    val linkEnd = length
                                    append(help.substring(match.range.last + 1))
                                    addLink(
                                        LinkAnnotation.Url(
                                            url = url,
                                            styles =
                                                TextLinkStyles(
                                                    style =
                                                        SpanStyle(
                                                            color = primaryColor,
                                                            textDecoration = TextDecoration.Underline,
                                                        ),
                                                ),
                                        ),
                                        start = linkStart,
                                        end = linkEnd,
                                    )
                                } else {
                                    append(help)
                                }
                            }
                        Text(
                            text = annotatedString,
                            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            }
        },
    )
}
