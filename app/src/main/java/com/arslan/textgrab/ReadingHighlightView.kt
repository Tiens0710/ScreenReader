package com.arslan.textgrab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.BlurMaskFilter
import android.os.SystemClock
import android.view.View
import kotlin.math.sin

/** Normalized source coordinates; follows playback, not network arrival. */
class ReadingHighlightView(context: Context) : View(context) {
    private var boxes = emptyList<RectF>()
    private var fromBoxes = emptyList<RectF>()
    private var lastAt = 0L
    private var transitionAt = 0L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun show(regions: List<RectF>) {
        if (regions == boxes) {
            lastAt = SystemClock.uptimeMillis()
            return
        }
        val now = SystemClock.uptimeMillis()
        fromBoxes = currentBoxes(now)
        boxes = regions
        transitionAt = now
        lastAt = now
        invalidate()
    }

    private fun currentBoxes(now: Long): List<RectF> {
        val progress = ((now - transitionAt) / 180f).coerceIn(0f, 1f)
        val eased = 1f - (1f - progress) * (1f - progress)
        return boxes.mapIndexedNotNull { index, target ->
            val start = fromBoxes.getOrNull(index) ?: RectF(target.centerX(), target.centerY(), target.centerX(), target.centerY())
            RectF(start.left + (target.left - start.left) * eased, start.top + (target.top - start.top) * eased,
                start.right + (target.right - start.right) * eased, start.bottom + (target.bottom - start.bottom) * eased)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        if (now - lastAt > 400) { boxes = emptyList(); fromBoxes = emptyList(); return }
        val d = resources.displayMetrics.density
        val progress = ((now - transitionAt) / 180f).coerceIn(0f, 1f)
        val pulse = (.96f + .04f * sin(now / 360.0)).toFloat()
        for (box in currentBoxes(now)) {
            val rect = RectF(box.left * width - 4*d, box.top * height - 4*d,
                box.right * width + 4*d, box.bottom * height + 4*d)
            val radius = 11*d

            // A restrained mint wash makes the active line easy to spot while keeping its letters clear.
            paint.clearShadowLayer()
            paint.style = Paint.Style.FILL
            paint.color = Color.argb((22 * pulse).toInt(), 35, 230, 184)
            canvas.drawRoundRect(rect, radius, radius, paint)

            // Diffuse halo, crisp outline, then brighter corner brackets for a deliberate focus cue.
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = 7*d
            paint.color = Color.argb((46 * pulse).toInt(), 37, 242, 192)
            paint.maskFilter = BlurMaskFilter(5*d, BlurMaskFilter.Blur.NORMAL)
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.maskFilter = null
            paint.strokeWidth = 2*d
            paint.color = Color.argb((215 * pulse).toInt(), 67, 245, 199)
            paint.style = Paint.Style.STROKE
            canvas.drawRoundRect(rect, radius, radius, paint)

            val corner = minOf(14*d, rect.width()*.22f, rect.height()*.38f)
            paint.strokeWidth = 3.4f*d
            paint.color = Color.argb((255 * pulse).toInt(), 160, 255, 226)
            canvas.drawLine(rect.left+radius, rect.top, rect.left+radius+corner, rect.top, paint)
            canvas.drawLine(rect.left, rect.top+radius, rect.left, rect.top+radius+corner, paint)
            canvas.drawLine(rect.right-radius-corner, rect.bottom, rect.right-radius, rect.bottom, paint)
            canvas.drawLine(rect.right, rect.bottom-radius-corner, rect.right, rect.bottom-radius, paint)
        }
        if (boxes.isNotEmpty() && (progress < 1f || now - lastAt < 400)) postInvalidateOnAnimation()
    }
}
