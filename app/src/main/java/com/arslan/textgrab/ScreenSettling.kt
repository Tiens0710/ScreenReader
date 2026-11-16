package com.arslan.textgrab

/** A trailing quiet window: every content change restarts the wait, including startup. */
internal class ScreenSettling(private val quietMs: Long = 700L) {
    private var changedAt: Long? = null
    fun changed(now: Long) { changedAt = now }
    fun isReady(now: Long): Boolean = changedAt?.let { now - it >= quietMs } ?: false
}
