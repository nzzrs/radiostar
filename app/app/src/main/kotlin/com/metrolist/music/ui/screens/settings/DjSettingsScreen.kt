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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.RadioStarDjAiKey
import com.metrolist.music.constants.RadioStarDjContextCityKey
import com.metrolist.music.constants.RadioStarDjContextKey
import com.metrolist.music.constants.RadioStarDjContextNewsKey
import com.metrolist.music.constants.RadioStarDjContextWeatherKey
import com.metrolist.music.constants.RadioStarDjFarKey
import com.metrolist.music.constants.RadioStarDjKey
import com.metrolist.music.constants.RadioStarDjKnownKey
import com.metrolist.music.constants.RadioStarDjLikelyKey
import com.metrolist.music.recommend.DjConfig
import com.metrolist.music.recommend.DjPresence
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.screens.DjSettingsBody
import com.metrolist.music.ui.screens.rememberDjPersonaPreferences
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DjSettingsScreen(navController: NavController) {
    var enabled by rememberPreference(RadioStarDjKey, true)
    var aiEnabled by rememberPreference(RadioStarDjAiKey, false)
    val djPersonas = rememberDjPersonaPreferences()
    var known by rememberEnumPreference(RadioStarDjKnownKey, DjPresence.SOMETIMES)
    var far by rememberEnumPreference(RadioStarDjFarKey, DjPresence.NEVER)
    var likely by rememberEnumPreference(RadioStarDjLikelyKey, DjPresence.FREQUENT)
    var contextEnabled by rememberPreference(RadioStarDjContextKey, true)
    var weatherEnabled by rememberPreference(RadioStarDjContextWeatherKey, false)
    var newsEnabled by rememberPreference(RadioStarDjContextNewsKey, false)
    var contextCity by rememberPreference(RadioStarDjContextCityKey, "")
    val config = DjConfig(persona = djPersonas.library.selected, known = known, far = far, likely = likely)

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
            title = stringResource(R.string.dj_section_status),
            items =
                listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.radio),
                        title = { Text(stringResource(R.string.dj_enable)) },
                        description = { Text(stringResource(R.string.dj_enable_desc)) },
                        trailingContent = {
                            Switch(checked = enabled, onCheckedChange = { enabled = it })
                        },
                        onClick = { enabled = !enabled },
                    ),
                ),
        )

        Spacer(Modifier.height(24.dp))

        DjSettingsBody(
            config = config,
            personas = djPersonas.library,
            onSelectPersona = djPersonas::select,
            onSavePersona = djPersonas::save,
            onDeletePersona = djPersonas::delete,
            aiEnabled = aiEnabled,
            onAiChange = { aiEnabled = it },
            onConfigChange = { updated ->
                known = updated.known
                far = updated.far
                likely = updated.likely
            },
            onOpenAiProviders = { navController.navigate("settings/ai/providers") },
            contextEnabled = contextEnabled,
            weatherEnabled = weatherEnabled,
            newsEnabled = newsEnabled,
            city = contextCity,
            onContextEnabledChange = { contextEnabled = it },
            onWeatherEnabledChange = { weatherEnabled = it },
            onNewsEnabledChange = { newsEnabled = it },
            onCityChange = { contextCity = it },
        )

        Spacer(Modifier.height(24.dp))
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
                    contentDescription = stringResource(R.string.back),
                )
            }
        },
    )
}
