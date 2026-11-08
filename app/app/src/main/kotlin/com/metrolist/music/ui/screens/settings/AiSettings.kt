/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.AiSystemPromptKey
import com.metrolist.music.constants.DEFAULT_AI_SYSTEM_PROMPT
import com.metrolist.music.constants.DeeplFormalityKey
import com.metrolist.music.constants.LanguageCodeToName
import com.metrolist.music.constants.TranslateLanguageKey
import com.metrolist.music.constants.TranslateModeKey
import com.metrolist.music.recommend.AiFeature
import com.metrolist.music.ui.component.EnumDialog
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.TextFieldDialog
import com.metrolist.music.ui.component.rememberAiResolvedConfig
import com.metrolist.music.utils.rememberPreference

/** Translation behavior only; providers and keys live in the AI providers screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettings(navController: NavController) {
    var translateLanguage by rememberPreference(TranslateLanguageKey, "en")
    var translateMode by rememberPreference(TranslateModeKey, "Literal")
    var deeplFormality by rememberPreference(DeeplFormalityKey, "default")
    var aiSystemPrompt by rememberPreference(AiSystemPromptKey, "")
    val translation = rememberAiResolvedConfig(AiFeature.TRANSLATION)

    var showTranslateModeDialog by rememberSaveable { mutableStateOf(false) }
    var showTranslateModeHelpDialog by rememberSaveable { mutableStateOf(false) }
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    var showDeeplFormalityDialog by rememberSaveable { mutableStateOf(false) }
    var showSystemPromptDialog by rememberSaveable { mutableStateOf(false) }

    if (showTranslateModeHelpDialog) {
        AlertDialog(
            onDismissRequest = { showTranslateModeHelpDialog = false },
            confirmButton = {
                TextButton(onClick = { showTranslateModeHelpDialog = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            icon = { Icon(painterResource(R.drawable.info), null) },
            title = { Text(stringResource(R.string.ai_translation_mode)) },
            text = {
                Column {
                    Text(
                        text = "${stringResource(R.string.ai_translation_literal)}:",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        text = stringResource(R.string.ai_translation_literal_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    Text(
                        text = "${stringResource(R.string.ai_translation_transcribed)}:",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.ai_translation_transcribed_desc),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
        )
    }

    if (showTranslateModeDialog) {
        EnumDialog(
            onDismiss = { showTranslateModeDialog = false },
            onSelect = {
                translateMode = it
                showTranslateModeDialog = false
            },
            title = stringResource(R.string.ai_translation_mode),
            current = translateMode,
            values = listOf("Literal", "Transcribed"),
            valueText = {
                when (it) {
                    "Literal" -> stringResource(R.string.ai_translation_literal)
                    "Transcribed" -> stringResource(R.string.ai_translation_transcribed)
                    else -> it
                }
            },
        )
    }

    if (showLanguageDialog) {
        EnumDialog(
            onDismiss = { showLanguageDialog = false },
            onSelect = {
                translateLanguage = it
                showLanguageDialog = false
            },
            title = stringResource(R.string.ai_target_language),
            current = translateLanguage,
            values = LanguageCodeToName.keys.sortedBy { LanguageCodeToName[it] },
            valueText = { LanguageCodeToName[it] ?: it },
        )
    }

    if (showDeeplFormalityDialog) {
        EnumDialog(
            onDismiss = { showDeeplFormalityDialog = false },
            onSelect = {
                deeplFormality = it
                showDeeplFormalityDialog = false
            },
            title = stringResource(R.string.ai_deepl_formality),
            current = deeplFormality,
            values = listOf("default", "more", "less"),
            valueText = {
                when (it) {
                    "default" -> stringResource(R.string.ai_deepl_formality_default)
                    "more" -> stringResource(R.string.ai_deepl_formality_more)
                    "less" -> stringResource(R.string.ai_deepl_formality_less)
                    else -> it
                }
            },
        )
    }

    if (showSystemPromptDialog) {
        TextFieldDialog(
            title = { Text(stringResource(R.string.ai_system_prompt)) },
            icon = { Icon(painterResource(R.drawable.edit), null) },
            initialTextFieldValue = TextFieldValue(text = aiSystemPrompt.ifBlank { DEFAULT_AI_SYSTEM_PROMPT }),
            singleLine = false,
            maxLines = 12,
            isInputValid = { true },
            onDone = {
                aiSystemPrompt = if (it.isBlank() || it == DEFAULT_AI_SYSTEM_PROMPT) "" else it
                showSystemPromptDialog = false
            },
            onDismiss = { showSystemPromptDialog = false },
            extraContent = {
                if (aiSystemPrompt.isNotBlank()) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                    ) {
                        TextButton(
                            onClick = {
                                aiSystemPrompt = ""
                                showSystemPromptDialog = false
                            },
                        ) {
                            Text(stringResource(R.string.ai_system_prompt_reset))
                        }
                    }
                }
            },
        )
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
            title = stringResource(R.string.ai_lyrics_translation),
            items =
                buildList {
                    if (translation.provider == "DeepL") {
                        add(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.tune),
                                title = { Text(stringResource(R.string.ai_deepl_formality)) },
                                description = {
                                    Text(
                                        when (deeplFormality) {
                                            "default" -> stringResource(R.string.ai_deepl_formality_default)
                                            "more" -> stringResource(R.string.ai_deepl_formality_more)
                                            "less" -> stringResource(R.string.ai_deepl_formality_less)
                                            else -> deeplFormality
                                        },
                                    )
                                },
                                onClick = { showDeeplFormalityDialog = true },
                            ),
                        )
                    } else {
                        add(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.translate),
                                title = { Text(stringResource(R.string.ai_translation_mode)) },
                                description = {
                                    Text(
                                        when (translateMode) {
                                            "Literal" -> stringResource(R.string.ai_translation_literal)
                                            "Transcribed" -> stringResource(R.string.ai_translation_transcribed)
                                            else -> translateMode
                                        },
                                    )
                                },
                                onClick = { showTranslateModeDialog = true },
                                trailingContent = {
                                    IconButton(onClick = { showTranslateModeHelpDialog = true }) {
                                        Icon(
                                            painterResource(R.drawable.info),
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                },
                            ),
                        )
                        add(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.edit),
                                title = { Text(stringResource(R.string.ai_system_prompt)) },
                                description = {
                                    Text(
                                        if (aiSystemPrompt.isNotBlank()) {
                                            aiSystemPrompt.take(60).let {
                                                if (aiSystemPrompt.length > 60) "$it…" else it
                                            }
                                        } else {
                                            stringResource(R.string.ai_system_prompt_default)
                                        },
                                    )
                                },
                                onClick = { showSystemPromptDialog = true },
                            ),
                        )
                    }
                    add(
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.language),
                            title = { Text(stringResource(R.string.ai_target_language)) },
                            description = { Text(LanguageCodeToName[translateLanguage] ?: translateLanguage) },
                            onClick = { showLanguageDialog = true },
                        ),
                    )
                },
        )

        Spacer(Modifier.height(16.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.ai_lyrics_translation)) },
        navigationIcon = {
            IconButton(onClick = { navController.navigateUp() }) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
    )
}
