package com.arslan.textgrab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import kotlin.math.sin

/** A small, touch-through indicator driven by the PCM that reaches the speaker. */
class SpeakingWaveView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var level = 0f
    private var target = 0f
    private var lastAudioAt = 0L
    private val density = resources.displayMetrics.density

    fun setLevel(value: Float) {
        target = value
        lastAudioAt = if (value > 0f) SystemClock.uptimeMillis() else 0L
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        if (now - lastAudioAt > 350) target = 0f
        level += (target - level) * .25f
        if (level < .008f && target == 0f) return
        paint.color = Color.rgb(16, 42, 40)
        paint.alpha = (level * 600).toInt().coerceIn(0, 235)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), height / 2f, height / 2f, paint)
        paint.color = Color.rgb(111, 239, 205)
        paint.alpha = (level * 900).toInt().coerceIn(0, 255)
        paint.strokeWidth = 3 * density
        paint.strokeCap = Paint.Cap.ROUND
        val count = 23
        for (i in 0 until count) {
            val x = width / 2f + (i - count / 2) * 6 * density
            val envelope = sin(Math.PI * (i + 1) / (count + 1)).toFloat()
            val motion = (.5 + .5 * sin(now / 95.0 + i * .7)).toFloat()
            val bar = (2 + 13 * level * envelope * motion) * density
            canvas.drawLine(x, height / 2 - bar, x, height / 2 + bar, paint)
        }
        postInvalidateOnAnimation()
    }
}
