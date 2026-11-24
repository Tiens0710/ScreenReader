package com.arslan.textgrab

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.LinearLayout

/** A separate window lets the menu follow a captured gesture without moving its touch target. */
internal class FloatingGlassMenu(
    private val context: Context,
    private val windows: WindowManager,
    private val dp: (Int) -> Int,
    private val top: () -> Int,
    private val onTranslate: () -> Unit,
    private val onLock: () -> Unit,
    private val onDismiss: () -> Unit,
) {
    private var panel: LinearLayout? = null
    private var layout: WindowManager.LayoutParams? = null
    var isOpen = false
        private set

    fun follow(progress: Float) {
        if (isOpen || progress <= 0f) return
        val view = panel ?: attach() ?: return
        view.animate().cancel()
        view.translationX = dp(25) * (1f - progress)
        view.scaleX = .55f + .45f * progress
        view.scaleY = .74f + .26f * progress
        view.alpha = progress
        view.getChildAt(0).alpha = (progress * 1.5f - .25f).coerceIn(0f, 1f)
        view.getChildAt(2).alpha = (progress * 1.7f - .55f).coerceIn(0f, 1f)
    }

    fun open() {
        val view = panel ?: attach() ?: return
        val params = layout ?: return
        isOpen = true
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        windows.updateViewLayout(view, params)
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        view.animate().translationX(0f).scaleX(1f).scaleY(1f).alpha(1f)
            .setInterpolator(DecelerateInterpolator()).setDuration(205).start()
        view.getChildAt(0).animate().alpha(1f).setDuration(145).start()
        view.getChildAt(2).animate().alpha(1f).setStartDelay(35).setDuration(145).start()
    }

    fun dismiss(animated: Boolean = false) {
        val view = panel ?: return
        isOpen = false
        val params = layout
        if (params != null) {
            params.flags = inactiveFlags
            runCatching { windows.updateViewLayout(view, params) }
        }
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        view.animate().cancel()
        if (animated) {
            view.animate().translationX(dp(22).toFloat()).scaleX(.55f).scaleY(.74f).alpha(0f)
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
            background = glass()
            elevation = dp(5).toFloat()
            pivotX = dp(64).toFloat()
            pivotY = dp(56).toFloat()
            scaleX = .55f
            scaleY = .74f
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(action(R.drawable.ic_screen_translate_mark, R.string.edge_translate) {
                dismiss()
                onTranslate()
            })
            addView(View(context).apply { setBackgroundColor(0x486781AE) },
                LinearLayout.LayoutParams(dp(26), dp(1)))
            addView(action(R.drawable.ic_screen_power, R.string.edge_lock) {
                dismiss()
                onLock()
            })
        }
        val params = WindowManager.LayoutParams(dp(64), dp(109), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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

    private fun glass() = GradientDrawable(GradientDrawable.Orientation.TL_BR,
        intArrayOf(0xFAF9FCFF.toInt(), 0xB8CEDCF7.toInt(), 0xEBEDF4FF.toInt())).apply {
        cornerRadius = dp(26).toFloat()
        setStroke(dp(1), 0xF3FFFFFF.toInt())
    }

    private fun remove(view: View) {
        panel = null
        layout = null
        runCatching { windows.removeViewImmediate(view) }
    }
}
