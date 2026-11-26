package com.arslan.textgrab

import android.content.Context
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar

internal class TranslationBubble(context: Context, private val dp: (Int) -> Int) : FrameLayout(context) {
    enum class State { READY, WAITING, READING, LISTENING, GUIDE_WAITING, FOLLOW_UP }

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
    private val activityIndicator = FrameLayout(context)
    private val rail = View(context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFFC9D8F7.toInt(), 0xFF526A99.toInt(), 0xFF849BC5.toInt())
        ).apply { cornerRadius = dp(4).toFloat() }
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
        minimumHeight = dp(64)
        isFocusable = true
        activityIndicator.addView(icon, LayoutParams(dp(26), dp(26), Gravity.CENTER))
        activityIndicator.addView(spinner, LayoutParams(dp(22), dp(22), Gravity.CENTER))
        addView(activityIndicator, LayoutParams(dp(46), dp(46), Gravity.END or Gravity.CENTER_VERTICAL))
        addView(rail, LayoutParams(dp(6), dp(42), Gravity.END or Gravity.CENTER_VERTICAL))
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

    val isReady: Boolean get() = state == State.READY

    fun followSwipe(inward: Float) {
        rail.animate().cancel()
        if (inward == 0f && ValueAnimator.areAnimatorsEnabled()) {
            rail.animate().scaleY(1f).setDuration(150).start()
        } else {
            rail.scaleY = 1f - (inward / dp(9)).coerceIn(0f, 1f) * .20f
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGestureExclusion()
    }

    private fun updateGestureExclusion() {
        if (Build.VERSION.SDK_INT >= 29) {
            systemGestureExclusionRects = if (isReady && width > 0 && height > 0)
                listOf(Rect(0, 0, width, height)) else emptyList()
        }
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
        updateGestureExclusion()
        rail.visibility = if (isReady) View.VISIBLE else View.GONE
        activityIndicator.visibility = if (isReady) View.GONE else View.VISIBLE
        val waiting = value == State.WAITING || value == State.GUIDE_WAITING
        spinner.visibility = if (waiting) View.VISIBLE else View.GONE
        icon.visibility = if (waiting) View.INVISIBLE else View.VISIBLE
        when (value) {
            State.READY -> {
                contentDescription = context.getString(R.string.edge_handle_description)
            }
            State.WAITING -> contentDescription = "Đang dịch. Chạm để dừng"
            State.GUIDE_WAITING -> contentDescription = "AI đang tìm bước tiếp theo. Chạm để dừng"
            State.LISTENING, State.FOLLOW_UP -> {
                icon.setImageResource(R.drawable.ic_screen_guide)
                icon.setColorFilter(if (value == State.LISTENING) Color.RED else teal)
                contentDescription = if (value == State.LISTENING) "Đang nghe. Chạm để hủy"
                    else "Hỏi tiếp hoặc yêu cầu nói lại"
            }
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
                State.WAITING, State.GUIDE_WAITING, State.LISTENING -> paper
                State.READING, State.FOLLOW_UP -> mint
            })
            setStroke(dp(1), if (value == State.READY) Color.rgb(59, 105, 93) else teal)
        }
        val ripple = if (value == State.READY) 0x2DFFFFFF else 0x26006B57
        activityIndicator.background = RippleDrawable(ColorStateList.valueOf(ripple), shape, null)
    }
}
