package com.arslan.textgrab

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlin.math.roundToInt

/** No capture timer, automatic OCR, or network connection. Work starts only on a tap. */
class ScreenTranslationService : Service() {
    companion object {
        const val START = "textgrab.translate.START"
        const val STOP = "textgrab.translate.STOP"
        private const val NOTIFICATION = 39
        private const val CHANNEL = "screen_translation"
    }
    private val main = Handler(Looper.getMainLooper())
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var bubble: TranslationBubble? = null
    private var highlight: View? = null
    private var speakingWave: SpeakingWaveView? = null
    private var lines = emptyList<CapturedLine>()
    private var lineIndex = 0
    private var lineTimeout: Runnable? = null
    private var responseComplete = false
    private var connectionReady = false
    private var requestSent = false
    private var tappedAt = 0L
    private var speechStarted = false
    private var client: GeminiLiveClient? = null
    private var audio: LiveAudio? = null
    private var busy = false
    private var width = 0
    private var height = 0
    @Volatile private var generation = 0
    @Volatile private var closing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> stopSelf()
            START -> if (projection == null) start(intent)
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        try {
            stopService(Intent(this, LiveScreenService::class.java))
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Nút dịch màn hình", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, LiveActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_translate)
                .setContentTitle("Nút Dịch đang bật")
                .setContentText("Chỉ chụp và dịch khi bạn bấm. Chạm để mở điều khiển.")
                .setContentIntent(open).setOngoing(true).addAction(0, "Tắt nút Dịch", stop).build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(NOTIFICATION, notification)
            check(Settings.canDrawOverlays(this)) { "Chưa cho phép nút nổi" }
            val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(LiveScreenService.EXTRA_DATA, Intent::class.java)
                else @Suppress("DEPRECATION") (intent.getParcelableExtra(LiveScreenService.EXTRA_DATA) as? Intent)
            checkNotNull(data)
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(
                intent.getIntExtra(LiveScreenService.EXTRA_RESULT, Activity.RESULT_CANCELED), data)
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { if (!closing) stopSelf() }
                override fun onCapturedContentResize(w: Int, h: Int) {
                    if (!closing && w > 0 && h > 0) {
                        val oldWidth = width; val oldHeight = height
                        setSize(w, h)
                        if (width != oldWidth || height != oldHeight) {
                            if (busy) fail(generation, "Màn hình vừa xoay. Chạm Dịch để thử lại.")
                            display?.resize(width, height, resources.displayMetrics.densityDpi)
                        }
                    }
                }
            }, main)
            setSize(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
            // A single virtual display per consent session, with no surface while idle.
            display = projection!!.createVirtualDisplay("TextGrabOnDemand", width, height,
                resources.displayMetrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                null, null, main)
            createOverlay()
            ScreenTranslationSession.reset(ScreenTranslationState(running = true, ready = true, status = "Chạm nút Dịch trên trang bạn muốn dịch"))
            android.util.Log.i("ScreenTranslate", "Ready; idle capture and network are off")
        } catch (_: Exception) {
            ScreenTranslationSession.reset(ScreenTranslationState(status = "Không bật được nút Dịch. Hãy cấp lại quyền chia sẻ màn hình."))
            stopSelf()
        }
    }

    private fun setSize(w: Int, h: Int) {
        val lowRam = getSystemService(ActivityManager::class.java).isLowRamDevice
        val scale = (if (lowRam) 1600f else 2200f).div(maxOf(w, h)).coerceAtMost(1f)
        width = (w * scale).roundToInt().coerceAtLeast(1)
        height = (h * scale).roundToInt().coerceAtLeast(1)
    }

    private fun capture() {
        if (closing) return
        if (busy) {
            finishRequest()
            updateStatus("Đã dừng. Chạm Dịch để đọc lại.")
            return
        }
        if (LiveSession.appVisible) { updateStatus("Mở trang cần dịch, rồi chạm nút Dịch nổi trên màn hình."); return }
        val memory = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        if (memory.lowMemory) { updateStatus("Máy thiếu bộ nhớ. Đóng bớt ứng dụng rồi thử lại."); return }
        val key = GeminiCredentials.read(this)
        if (key.isBlank()) { updateStatus("Chưa có kết nối Gemini. Nhờ người hỗ trợ nhập khóa trong Cài đặt."); return }
        val id = ++generation
        busy = true
        tappedAt = SystemClock.elapsedRealtime()
        bubble?.visibility = View.INVISIBLE
        updateStatus("Đang chụp màn hình…")
        // Hide our controls before attaching the capture surface.
        main.postDelayed({
            if (closing || id != generation) return@postDelayed
            try {
                val source = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                reader = source
                source.setOnImageAvailableListener({ incoming ->
                    if (incoming !== reader || id != generation) return@setOnImageAvailableListener
                    val image = runCatching { incoming.acquireLatestImage() }.getOrNull()
                        ?: return@setOnImageAvailableListener
                    var bitmap: Bitmap? = null
                    try {
                        val plane = image.planes[0]
                        val paddedWidth = image.width + (plane.rowStride - plane.pixelStride * image.width) / plane.pixelStride
                        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                        try {
                            padded.copyPixelsFromBuffer(plane.buffer)
                            bitmap = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                        } finally { if (bitmap !== padded) padded.recycle() }
                    } catch (_: Exception) {
                        fail(id, "Không chụp được trang này. Hãy thử lại trên trang khác.")
                    } catch (_: OutOfMemoryError) {
                        fail(id, "Máy không đủ bộ nhớ. Đóng bớt ứng dụng rồi thử lại.")
                    } finally { image.close(); detachCapture() }
                    val snapshot = bitmap ?: return@setOnImageAvailableListener
                    android.util.Log.i("ScreenTranslate", "One snapshot acquired; capture surface detached")
                    bubble?.visibility = View.VISIBLE
                    updateStatus("Đang tìm chữ…")
                    recognize(snapshot, id)
                }, main)
                display?.surface = source.surface
                main.postDelayed({ if (id == generation && reader != null)
                    fail(id, "Chưa chụp được màn hình. Hãy thử lại hoặc bật lại nút Dịch.") }, 5000)
            } catch (_: Exception) { fail(id, "Không chụp được màn hình. Hãy bật lại nút Dịch.") }
        }, 180)
        connect(id, key)
    }

    private fun detachCapture() {
        runCatching { display?.surface = null }
        reader?.setOnImageAvailableListener(null, null)
        runCatching { reader?.close() }; reader = null
    }

    private data class CapturedLine(val text: String, val box: RectF)

    private fun recognize(snapshot: Bitmap, id: Int) {
        work.launch {
            val recognized = try {
                withContext(NonCancellable) {
                    val paddle = runCatching {
                        PaddleLiveOcr.recognize(this@ScreenTranslationService, snapshot) { closing || id != generation }
                    }.getOrDefault(emptyList())
                    val detected = when {
                        closing || id != generation -> emptyList()
                        paddle.isNotEmpty() -> paddle.map { CapturedLine(it.text, it.bounds) }
                        else -> OcrEngine.recognizeLatin(snapshot).let { result ->
                            result.lineTexts.zip(result.lineBoxes, ::CapturedLine)
                        }
                    }
                    detected.mapNotNull { line ->
                        val clipped = RectF(line.box)
                        if (line.text.isBlank() ||
                            !clipped.intersect(0f, 0f, snapshot.width.toFloat(), snapshot.height.toFloat())) {
                            return@mapNotNull null
                        }
                        line.copy(box = RectF(clipped.left / snapshot.width, clipped.top / snapshot.height,
                            clipped.right / snapshot.width, clipped.bottom / snapshot.height))
                    }
                }
            } catch (_: Exception) { emptyList() }
            finally { snapshot.recycle() }
            try {
                ensureActive()
                main.post {
                    if (closing || id != generation) return@post
                    when {
                        recognized.isEmpty() ->
                            fail(id, "Không tìm thấy chữ rõ. Hãy phóng to rồi chạm Dịch.")
                        recognized.sumOf { it.text.length + 1 } > 8000 ->
                            fail(id, "Trang có quá nhiều chữ. Hãy mở từng phần rồi chạm Dịch.")
                        else -> {
                            lines = recognized
                            updateStatus("Đang dịch sang tiếng Việt…")
                            sendWhenReady(id)
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) { PaddleLiveOcr.release() }
            }
        }.invokeOnCompletion { if (!snapshot.isRecycled) snapshot.recycle() }
    }

    private fun sendWhenReady(id: Int) {
        val line = lines.getOrNull(lineIndex) ?: return
        if (!connectionReady || requestSent) return
        if (client?.askText("TRANSLATE_SCREEN\n${line.text}") != true) {
            fail(id, "Không gửi được nội dung. Hãy chạm Dịch để thử lại.")
            return
        }
        requestSent = true
        val index = lineIndex
        android.util.Log.i("ScreenTranslate", "Line ${index + 1}/${lines.size} sent")
        lineTimeout?.let(main::removeCallbacks)
        lineTimeout = Runnable {
            if (id == generation && index == lineIndex) {
                fail(id, "Dịch lâu quá. Hãy kiểm tra mạng rồi thử lại.")
            }
        }.also { main.postDelayed(it, 60_000) }
    }

    private fun advanceAfterPlayback(id: Int) {
        if (closing || id != generation || !responseComplete || audio?.hasPendingPlayback != false) return
        clearLinePlayback()
        lineIndex++
        if (lineIndex == lines.size) {
            finishRequest()
            updateStatus("Đã đọc xong")
        } else {
            updateStatus("Đang dịch dòng tiếp theo…")
            sendWhenReady(id)
        }
    }

    private fun connect(id: Int, key: String) {
        fun isCurrent() = id == generation && !closing
        val connection = GeminiLiveClient(key, object : GeminiLiveClient.Listener {
            override fun onReady() { main.post {
                if (!isCurrent()) return@post
                if (requestSent) {
                    fail(id, "Kết nối bị gián đoạn. Hãy chạm Dịch để thử lại.")
                    return@post
                }
                connectionReady = true
                sendWhenReady(id)
            } }
            override fun onTranscript(text: String) {}
            override fun onAudio(bytes: ByteArray) { main.post {
                if (!isCurrent() || !requestSent || responseComplete || bytes.size < 2) return@post
                val playback = audio ?: try {
                    val index = lineIndex
                    var lastLevelAt = 0L
                    LiveAudio(onLevel = { level ->
                        val now = SystemClock.uptimeMillis()
                        if ((lastLevelAt != 0L || level > 0f) && now - lastLevelAt >= 33) {
                            lastLevelAt = now
                            main.post {
                                if (isCurrent() && index == lineIndex) updateSpeechIndicator(level)
                            }
                        }
                    }, onIdle = { main.post {
                        if (index == lineIndex) advanceAfterPlayback(id)
                    } }).also { audio = it }
                } catch (_: Exception) {
                    fail(id, "Không mở được âm thanh. Hãy chạm Dịch để thử lại.")
                    return@post
                }
                playback.play(bytes)
            } }
            override fun onTurnComplete() { main.post {
                if (!isCurrent() || !requestSent || responseComplete) return@post
                responseComplete = true
                if (audio == null) fail(id, "Chưa nhận được giọng đọc. Hãy chạm Dịch để thử lại.")
                else advanceAfterPlayback(id)
            } }
            override fun onFatal(message: String) { main.post { fail(id, message) } }
            override fun onStatus(message: String) {
                if (message.startsWith("Mất kết nối")) main.post {
                    if (isCurrent()) connectionReady = false
                }
            }
            override fun onInterrupted() { main.post { fail(id, "Bản đọc bị ngắt. Hãy chạm Dịch để thử lại.") } }
            override fun onHighlight(regions: List<RectF>, allowNoRegion: Boolean) {}
        }, transcriptLimit = 1)
        client = connection
        connection.connect()
        lineTimeout = Runnable {
            if (isCurrent() && !requestSent) fail(id, "Dịch lâu quá. Hãy kiểm tra mạng rồi thử lại.")
        }.also { main.postDelayed(it, 60_000) }
    }

    private fun updateSpeechIndicator(level: Float) {
        if (level > 0f && !speechStarted) {
            speechStarted = true
            android.util.Log.i("ScreenTranslate", "Reading line ${lineIndex + 1}/${lines.size} after ${SystemClock.elapsedRealtime() - tappedAt} ms")
            showHighlight(lines[lineIndex].box)
            updateStatus("Đang đọc tiếng Việt…")
        }
        speakingWave?.setLevel(level)
    }

    private fun fail(id: Int, message: String) {
        if (closing || id != generation) return
        finishRequest()
        updateStatus(message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun finishRequest() {
        ++generation
        busy = false
        client?.close()
        client = null
        clearLinePlayback()
        detachCapture()
        lines = emptyList()
        lineIndex = 0
        connectionReady = false
        bubble?.visibility = View.VISIBLE
        bubble?.show(TranslationBubble.State.READY)
    }

    private fun clearLinePlayback() {
        lineTimeout?.let(main::removeCallbacks)
        lineTimeout = null
        audio?.close()
        audio = null
        removeOverlay(highlight)
        highlight = null
        speakingWave = null
        speechStarted = false
        responseComplete = false
        requestSent = false
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).roundToInt()

    private fun createOverlay() {
        val windows = getSystemService(WindowManager::class.java)
        val params = WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.RIGHT or Gravity.TOP
            x = dp(12)
            y = dp(140)
            title = getString(R.string.app_name)
        }
        val button = TranslationBubble(App.localizedContext(this), ::dp).apply {
            setOnClickListener { capture() }
        }
        button.setOnTouchListener(object : View.OnTouchListener {
            var x = 0f
            var y = 0f
            var initialX = 0
            var initialY = 0
            var moved = false
            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        x = event.rawX
                        y = event.rawY
                        initialX = params.x
                        initialY = params.y
                        moved = false
                        view.isPressed = true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (kotlin.math.abs(event.rawX - x) + kotlin.math.abs(event.rawY - y) > dp(8)) moved = true
                        if (moved) {
                            view.isPressed = false
                            params.x = (initialX - (event.rawX - x).toInt()).coerceIn(
                                0, (resources.displayMetrics.widthPixels - view.width).coerceAtLeast(0))
                            params.y = (initialY + (event.rawY - y).toInt()).coerceIn(
                                0, (resources.displayMetrics.heightPixels - view.height).coerceAtLeast(0))
                            windows.updateViewLayout(view, params)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        view.isPressed = false
                        if (!moved) view.performClick()
                    }
                    MotionEvent.ACTION_CANCEL -> view.isPressed = false
                }
                return true
            }
        })
        windows.addView(button, params)
        bubble = button
    }

    private fun showHighlight(box: RectF) {
        val view = object : View(this) {
            private val density = resources.displayMetrics.density
            private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(28, 76, 222, 174)
            }
            private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.5f * density
                color = Color.rgb(0, 139, 112)
            }
            private val rect = RectF()

            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                val edge = outline.strokeWidth / 2
                rect.set(
                    (box.left * w - 4 * density).coerceAtLeast(edge),
                    (box.top * h - 2 * density).coerceAtLeast(edge),
                    (box.right * w + 4 * density).coerceAtMost(w - edge),
                    (box.bottom * h + 2 * density).coerceAtMost(h - edge)
                )
            }

            override fun onDraw(canvas: Canvas) {
                if (rect.isEmpty) return
                val radius = minOf(7 * density, rect.height() / 3)
                canvas.drawRoundRect(rect, radius, radius, fill)
                canvas.drawRoundRect(rect, radius, radius, outline)
            }
        }.apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            alpha = 0f
        }
        val params = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            alpha = .65f
            title = "${getString(R.string.app_name)} reading highlight"
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val wave = SpeakingWaveView(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val wavePosition = FrameLayout.LayoutParams(dp(164), dp(34), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = dp(38) }
        val layer = FrameLayout(this).apply {
            addView(view, FrameLayout.LayoutParams(-1, -1))
            addView(wave, wavePosition)
            setOnApplyWindowInsetsListener { _, insets ->
                val top = androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(insets, this)
                    .getInsetsIgnoringVisibility(androidx.core.view.WindowInsetsCompat.Type.statusBars() or
                        androidx.core.view.WindowInsetsCompat.Type.displayCutout()).top
                wavePosition.topMargin = maxOf(top, dp(26)) + dp(6)
                wave.requestLayout()
                insets
            }
        }
        runCatching { getSystemService(WindowManager::class.java).addView(layer, params) }
            .onSuccess {
                highlight = layer
                speakingWave = wave
                view.animate().alpha(1f).setDuration(115).start()
            }
    }

    private fun removeOverlay(view: View?) {
        if (view != null) runCatching { getSystemService(WindowManager::class.java).removeViewImmediate(view) }
    }

    private fun updateStatus(message: String) {
        bubble?.show(when {
            !busy -> TranslationBubble.State.READY
            speechStarted -> TranslationBubble.State.READING
            else -> TranslationBubble.State.WAITING
        })
        ScreenTranslationSession.update { it.copy(status = message) }
    }

    override fun onDestroy() {
        closing = true; ++generation; work.cancel(); main.removeCallbacksAndMessages(null)
        finishRequest()
        runCatching { display?.release() }; runCatching { projection?.stop() }
        removeOverlay(bubble)
        bubble = null
        CoroutineScope(Dispatchers.IO).launch { work.coroutineContext[Job]?.join(); PaddleLiveOcr.release() }
        ScreenTranslationSession.reset(ScreenTranslationState(status = "Đã tắt nút Dịch"))
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
