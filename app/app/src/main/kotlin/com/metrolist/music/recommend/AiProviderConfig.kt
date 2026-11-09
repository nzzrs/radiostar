/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import com.metrolist.music.constants.AI_TTS_DEFAULT_BASE_URL
import com.metrolist.music.constants.AI_TTS_DEFAULT_MODEL
import com.metrolist.music.constants.AI_TTS_DEFAULT_PROVIDER
import com.metrolist.music.constants.AI_TTS_DEFAULT_VOICE
import com.metrolist.music.constants.AI_TTS_ENGINE_PHONE
import com.metrolist.music.constants.AI_TTS_ENGINE_PROVIDER
import com.metrolist.music.constants.AiProviderKey
import com.metrolist.music.constants.AiTranslationApiKeyKey
import com.metrolist.music.constants.AiTranslationBaseUrlKey
import com.metrolist.music.constants.AiTranslationModelKey
import com.metrolist.music.constants.AiTranslationProviderKey
import com.metrolist.music.constants.AiTranslationUseDefaultKey
import com.metrolist.music.constants.AiTtsApiKeyKey
import com.metrolist.music.constants.AiTtsBaseUrlKey
import com.metrolist.music.constants.AiTtsEngineKey
import com.metrolist.music.constants.AiTtsModelKey
import com.metrolist.music.constants.AiTtsProviderKey
import com.metrolist.music.constants.AiTtsUseDefaultKey
import com.metrolist.music.constants.AiTtsVoiceKey
import com.metrolist.music.constants.DeeplApiKey
import com.metrolist.music.constants.OpenRouterApiKey
import com.metrolist.music.constants.OpenRouterBaseUrlKey
import com.metrolist.music.constants.OpenRouterDefaultBaseUrl
import com.metrolist.music.constants.OpenRouterDefaultModel
import com.metrolist.music.constants.OpenRouterModelKey
import com.metrolist.music.constants.RADIOSTAR_DEFAULT_DJ_BASE_URL
import com.metrolist.music.constants.RADIOSTAR_DEFAULT_DJ_MODEL
import com.metrolist.music.constants.RADIOSTAR_DEFAULT_DJ_PROVIDER
import com.metrolist.music.constants.RadioStarDjApiKeyKey
import com.metrolist.music.constants.RadioStarDjBaseUrlKey
import com.metrolist.music.constants.RadioStarDjModelKey
import com.metrolist.music.constants.RadioStarDjProviderKey
import com.metrolist.music.constants.RadioStarDjUseDefaultKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import kotlinx.coroutines.flow.first

enum class AiFeature {
    TRANSLATION,
    DJ_VOICE,
    TTS,
}

enum class AiTtsEngine {
    PHONE,
    PROVIDER,
}

/** One resolved provider for one feature; [baseUrl] stays the chat URL for TTS too. */
data class AiResolvedConfig(
    val feature: AiFeature,
    val provider: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val useDefault: Boolean,
    val ttsEngine: AiTtsEngine = AiTtsEngine.PHONE,
    val ttsVoice: String = AI_TTS_DEFAULT_VOICE,
) {
    val isChatCapable: Boolean
        get() = provider != "DeepL"

    val hasKey: Boolean
        get() = apiKey.isNotBlank()

    val canSpeak: Boolean
        get() = ttsEngine == AiTtsEngine.PROVIDER && isChatCapable && hasKey &&
            model.isNotBlank() &&
            (provider == "Gemini" || AiProviderCatalog.speechUrl(baseUrl).isNotBlank())
}

/**
 * Resolves the global provider and the per-feature override.
 *
 * The global keys are the old OpenRouter/Deepl settings, so translation keeps
 * working without a migration. A feature with the default switch on reads the
 * provider and key globally but keeps its own model.
 */
object AiProviderResolver {
    const val DEFAULT_PROVIDER = "OpenRouter"

    suspend fun resolve(context: Context, feature: AiFeature): AiResolvedConfig =
        fromPreferences(context.dataStore.data.first(), feature)

    fun fromPreferences(prefs: Preferences, feature: AiFeature): AiResolvedConfig {
        val globalProvider = prefs[AiProviderKey]?.trim().takeUnless { it.isNullOrEmpty() } ?: DEFAULT_PROVIDER
        val globalBase =
            prefs[OpenRouterBaseUrlKey]?.trim().takeUnless { it.isNullOrEmpty() }
                ?: OpenRouterDefaultBaseUrl
        val globalModel =
            prefs[OpenRouterModelKey]?.trim().takeUnless { it.isNullOrEmpty() }
                ?: OpenRouterDefaultModel
        val globalKey = globalApiKey(prefs, globalProvider)
        val useDefault = usesDefault(prefs, feature)
        val provider =
            if (useDefault) {
                globalProvider
            } else {
                prefs[providerKey(feature)]?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: defaultProvider(feature)
            }
        val baseUrl =
            if (useDefault) {
                globalBase
            } else {
                prefs[baseUrlKey(feature)]?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: defaultBaseUrl(feature)
            }
        val geminiTts = provider == "Gemini" || baseUrl.contains("generativelanguage.googleapis.com")
        val model =
            prefs[modelKey(feature)]?.trim().takeUnless { it.isNullOrEmpty() }
                ?: when {
                    feature == AiFeature.TTS -> AiTtsClient.defaultModel(if (geminiTts) "Gemini" else provider)
                    useDefault -> globalModel
                    else -> defaultModel(feature)
                }
        val apiKey =
            if (useDefault) {
                globalKey
            } else {
                customApiKey(prefs, feature, provider, globalKey)
            }
        return AiResolvedConfig(
            feature = feature,
            provider = provider,
            baseUrl = baseUrl,
            model = model,
            apiKey = apiKey,
            useDefault = useDefault,
            ttsEngine = if (prefs[AiTtsEngineKey] == AI_TTS_ENGINE_PROVIDER) AiTtsEngine.PROVIDER else AiTtsEngine.PHONE,
            ttsVoice = AiTtsClient.normalizeVoice(if (geminiTts) "Gemini" else provider, prefs[AiTtsVoiceKey]),
        )
    }

    /** One-time switch for installs that configured the old DJ provider block. */
    suspend fun migrate(context: Context) {
        context.safeDataStoreEdit { prefs ->
            if (prefs[RadioStarDjUseDefaultKey] == null) {
                val hasLegacyDjConfig =
                    !prefs[RadioStarDjApiKeyKey].isNullOrBlank() ||
                        !prefs[RadioStarDjProviderKey].isNullOrBlank() ||
                        !prefs[RadioStarDjBaseUrlKey].isNullOrBlank() ||
                        !prefs[RadioStarDjModelKey].isNullOrBlank()
                prefs[RadioStarDjUseDefaultKey] = !hasLegacyDjConfig
            }
            if (prefs[AiTranslationUseDefaultKey] == null) prefs[AiTranslationUseDefaultKey] = true
            if (prefs[AiTtsUseDefaultKey] == null) prefs[AiTtsUseDefaultKey] = true
            if (prefs[AiTtsEngineKey] == null) prefs[AiTtsEngineKey] = AI_TTS_ENGINE_PHONE
        }
    }

    fun usesDefault(prefs: Preferences, feature: AiFeature): Boolean =
        prefs[useDefaultKey(feature)] ?: (feature != AiFeature.DJ_VOICE)

    fun useDefaultKey(feature: AiFeature) =
        when (feature) {
            AiFeature.TRANSLATION -> AiTranslationUseDefaultKey
            AiFeature.DJ_VOICE -> RadioStarDjUseDefaultKey
            AiFeature.TTS -> AiTtsUseDefaultKey
        }

    fun providerKey(feature: AiFeature) =
        when (feature) {
            AiFeature.TRANSLATION -> AiTranslationProviderKey
            AiFeature.DJ_VOICE -> RadioStarDjProviderKey
            AiFeature.TTS -> AiTtsProviderKey
        }

    fun baseUrlKey(feature: AiFeature) =
        when (feature) {
            AiFeature.TRANSLATION -> AiTranslationBaseUrlKey
            AiFeature.DJ_VOICE -> RadioStarDjBaseUrlKey
            AiFeature.TTS -> AiTtsBaseUrlKey
        }

    fun modelKey(feature: AiFeature) =
        when (feature) {
            AiFeature.TRANSLATION -> AiTranslationModelKey
            AiFeature.DJ_VOICE -> RadioStarDjModelKey
            AiFeature.TTS -> AiTtsModelKey
        }

    fun apiKeyKey(feature: AiFeature) =
        when (feature) {
            AiFeature.TRANSLATION -> AiTranslationApiKeyKey
            AiFeature.DJ_VOICE -> RadioStarDjApiKeyKey
            AiFeature.TTS -> AiTtsApiKeyKey
        }

    fun encryptedKey(feature: AiFeature): Boolean = feature == AiFeature.DJ_VOICE

    fun defaultProvider(feature: AiFeature): String =
        when (feature) {
            AiFeature.TRANSLATION -> DEFAULT_PROVIDER
            AiFeature.DJ_VOICE -> RADIOSTAR_DEFAULT_DJ_PROVIDER
            AiFeature.TTS -> AI_TTS_DEFAULT_PROVIDER
        }

    fun defaultBaseUrl(feature: AiFeature): String =
        when (feature) {
            AiFeature.TRANSLATION -> OpenRouterDefaultBaseUrl
            AiFeature.DJ_VOICE -> RADIOSTAR_DEFAULT_DJ_BASE_URL
            AiFeature.TTS -> AI_TTS_DEFAULT_BASE_URL
        }

    fun defaultModel(feature: AiFeature): String =
        when (feature) {
            AiFeature.TRANSLATION -> OpenRouterDefaultModel
            AiFeature.DJ_VOICE -> RADIOSTAR_DEFAULT_DJ_MODEL
            AiFeature.TTS -> AI_TTS_DEFAULT_MODEL
        }

    private fun globalApiKey(prefs: Preferences, provider: String): String =
        if (provider == "DeepL") {
            prefs[DeeplApiKey].orEmpty()
        } else {
            prefs[OpenRouterApiKey].orEmpty()
        }

    private fun customApiKey(
        prefs: Preferences,
        feature: AiFeature,
        provider: String,
        globalKey: String,
    ): String {
        if (feature == AiFeature.TRANSLATION && provider == "DeepL") {
            return prefs[DeeplApiKey].orEmpty()
        }
        val stored = prefs[apiKeyKey(feature)].orEmpty()
        if (feature != AiFeature.DJ_VOICE) return stored
        val decrypted = stored.takeIf { it.isNotBlank() }?.let(SecureKeyStore::decrypt)
        return decrypted?.takeIf { it.isNotBlank() } ?: globalKey
    }
}
