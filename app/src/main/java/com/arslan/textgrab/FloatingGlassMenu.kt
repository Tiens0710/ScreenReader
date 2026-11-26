package com.arslan.textgrab

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import android.widget.LinearLayout
import kotlin.math.sin

/** A separate window lets the menu follow a captured gesture without moving its touch target. */
internal class FloatingGlassMenu(
    private val context: Context,
    private val windows: WindowManager,
    private val dp: (Int) -> Int,
    private val top: () -> Int,
    private val onTranslate: () -> Unit,
    private val onGuide: () -> Unit,
    private val onLock: () -> Unit,
    private val onDismiss: () -> Unit,
) {
    private var panel: LinearLayout? = null
    private var layout: WindowManager.LayoutParams? = null
    private var opening: ValueAnimator? = null
    var isOpen = false
        private set

    fun follow(progress: Float) {
        if (isOpen || progress <= 0f) return
        val view = panel ?: attach() ?: return
        view.animate().cancel()
        // Leave room for the release to settle even after a long swipe.
        view.translationX = dp(34) * (1f - progress) + dp(3)
        view.scaleX = .48f + .47f * progress
        view.scaleY = .68f + .29f * progress
        view.alpha = (.08f + .92f * progress).coerceAtMost(1f)
        view.getChildAt(0).alpha = (progress * 1.15f - .24f).coerceIn(0f, .86f)
        view.getChildAt(2).alpha = (progress * 1.18f - .45f).coerceIn(0f, .73f)
        view.getChildAt(4).alpha = (progress * 1.22f - .61f).coerceIn(0f, .61f)
        (view.background as GlassSurface).shine(progress * .33f, progress * .46f)
    }

    fun open() {
        if (isOpen) return
        val view = panel ?: attach() ?: return
        val params = layout ?: return
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        if (runCatching { windows.updateViewLayout(view, params) }.isFailure) {
            dismiss()
            return
        }
        isOpen = true
        view.animate().cancel()
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        val glass = view.background as GlassSurface
        val startX = view.translationX
        val startScaleX = view.scaleX
        val startScaleY = view.scaleY
        val startAlpha = view.alpha
        val icons = listOf(view.getChildAt(0), view.getChildAt(2), view.getChildAt(4))
        val iconAlphas = icons.map { it.alpha }
        val spring = OvershootInterpolator(1.65f)
        opening = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 370
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val time = it.animatedFraction
                val settle = spring.getInterpolation((time * 1.27f).coerceAtMost(1f))
                val bounce = sin(Math.PI * time).toFloat() * (1f - time)
                view.translationX = startX * (1f - settle) - dp(5) * bounce
                view.scaleX = (startScaleX + (1f - startScaleX) * settle).coerceAtMost(1.035f)
                view.scaleY = (startScaleY + (1f - startScaleY) * settle).coerceAtMost(1.035f)
                view.alpha = startAlpha + (1f - startAlpha) * (time * 4f).coerceAtMost(1f)
                icons.forEachIndexed { index, icon ->
                    revealIcon(icon, iconAlphas[index], time, .07f + index * .115f)
                }
                glass.shine(.12f + time * .88f, sin(Math.PI * time).toFloat() * .9f)
            }
            start()
        }
    }

    private fun revealIcon(icon: View, startAlpha: Float, time: Float, delay: Float) {
        val progress = ((time - delay) / .53f).coerceIn(0f, 1f)
        val remaining = 1f - progress
        icon.alpha = 1f - (1f - startAlpha) * remaining * remaining
        icon.translationX = dp(6) * remaining * (1f - startAlpha)
    }

    fun dismiss(animated: Boolean = false) {
        val view = panel ?: return
        isOpen = false
        opening?.cancel()
        opening = null
        (view.background as GlassSurface).shine(1f, 0f)
        val params = layout
        if (params != null) {
            params.flags = inactiveFlags
            runCatching { windows.updateViewLayout(view, params) }
        }
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        view.animate().cancel()
        if (animated) {
            view.animate().translationX(dp(31).toFloat()).scaleX(.46f).scaleY(.72f).alpha(0f)
                .setInterpolator(DecelerateInterpolator()).setDuration(155)
                .withEndAction { if (panel === view && !isOpen) remove(view) }.start()
        } else remove(view)
        onDismiss()
    }

    private val inactiveFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

    private fun attach(): LinearLayout? {
        val view = object : LinearLayout(context) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    dismiss(animated = true)
                    return true
                }
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GlassSurface(dp(26).toFloat(), dp(1).toFloat())
            elevation = dp(5).toFloat()
            pivotX = dp(64).toFloat()
            pivotY = dp(79).toFloat()
            scaleX = .55f
            scaleY = .74f
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(action(R.drawable.ic_screen_translate_mark, R.string.edge_translate) {
                dismiss()
                onTranslate()
            })
            addView(separator())
            addView(action(R.drawable.ic_screen_guide, R.string.edge_guide) {
                dismiss()
                onGuide()
            })
            addView(separator())
            addView(action(R.drawable.ic_screen_power, R.string.edge_lock) {
                dismiss()
                onLock()
            })
        }
        val params = WindowManager.LayoutParams(dp(64), dp(158), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            inactiveFlags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.RIGHT or Gravity.TOP
            x = dp(12)
            y = top()
            title = context.getString(R.string.edge_menu_title)
        }
        return runCatching {
            windows.addView(view, params)
            layout = params
            panel = view
            view
        }.getOrNull()
    }

    private fun separator() = View(context).apply {
        setBackgroundColor(0x486781AE)
        layoutParams = LinearLayout.LayoutParams(dp(26), dp(1))
    }

    private fun action(icon: Int, label: Int, onClick: () -> Unit) = ImageButton(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        alpha = 0f
        contentDescription = context.getString(label)
        tooltipText = null
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(0xFF263D64.toInt())
        background = RippleDrawable(ColorStateList.valueOf(0x404C6B9D), null,
            GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.WHITE)
            })
        setOnClickListener { onClick() }
    }

    private class GlassSurface(private val radius: Float, stroke: Float) : Drawable() {
        private val area = RectF()
        private val matrix = Matrix()
        private val base = Paint(Paint.ANTI_ALIAS_FLAG)
        private val reflection = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 0 }
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = 0xF5FFFFFF.toInt()
        }
        private var band = 1f
        private var progress = 1f

        override fun onBoundsChange(bounds: Rect) {
            area.set(bounds)
            area.inset(rim.strokeWidth / 2f, rim.strokeWidth / 2f)
            if (area.isEmpty) return
            band = area.width() * .74f
            base.shader = LinearGradient(area.left, area.top, area.right, area.bottom,
                intArrayOf(0xFAF9FCFF.toInt(), 0xB6D4E2FC.toInt(), 0xE9EEF5FF.toInt()),
                floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
            reflection.shader = LinearGradient(0f, 0f, band, band * .42f,
                intArrayOf(0x00FFFFFF, 0xCCFFFFFF.toInt(), 0x00FFFFFF),
                floatArrayOf(0f, .52f, 1f), Shader.TileMode.CLAMP)
        }

        fun shine(position: Float, strength: Float) {
            progress = position
            reflection.alpha = (255 * strength).toInt().coerceIn(0, 255)
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            if (area.isEmpty) return
            canvas.drawRoundRect(area, radius, radius, base)
            if (reflection.alpha > 0) {
                matrix.setTranslate(area.right - (area.width() + band) * progress, area.top)
                reflection.shader?.setLocalMatrix(matrix)
                canvas.drawRoundRect(area, radius, radius, reflection)
            }
            canvas.drawRoundRect(area, radius, radius, rim)
        }

        override fun setAlpha(alpha: Int) {
            base.alpha = alpha
            rim.alpha = alpha
            invalidateSelf()
        }
        override fun setColorFilter(filter: ColorFilter?) {
            base.colorFilter = filter
            rim.colorFilter = filter
            invalidateSelf()
        }
        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun remove(view: View) {
        panel = null
        layout = null
        runCatching { windows.removeViewImmediate(view) }
    }
}
