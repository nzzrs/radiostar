/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.recommend.AiProviderClient

/**
 * Model picker that asks the provider for its current model catalog and lets
 * the user filter it. It keeps the bundled suggestions when the endpoint is
 * unavailable, so selecting a model never blocks on the network.
 */
@Composable
fun AiModelPickerDialog(
    provider: String,
    baseUrl: String,
    apiKey: String,
    current: String,
    fallbackModels: List<String>,
    onSelect: (String) -> Unit,
    onCustom: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var models by remember(fallbackModels) { mutableStateOf(fallbackModels) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(provider, baseUrl, apiKey) {
        loading = true
        val result = AiProviderClient.listModels(provider, baseUrl, apiKey)
        if (result.ok) {
            models = result.models
            error = null
        } else {
            error = result.message
        }
        loading = false
    }

    val filtered =
        models.filter { query.isBlank() || it.contains(query, ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_model)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.ai_model_filter)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (loading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp).padding(top = 8.dp),
                ) {
                    if (query.isBlank() && current.isNotBlank() && models.none { it == current }) {
                        item(key = "current") {
                            TextButton(onClick = { onSelect(current) }) {
                                Text(current)
                            }
                        }
                    }
                    items(filtered, key = { it }) { model ->
                        TextButton(onClick = { onSelect(model) }) {
                            Text(model)
                        }
                    }
                }
                if (filtered.isEmpty() && !loading) {
                    Text(
                        text = stringResource(R.string.ai_no_models),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCustom) {
                Text(stringResource(R.string.ai_custom_model))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
