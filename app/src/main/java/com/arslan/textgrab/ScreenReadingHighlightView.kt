package com.arslan.textgrab

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.DecelerateInterpolator

/** Draws speech activity around the active line without moving the text's bounds. */
internal class ScreenReadingHighlightView(context: Context, private val box: RectF) : View(context) {
    private val density = resources.displayMetrics.density
    private val rect = RectF()
    private val rounded = Path()
    private val corner = Path()
    private val sweepMatrix = Matrix()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(75, 231, 183) }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1.5f
        color = Color.rgb(0, 129, 101)
    }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 2.6f
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(79, 245, 193)
    }
    private val sweep = Paint(Paint.ANTI_ALIAS_FLAG)
    private var radius = 0f
    private var sweepWidth = 0f
    private var voice = 0f
    private var reveal = 1f
    private var entrance: ValueAnimator? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!ValueAnimator.areAnimatorsEnabled()) return
        entrance = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                reveal = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun setLevel(level: Float) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val target = kotlin.math.sqrt(level.coerceIn(0f, 1f))
        voice += (target - voice) * if (target > voice) .55f else .28f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        entrance?.cancel()
        entrance = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val edge = accent.strokeWidth / 2
        rect.set(
            (box.left * w - 4 * density).coerceAtLeast(edge),
            (box.top * h - 3 * density).coerceAtLeast(edge),
            (box.right * w + 4 * density).coerceAtMost(w - edge),
            (box.bottom * h + 3 * density).coerceAtMost(h - edge)
        )
        if (rect.isEmpty) return
        radius = minOf(8 * density, rect.height() / 3, rect.width() / 3)
        rounded.rewind()
        rounded.addRoundRect(rect, radius, radius, Path.Direction.CW)
        sweepWidth = minOf(56 * density, rect.width() * .48f)
        sweep.shader = LinearGradient(0f, 0f, sweepWidth, 0f,
            intArrayOf(Color.TRANSPARENT, Color.argb(78, 87, 255, 203), Color.TRANSPARENT),
            floatArrayOf(0f, .68f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        if (rect.isEmpty) return
        fill.alpha = (25 + 28 * voice).toInt()
        canvas.drawRoundRect(rect, radius, radius, fill)
        if (reveal < 1f) {
            val startX = rect.left - sweepWidth + (rect.width() + sweepWidth) * reveal
            sweepMatrix.setTranslate(startX, 0f)
            sweep.shader?.setLocalMatrix(sweepMatrix)
            canvas.save()
            canvas.clipPath(rounded)
            canvas.drawRect(startX, rect.top, startX + sweepWidth, rect.bottom, sweep)
            canvas.restore()
        }
        canvas.drawRoundRect(rect, radius, radius, outline)
        val tail = minOf(radius + (3 + 5 * voice) * density, rect.width() * .38f)
        val side = minOf(radius + (2 + 4 * voice) * density, rect.height() * .44f)
        accent.alpha = (176 + 79 * voice).toInt()
        corner.rewind()
        corner.moveTo(tail, 0f)
        corner.lineTo(radius, 0f)
        corner.quadTo(0f, 0f, 0f, radius)
        corner.lineTo(0f, side)
        drawCorner(canvas, rect.left, rect.top, 1f, 1f)
        drawCorner(canvas, rect.right, rect.top, -1f, 1f)
        drawCorner(canvas, rect.left, rect.bottom, 1f, -1f)
        drawCorner(canvas, rect.right, rect.bottom, -1f, -1f)
    }

    private fun drawCorner(canvas: Canvas, x: Float, y: Float, dx: Float, dy: Float) {
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(dx, dy)
        canvas.drawPath(corner, accent)
        canvas.restore()
    }
}
