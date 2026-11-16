package com.arslan.textgrab

import android.graphics.RectF

/** Allow one queued reading turn, advancing only when it reaches the speaker. */
internal object ReadingPrefetchPolicy {
    fun canPrepare(pending: Boolean, active: List<RectF>, requested: List<RectF>,
                   cacheCurrent: Boolean, hasNext: Boolean): Boolean =
        !pending || (cacheCurrent && hasNext && requested.isNotEmpty() && active == requested)
}
