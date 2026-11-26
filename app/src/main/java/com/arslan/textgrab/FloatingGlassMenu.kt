package com.arslan.textgrab

import android.animation.ValueAnimator
import androidx.core.animation.doOnEnd
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
import android.view.animation.PathInterpolator
import android.widget.ImageButton
import android.widget.LinearLayout

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
    private var transition: ValueAnimator? = null
    private var reveal = 0f
    private val openCurve = PathInterpolator(.2f, .85f, .25f, 1f)
    private val closeCurve = PathInterpolator(.55f, 0f, .78f, .4f)
    var isOpen = false
        private set

    fun follow(progress: Float) {
        if (isOpen || (panel == null && progress <= 0f)) return
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val view = panel ?: attach() ?: return
        cancelTransition()
        render(view, progress * .88f)
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
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        animateTo(view, 1f, 280)
    }

    private fun render(view: LinearLayout, progress: Float) {
        reveal = progress
        val remaining = 1f - progress
        view.translationX = dp(12) * remaining
        view.scaleX = .10f + .90f * progress
        view.scaleY = .26f + .74f * progress
        view.alpha = (progress * 3.8f).coerceAtMost(1f)
        for (index in 0..2) {
            val icon = view.getChildAt(index * 2)
            val offset = index - 1
            val threshold = if (offset == 0) .22f else if (offset < 0) .39f else .48f
            val unfold = ((progress - threshold) / (1f - threshold)).coerceIn(0f, 1f)
            val ease = 1f - (1f - unfold) * (1f - unfold)
            icon.alpha = ease
            icon.translationY = -offset * dp(24) * (1f - ease)
            icon.translationX = dp(15) * (1f - ease)
            icon.scaleX = .78f + .22f * ease
            icon.scaleY = icon.scaleX
        }
        val dividers = ((progress - .60f) / .40f).coerceIn(0f, 1f)
        view.getChildAt(1).alpha = dividers
        view.getChildAt(3).alpha = dividers
        (view.background as GlassSurface).shine(progress, 2.6f * progress * remaining)
    }

    private fun cancelTransition() {
        val running = transition
        transition = null
        running?.cancel()
    }

    private fun animateTo(view: LinearLayout, target: Float, durationMs: Long) {
        cancelTransition()
        if (!ValueAnimator.areAnimatorsEnabled() || reveal == target) {
            render(view, target)
            if (target == 0f) remove(view)
            return
        }
        val animation = ValueAnimator.ofFloat(reveal, target).apply {
            duration = durationMs
            interpolator = if (target == 1f) openCurve else closeCurve
            addUpdateListener { render(view, it.animatedValue as Float) }
            doOnEnd {
                if (transition !== it) return@doOnEnd
                transition = null
                if (target == 0f) remove(view)
            }
        }
        transition = animation
        animation.start()
    }

    fun dismiss(animated: Boolean = false) {
        val view = panel ?: return
        isOpen = false
        cancelTransition()
        val params = layout
        if (params != null) {
            params.flags = inactiveFlags
            runCatching { windows.updateViewLayout(view, params) }
        }
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        if (animated) animateTo(view, 0f, 190) else remove(view)
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
        render(view, 0f)
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
                intArrayOf(0xF6FEFFFF.toInt(), 0xCDDFE9FA.toInt(), 0xE3E8F2FF.toInt(), 0xF4C7D7EE.toInt()),
                floatArrayOf(0f, .43f, .77f, 1f), Shader.TileMode.CLAMP)
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
        reveal = 0f
        panel = null
        layout = null
        runCatching { windows.removeViewImmediate(view) }
    }
}
