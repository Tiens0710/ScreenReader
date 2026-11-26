package com.arslan.textgrab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ScreenTranslationState(val running: Boolean = false, val ready: Boolean = false,
    val guideReady: Boolean = false,
    val status: String = "Bật nút Dịch, rồi mở trang cần dịch")
object ScreenTranslationSession {
    private val mutable = MutableStateFlow(ScreenTranslationState())
    val state = mutable.asStateFlow()
    fun reset(value: ScreenTranslationState = ScreenTranslationState()) { mutable.value = value }
    fun update(change: (ScreenTranslationState) -> ScreenTranslationState) = mutable.update(change)
}
