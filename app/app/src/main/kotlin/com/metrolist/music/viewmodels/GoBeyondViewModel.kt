/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.recommend.GoBeyondFeed
import com.metrolist.music.recommend.GoBeyondRepository
import com.metrolist.music.recommend.GoBeyondSelection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GoBeyondUiState(
    val loading: Boolean = true,
    val generating: Boolean = false,
    val feed: GoBeyondFeed? = null,
    val message: String? = null,
)

sealed interface GoBeyondEvent {
    data class Created(
        val playlistId: String,
    ) : GoBeyondEvent

    data class Failed(
        val message: String,
    ) : GoBeyondEvent
}

@HiltViewModel
class GoBeyondViewModel
    @Inject
    constructor(
        private val repository: GoBeyondRepository,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(GoBeyondUiState())
        val state: StateFlow<GoBeyondUiState> = mutableState.asStateFlow()

        private val mutableEvents = MutableSharedFlow<GoBeyondEvent>(extraBufferCapacity = 4)
        val events: SharedFlow<GoBeyondEvent> = mutableEvents.asSharedFlow()

        private var refreshing = false

        init {
            refresh()
        }

        fun refresh() {
            if (refreshing) return
            refreshing = true
            viewModelScope.launch {
                mutableState.update { it.copy(loading = true, message = null) }
                runCatching { repository.feed() }
                    .onSuccess { feed ->
                        mutableState.update { it.copy(loading = false, feed = feed) }
                    }
                    .onFailure { error ->
                        mutableState.update {
                            it.copy(
                                loading = false,
                                message = error.message ?: "Could not load Go Beyond.",
                            )
                        }
                    }
                refreshing = false
            }
        }

        fun generate(selection: GoBeyondSelection) {
            if (mutableState.value.generating) return
            viewModelScope.launch {
                mutableState.update { it.copy(generating = true, message = null) }
                runCatching { repository.generate(selection) }
                    .onSuccess { playlistId ->
                        mutableState.update { it.copy(generating = false) }
                        mutableEvents.emit(GoBeyondEvent.Created(playlistId))
                    }
                    .onFailure { error ->
                        val message = error.message?.takeIf { it.isNotBlank() } ?: "Could not build that journey."
                        mutableState.update { it.copy(generating = false, message = message) }
                        mutableEvents.emit(GoBeyondEvent.Failed(message))
                    }
            }
        }

        fun clearMessage() {
            mutableState.update { it.copy(message = null) }
        }
    }
