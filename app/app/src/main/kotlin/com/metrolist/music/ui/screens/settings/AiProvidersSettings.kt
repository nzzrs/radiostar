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
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.AI_TTS_DEFAULT_VOICE
import com.metrolist.music.constants.AI_TTS_ENGINE_PHONE
import com.metrolist.music.constants.AI_TTS_ENGINE_PROVIDER
import com.metrolist.music.constants.AiTtsEngineKey
import com.metrolist.music.constants.AiTtsVoiceKey
import com.metrolist.music.playback.AiTtsPreview
import com.metrolist.music.recommend.AiFeature
import com.metrolist.music.ui.component.EnumDialog
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.rememberAiResolvedConfig
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.launch

/** Global provider, per-feature overrides and text-to-speech voice. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiProvidersSettings(navController: NavController) {
    var ttsEngine by rememberPreference(AiTtsEngineKey, AI_TTS_ENGINE_PHONE)
    var ttsVoice by rememberPreference(AiTtsVoiceKey, AI_TTS_DEFAULT_VOICE)
    val tts = rememberAiResolvedConfig(AiFeature.TTS)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showTtsEngineDialog by rememberSaveable { mutableStateOf(false) }
    var showTtsVoiceDialog by rememberSaveable { mutableStateOf(false) }
    var voiceTesting by remember { mutableStateOf(false) }
    var voiceTestResult by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose { AiTtsPreview.stop() }
    }

    if (showTtsEngineDialog) {
        EnumDialog(
            onDismiss = { showTtsEngineDialog = false },
            onSelect = {
                ttsEngine = it
                showTtsEngineDialog = false
            },
            title = stringResource(R.string.ai_voice_engine),
            current = ttsEngine,
            values = listOf(AI_TTS_ENGINE_PHONE, AI_TTS_ENGINE_PROVIDER),
            valueText = {
                when (it) {
                    AI_TTS_ENGINE_PHONE -> stringResource(R.string.ai_voice_engine_phone)
                    AI_TTS_ENGINE_PROVIDER -> stringResource(R.string.ai_voice_engine_provider)
                    else -> it
                }
            },
        )
    }

    if (showTtsVoiceDialog) {
        AiVoicePickerDialog(
            config = tts,
            current = tts.ttsVoice,
            onSelect = { ttsVoice = it },
            onDismiss = { showTtsVoiceDialog = false },
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

        AiDefaultProviderGroup()

        Spacer(Modifier.height(27.dp))

        AiFeatureConfigSection(
            feature = AiFeature.TRANSLATION,
            title = stringResource(R.string.ai_lyrics_translation),
        )

        Spacer(Modifier.height(27.dp))

        AiFeatureConfigSection(
            feature = AiFeature.DJ_VOICE,
            title = stringResource(R.string.radiostar_dj_ai),
        )

        Spacer(Modifier.height(27.dp))

        AiFeatureConfigSection(
            feature = AiFeature.TTS,
            title = stringResource(R.string.ai_text_to_speech),
            leadingItems =
                listOf(
                    ttsEngineItem(ttsEngine, onClick = { showTtsEngineDialog = true }),
                ),
            extraItems =
                if (ttsEngine == AI_TTS_ENGINE_PROVIDER) {
                    listOf(
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.radio),
                            title = { Text(stringResource(R.string.ai_voice)) },
                            description = { Text(tts.ttsVoice) },
                            onClick = { showTtsVoiceDialog = true },
                        ),
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.play),
                            title = { Text(stringResource(R.string.ai_voice_test)) },
                            description = voiceTestResult?.let { { Text(it) } },
                            trailingContent = {
                                if (voiceTesting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            },
                            onClick = {
                                if (!voiceTesting) {
                                    voiceTesting = true
                                    voiceTestResult = null
                                    scope.launch {
                                        val error =
                                            AiTtsPreview.speak(
                                                context,
                                                context.getString(R.string.ai_voice_test_text),
                                            )
                                        voiceTestResult = error ?: context.getString(R.string.ai_voice_test_playing)
                                        voiceTesting = false
                                    }
                                }
                            },
                        ),
                    )
                } else {
                    emptyList()
                },
        )

        Spacer(Modifier.height(16.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.ai_providers)) },
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

@Composable
private fun ttsEngineItem(
    engine: String,
    onClick: () -> Unit,
): Material3SettingsItem =
    Material3SettingsItem(
        icon = painterResource(R.drawable.radio),
        title = { Text(stringResource(R.string.ai_voice_engine)) },
        description = {
            Text(
                when (engine) {
                    AI_TTS_ENGINE_PROVIDER -> stringResource(R.string.ai_voice_engine_provider)
                    else -> stringResource(R.string.ai_voice_engine_phone)
                },
            )
        },
        onClick = onClick,
    )
