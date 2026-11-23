package com.arslan.textgrab

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import kotlin.math.sin
import kotlin.math.sqrt

/** A small, touch-through indicator driven by the PCM that reaches the speaker. */
class SpeakingWaveView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var level = 0f
    private var target = 0f
    private var lastAudioAt = 0L
    private val density = resources.displayMetrics.density
    private val envelope = FloatArray(19) { sin(Math.PI * (it + 1) / 20).toFloat() }
    private val samples = FloatArray(envelope.size)
    private var nextSample = 0

    fun setLevel(value: Float) {
        target = sqrt(value.coerceIn(0f, 1f))
        samples[nextSample] = target
        nextSample = (nextSample + 1) % samples.size
        if (value > 0f) lastAudioAt = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (lastAudioAt == 0L) return
        val now = SystemClock.uptimeMillis()
        val silence = now - lastAudioAt
        val visibility = ((570f - silence) / 290f).coerceIn(0f, 1f)
        if (visibility == 0f) {
            level = 0f
            target = 0f
            samples.fill(0f)
            return
        }
        val animated = ValueAnimator.areAnimatorsEnabled()
        val desired = if (silence < 160) target else 0f
        level += (desired - level) * if (!animated) 1f else if (desired > level) .44f else .16f
        paint.color = Color.rgb(11, 36, 33)
        paint.alpha = (250 * visibility).toInt()
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), height / 2f, height / 2f, paint)
        paint.strokeWidth = 2.6f * density
        paint.strokeCap = Paint.Cap.ROUND
        val spacing = minOf(5.8f * density, width.toFloat() / (envelope.size + 1))
        val center = envelope.lastIndex / 2f
        for (i in envelope.indices) {
            val x = width / 2f + (i - center) * spacing
            val recent = samples[(nextSample + i) % samples.size]
            val motion = if (animated) (.64 + .36 * sin(now / 88.0 - i * .7)).toFloat() else .8f
            val amplitude = (.60f * level * motion + .40f * recent) * visibility
            val bar = (1.2f + 12f * amplitude * envelope[i]) * density
            paint.color = if (i % 3 == 0) Color.rgb(213, 255, 238) else Color.rgb(92, 241, 194)
            paint.alpha = ((155 + 100 * envelope[i]) * visibility).toInt()
            canvas.drawLine(x, height / 2 - bar, x, height / 2 + bar, paint)
        }
        if (animated) postInvalidateOnAnimation() else postInvalidateDelayed(110)
    }
}
