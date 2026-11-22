package com.arslan.textgrab

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar

internal class TranslationBubble(context: Context, private val dp: (Int) -> Int) : FrameLayout(context) {
    enum class State { READY, WAITING, READING }

    private val ink = Color.rgb(15, 44, 39)
    private val paper = Color.rgb(248, 252, 249)
    private val mint = Color.rgb(165, 242, 205)
    private val teal = Color.rgb(0, 107, 87)
    private val icon = ImageView(context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(teal)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var state: State? = null
    private var interacting = false
    private val fadeWhenIdle = Runnable {
        if (state == State.READY && !interacting) {
            animate().alpha(.48f).setDuration(240).start()
        }
    }

    init {
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        elevation = dp(3).toFloat()
        isFocusable = true
        addView(icon, LayoutParams(dp(26), dp(26), Gravity.CENTER))
        addView(spinner, LayoutParams(dp(22), dp(22), Gravity.CENTER))
        show(State.READY)
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshOpacity()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(fadeWhenIdle)
        animate().cancel()
        interacting = false
        super.onDetachedFromWindow()
    }

    fun setInteracting(value: Boolean) {
        interacting = value
        refreshOpacity()
    }

    private fun refreshOpacity() {
        removeCallbacks(fadeWhenIdle)
        animate().cancel()
        alpha = 1f
        if (isAttachedToWindow && state == State.READY && !interacting) {
            postDelayed(fadeWhenIdle, 3_000)
        }
    }

    fun show(value: State) {
        if (state == value) return
        state = value
        refreshOpacity()
        val waiting = value == State.WAITING
        spinner.visibility = if (waiting) View.VISIBLE else View.GONE
        icon.visibility = if (waiting) View.INVISIBLE else View.VISIBLE
        when (value) {
            State.READY -> {
                icon.clearColorFilter()
                icon.setImageResource(R.drawable.ic_screen_translate_mark)
                contentDescription = "Dịch và đọc màn hình"
            }
            State.WAITING -> contentDescription = "Đang dịch. Chạm để dừng"
            State.READING -> {
                icon.setImageResource(R.drawable.ic_stop_reading)
                icon.setColorFilter(teal)
                contentDescription = "Dừng đọc"
            }
        }
        val shape = GradientDrawable().apply {
            this.shape = GradientDrawable.OVAL
            setColor(when (value) {
                State.READY -> ink
                State.WAITING -> paper
                State.READING -> mint
            })
            setStroke(dp(1), if (value == State.READY) Color.rgb(59, 105, 93) else teal)
        }
        val ripple = if (value == State.READY) 0x2DFFFFFF else 0x26006B57
        background = RippleDrawable(ColorStateList.valueOf(ripple), shape, null)
    }
}
