package com.arslan.textgrab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** Reports the selected rectangle in display coordinates normalized to 0..1. */
internal class ScreenRegionSelectionView(
    context: Context,
    private val onSelect: (RectF) -> Unit,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(76, 11, 24, 22) }
    private val edgeShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(155, 12, 44, 38)
        style = Paint.Style.STROKE
        strokeWidth = 2.8f * density
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(124, 237, 202)
        style = Paint.Style.STROKE
        strokeWidth = 1.15f * density
    }
    private val grip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(214, 255, 239)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.5f * density
    }
    private val gripShadow = Paint(grip).apply {
        color = Color.rgb(0, 93, 76)
        strokeWidth = 4.5f * density
    }
    private val shadePath = Path()
    private val cornerPath = Path()
    private val selection = RectF()
    private var startX = 0f
    private var startY = 0f
    private var dragging = false

    override fun onDraw(canvas: Canvas) {
        if (selection.isEmpty) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
            return
        }
        val radius = minOf(8 * density, selection.width() / 4, selection.height() / 4)
        shadePath.rewind()
        shadePath.fillType = Path.FillType.EVEN_ODD
        shadePath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        shadePath.addRoundRect(selection, radius, radius, Path.Direction.CW)
        canvas.drawPath(shadePath, shade)
        canvas.drawRoundRect(selection, radius, radius, edgeShadow)
        canvas.drawRoundRect(selection, radius, radius, edge)

        val across = minOf(radius + 10 * density, selection.width() * .39f)
        val down = minOf(radius + 10 * density, selection.height() * .39f)
        cornerPath.rewind()
        cornerPath.moveTo(across, 0f)
        cornerPath.lineTo(radius, 0f)
        cornerPath.quadTo(0f, 0f, 0f, radius)
        cornerPath.lineTo(0f, down)
        drawCorner(canvas, selection.left, selection.top, 1f, 1f)
        drawCorner(canvas, selection.right, selection.top, -1f, 1f)
        drawCorner(canvas, selection.left, selection.bottom, 1f, -1f)
        drawCorner(canvas, selection.right, selection.bottom, -1f, -1f)
    }

    private fun drawCorner(canvas: Canvas, x: Float, y: Float, dx: Float, dy: Float) {
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(dx, dy)
        canvas.drawPath(cornerPath, gripShadow)
        canvas.drawPath(cornerPath, grip)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x.coerceIn(0f, width.toFloat())
        val y = event.y.coerceIn(0f, height.toFloat())
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = x
                startY = y
                dragging = true
                selection.setEmpty()
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                if (!dragging) return true
                selection.set(minOf(startX, x), minOf(startY, y), maxOf(startX, x), maxOf(startY, y))
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    dragging = false
                    if (selection.width() >= 16 * density && selection.height() >= 12 * density) {
                        onSelect(RectF(selection.left / width, selection.top / height,
                            selection.right / width, selection.bottom / height))
                    }
                    selection.setEmpty()
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                dragging = false
                selection.setEmpty()
            }
        }
        invalidate()
        return true
    }
}
