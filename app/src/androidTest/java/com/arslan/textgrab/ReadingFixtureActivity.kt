package com.arslan.textgrab

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Synthetic, non-sensitive screen for end-to-end OCR/Live checks; exists only in the test APK. */
class ReadingFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24*d).toInt(), (48*d).toInt(), (24*d).toInt(), (32*d).toInt())
            setBackgroundColor(Color.WHITE)
        }
        fun text(value: String, size: Float, dark: Boolean = false) {
            body.addView(TextView(this).apply {
                this.text = value; textSize = size
                setTextColor(if (dark) Color.WHITE else Color.rgb(38,48,58))
                if (dark) setBackgroundColor(Color.rgb(24,39,47))
                setPadding((12*d).toInt(),(18*d).toInt(),(12*d).toInt(),(18*d).toInt())
            }, LinearLayout.LayoutParams(-1,-2))
        }
        text("Lịch học hôm nay", 24f)
        text("Đọc sách 20 phút", 16f)
        text("Nghỉ giải lao lúc 10 giờ", 13f)
        text("Cài đặt thông báo", 12f, true)
        text("Nội dung tiếng Việt có dấu", 11f, true)
        text("Choose your language", 14f)
        text("Automatic reading", 12f)
        text("Quay lại trang chính", 13f)
        setContentView(ScrollView(this).apply { addView(body) })
    }
}
