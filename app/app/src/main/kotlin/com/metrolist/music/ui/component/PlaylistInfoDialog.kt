/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.R
import com.metrolist.music.constants.RadioStarDayStartHourKey
import com.metrolist.music.constants.RadioStarPlaylistMetaKey
import com.metrolist.music.recommend.GeneratedPlaylistMeta
import com.metrolist.music.recommend.GeneratedPlaylistMetaCodec
import com.metrolist.music.recommend.PlaylistHorizon
import com.metrolist.music.recommend.PlaylistSchedule
import com.metrolist.music.recommend.PlaylistUpdateFormatter
import com.metrolist.music.recommend.PlaylistUpdateLabel
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.rememberPreference
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.flow.map

/** Reads the generation metadata of one playlist, if it has any. */
@Composable
fun rememberGeneratedPlaylistMeta(playlistId: String): GeneratedPlaylistMeta? {
    val context = LocalContext.current
    val raw by
        context.dataStore.data
            .map { it[RadioStarPlaylistMetaKey] }
            .collectAsStateWithLifecycle(initialValue = null)
    return remember(raw, playlistId) { GeneratedPlaylistMetaCodec.entry(raw, playlistId) }
}

@Composable
fun playlistUpdateLabelText(label: PlaylistUpdateLabel): String =
    when (label) {
        is PlaylistUpdateLabel.Today -> stringResource(R.string.radiostar_updated_today, label.time)
        is PlaylistUpdateLabel.Yesterday -> stringResource(R.string.radiostar_updated_yesterday, label.time)
        is PlaylistUpdateLabel.DaysAgo -> stringResource(R.string.radiostar_updated_days_ago, label.days)
        PlaylistUpdateLabel.Unknown -> stringResource(R.string.radiostar_updated_unknown)
    }

/**
 * Details for a generated or user playlist. Generated lists add the version
 * and next refresh time; everything else shows the plain playlist dates.
 */
@Composable
fun PlaylistInfoDialog(
    title: String,
    createdAt: LocalDateTime?,
    updatedAt: LocalDateTime?,
    trackCount: Int,
    onDismiss: () -> Unit,
    version: Int? = null,
    horizon: PlaylistHorizon? = null,
) {
    val dayStartHour by rememberPreference(RadioStarDayStartHourKey, PlaylistSchedule.DAY_START_HOUR.toInt())
    val now = remember { LocalDateTime.now() }
    val updateLabel = PlaylistUpdateFormatter.label(updatedAt, now, dayStartHour.toLong())
    val none = stringResource(R.string.radiostar_info_none)

    DefaultDialog(
        onDismiss = onDismiss,
        title = { Text(stringResource(R.string.radiostar_playlist_info)) },
        buttons = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        },
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        InfoRow(
            label = stringResource(R.string.radiostar_info_created),
            value = createdAt?.format(absoluteFormatter) ?: none,
        )
        InfoRow(
            label = stringResource(R.string.radiostar_info_updated),
            value = playlistUpdateLabelText(updateLabel),
        )
        InfoRow(
            label = stringResource(R.string.radiostar_info_version),
            value = version?.toString() ?: none,
        )
        if (horizon != null) {
            InfoRow(
                label = stringResource(R.string.radiostar_info_horizon),
                value =
                    when (horizon) {
                        PlaylistHorizon.DAILY -> stringResource(R.string.radiostar_daily)
                        PlaylistHorizon.WEEKLY -> stringResource(R.string.radiostar_weekly)
                        PlaylistHorizon.SPECIAL -> none
                    },
            )
            val next = PlaylistSchedule.nextRefresh(now, dayStartHour.toLong(), horizon)
            InfoRow(
                label = stringResource(R.string.radiostar_info_next_refresh),
                value = next.format(absoluteFormatter),
            )
        }
        InfoRow(
            label = stringResource(R.string.radiostar_info_tracks),
            value = trackCount.toString(),
        )
    }
}

private val absoluteFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

@Composable
private fun InfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}
