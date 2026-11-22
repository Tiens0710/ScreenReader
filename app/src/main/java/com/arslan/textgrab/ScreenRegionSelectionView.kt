package com.arslan.textgrab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** Reports the selected rectangle in display coordinates normalized to 0..1. */
internal class ScreenRegionSelectionView(
    context: Context,
    private val onSelect: (RectF) -> Unit,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val shade = Paint().apply { color = Color.argb(92, 0, 0, 0) }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(112, 246, 200)
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }
    private val selection = RectF()
    private var startX = 0f
    private var startY = 0f
    private var dragging = false

    override fun onDraw(canvas: Canvas) {
        if (selection.isEmpty) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
            return
        }
        canvas.save()
        canvas.clipOutRect(selection)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
        canvas.restore()
        canvas.drawRect(selection, outline)
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
