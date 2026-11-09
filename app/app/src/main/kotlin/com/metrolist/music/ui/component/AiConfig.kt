/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.recommend.AiFeature
import com.metrolist.music.recommend.AiProviderResolver
import com.metrolist.music.recommend.AiResolvedConfig
import com.metrolist.music.utils.dataStore

/** Live resolved provider for a feature; follows every provider setting change. */
@Composable
fun rememberAiResolvedConfig(feature: AiFeature): AiResolvedConfig {
    val context = LocalContext.current
    val prefs by context.dataStore.data.collectAsStateWithLifecycle(initialValue = emptyPreferences())
    return remember(prefs, feature) { AiProviderResolver.fromPreferences(prefs, feature) }
}
