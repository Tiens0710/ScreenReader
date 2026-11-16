package com.arslan.textgrab

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LiveUiState(
    val running: Boolean = false,
    val connected: Boolean = false,
    val paused: Boolean = false,
    val voice: Boolean = true,
    val microphone: Boolean = false,
    val frames: Int = 0,
    val status: String = "Sẵn sàng đọc cho bạn",
    val transcript: String = ""
)

object LiveSession {
    @Volatile var visibleActivities: Int = 0
    val appVisible: Boolean get() = visibleActivities > 0
    private val mutable = MutableStateFlow(LiveUiState())
    val state = mutable.asStateFlow()
    fun update(change: (LiveUiState) -> LiveUiState) = mutable.update(change)
    fun reset(value: LiveUiState = LiveUiState()) { mutable.value = value }
}
