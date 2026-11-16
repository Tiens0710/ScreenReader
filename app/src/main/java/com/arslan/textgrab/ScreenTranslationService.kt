package com.arslan.textgrab

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaPlayer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.roundToInt

/** No capture timer, automatic OCR, or network connection. Work starts only on a tap. */
class ScreenTranslationService : Service() {
    companion object {
        const val START = "textgrab.translate.START"
        const val STOP = "textgrab.translate.STOP"
        const val SHOW = "textgrab.translate.SHOW"
        private const val NOTIFICATION = 39
        private const val CHANNEL = "screen_translation"
    }
    private val main = Handler(Looper.getMainLooper())
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: LinearLayout? = null
    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var result: TextView? = null
    private var statusView: TextView? = null
    private var listen: TextView? = null
    private var retry: TextView? = null
    private var client: GeminiLiveClient? = null
    private var recording: TranslationRecording? = null
    private var player: MediaPlayer? = null
    private var translation = ""
    private var status = "Chạm nút Dịch trên trang bạn muốn dịch"
    private var busy = false
    private var audioReady = false
    private var width = 0
    private var height = 0
    @Volatile private var generation = 0
    @Volatile private var closing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> stopSelf()
            SHOW -> if (translation.isNotBlank() || busy) showResult() else updateStatus(status)
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
                            if (busy) fail(generation, "Màn hình vừa xoay. Chạm Dịch lại khi nội dung đã ổn định.")
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
            ScreenTranslationSession.reset(ScreenTranslationState(running = true, ready = true, status = status))
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
        if (closing || busy) return
        if (LiveSession.appVisible) { updateStatus("Mở trang cần dịch, rồi chạm nút Dịch nổi trên màn hình."); return }
        val memory = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        if (memory.lowMemory) { updateStatus("Máy thiếu bộ nhớ. Đóng bớt ứng dụng rồi thử lại."); showResult(); return }
        val key = GeminiCredentials.read(this)
        if (key.isBlank()) { updateStatus("Chưa có kết nối Gemini. Nhờ người hỗ trợ nhập khóa trong Cài đặt."); showResult(); return }
        val id = ++generation
        busy = true; audioReady = false; translation = ""
        stopPlayback(); client?.close(); client = null
        recording?.discard(); recording = null
        status = "Đang chụp màn hình…"
        overlay?.visibility = View.INVISIBLE
        ScreenTranslationSession.update { it.copy(status = status, translation = "") }
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
                    showResult(); updateStatus("Đang nhận dạng chữ…")
                    recognize(snapshot, id, key)
                }, main)
                display?.surface = source.surface
                main.postDelayed({ if (id == generation && reader != null)
                    fail(id, "Chưa chụp được màn hình. Hãy thử lại hoặc bật lại nút Dịch.") }, 5000)
            } catch (_: Exception) { fail(id, "Không chụp được màn hình. Hãy bật lại nút Dịch.") }
        }, 180)
    }

    private fun detachCapture() {
        runCatching { display?.surface = null }
        reader?.setOnImageAvailableListener(null, null)
        runCatching { reader?.close() }; reader = null
    }

    private fun recognize(snapshot: Bitmap, id: Int, key: String) {
        work.launch {
            val recognized = try {
                withContext(NonCancellable) {
                    val paddle = runCatching { PaddleLiveOcr.recognize(this@ScreenTranslationService, snapshot) {
                        closing || id != generation
                    } }.getOrDefault(emptyList())
                    if (closing || id != generation) ""
                    else if (paddle.isNotEmpty()) paddle.joinToString("\n") { it.text }
                    else OcrEngine.recognizeLatin(snapshot).fullText
                }
            } catch (_: Exception) { "" }
            finally { snapshot.recycle(); withContext(NonCancellable) { PaddleLiveOcr.release() } }
            ensureActive()
            main.post {
                if (closing || id != generation) return@post
                when {
                    recognized.isBlank() -> fail(id, "Không tìm thấy chữ rõ trong trang này. Thử phóng to chữ rồi Dịch lại.")
                    recognized.length > 8000 -> fail(id, "Trang có quá nhiều chữ. Hãy phóng to hoặc mở từng phần rồi Dịch lại.")
                    else -> translate(recognized, id, key)
                }
            }
        }.invokeOnCompletion { if (!snapshot.isRecycled) snapshot.recycle() }
    }

    private fun translate(text: String, id: Int, key: String) {
        updateStatus("Đang dịch sang tiếng Việt…")
        val audio = TranslationRecording(File(cacheDir, "screen-translation.wav"))
        recording = audio
        var sent = false
        val connection = GeminiLiveClient(key, object : GeminiLiveClient.Listener {
            override fun onReady() { main.post {
                if (id != generation || closing) return@post
                if (sent) { fail(id, "Kết nối bị gián đoạn. Chạm Dịch lại để thử lại."); return@post }
                sent = true
                if (client?.askText("TRANSLATE_SCREEN\n$text") != true) fail(id, "Không gửi được nội dung. Hãy thử lại.")
                else android.util.Log.i("ScreenTranslate", "One translation requested; chars=${text.length}")
            } }
            override fun onTranscript(text: String) { main.post {
                if (id == generation && !closing) { translation = text; render() }
            } }
            override fun onAudio(bytes: ByteArray) { if (id == generation && !closing) runCatching { audio.append(bytes) } }
            override fun onTurnComplete() { main.post {
                if (id != generation || closing) return@post
                client?.close(); client = null
                audioReady = runCatching { audio.finish() }.getOrDefault(false)
                busy = false
                updateStatus(if (translation.isBlank()) "Chưa nhận được bản dịch. Chạm Dịch lại để thử lại."
                    else if (audio.truncated) "Đã dịch. Bản nghe chỉ có phần đầu vì nội dung dài."
                    else "Đã dịch · chạm Nghe đọc nếu muốn nghe")
                android.util.Log.i("ScreenTranslate", "Translation complete; no automatic playback or next request")
            } }
            override fun onFatal(message: String) { main.post { fail(id, message) } }
            override fun onStatus(message: String) { main.post {
                if (id == generation && !closing) updateStatus(
                    if (message.startsWith("Đang kết nối")) "Đang kết nối để dịch…" else message)
            } }
            override fun onInterrupted() { main.post { fail(id, "Bản dịch bị ngắt. Hãy thử lại.") } }
            override fun onHighlight(regions: List<RectF>, allowNoRegion: Boolean) {}
        }, transcriptLimit = 24000)
        client = connection
        connection.connect()
        main.postDelayed({ if (id == generation && busy) fail(id, "Dịch lâu quá. Kiểm tra mạng rồi chạm Dịch lại.") }, 60000)
    }

    private fun fail(id: Int, message: String) {
        if (closing || id != generation) return
        ++generation; busy = false; audioReady = false
        client?.close(); client = null; recording?.discard(); recording = null
        detachCapture(); updateStatus(message); showResult()
    }

    private fun stopPlayback() {
        runCatching { player?.release() }; player = null
        listen?.text = "Nghe đọc"
    }

    private fun play() {
        if (busy || !audioReady) return
        if (player != null) { stopPlayback(); return }
        try {
            val media = MediaPlayer()
            player = media
            media.setDataSource(recording!!.file.absolutePath)
            media.setOnCompletionListener { stopPlayback() }
            media.setOnErrorListener { _, _, _ -> stopPlayback(); updateStatus("Không phát được giọng đọc. Bạn vẫn có thể đọc bản dịch."); true }
            media.setOnPreparedListener { if (player === it) { listen?.text = "Dừng nghe"; it.start() } }
            media.prepareAsync()
        } catch (_: Exception) { stopPlayback(); updateStatus("Không phát được giọng đọc. Hãy thử lại.") }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).roundToInt()
    private fun createOverlay() {
        val ctx = App.localizedContext(this)
        fun glassBackground(fillColor: Int, strokeColor: Int, radiusDp: Int = 18) = GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), strokeColor)
        }
        fun button(label: String, fillColor: Int, strokeColor: Int, textColor: Int = Color.WHITE, radiusDp: Int = 18, action: () -> Unit) = TextView(ctx).apply {
            text = label; textSize = 19f; gravity = Gravity.CENTER
            setTextColor(textColor); minimumHeight = dp(56); setPadding(dp(14), dp(14), dp(14), dp(14))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = glassBackground(fillColor, strokeColor, radiusDp)
            isClickable = true; isFocusable = true; setOnClickListener { action() }
        }
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        val params = WindowManager.LayoutParams(dp(88), -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.END or Gravity.TOP; x = dp(12); y = dp(140); title = "TextGrab Dịch" }
        bubble = button("Dịch", Color.argb(230, 15, 118, 110), Color.argb(200, 94, 234, 212), Color.WHITE, radiusDp = 24) { capture() }
        root.addView(bubble, LinearLayout.LayoutParams(dp(88), -2))
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(20), dp(20), dp(20), dp(20))
            background = glassBackground(Color.argb(242, 15, 23, 42), Color.argb(90, 56, 189, 248), radiusDp = 24)
        }
        statusView = TextView(ctx).apply { textSize = 16f; setTextColor(Color.argb(230, 94, 234, 212)); setTypeface(typeface, android.graphics.Typeface.BOLD) }
        body.addView(statusView)
        result = TextView(ctx).apply { textSize = 20f; setTextColor(Color.argb(255, 248, 250, 252)); setLineSpacing(dp(5).toFloat(), 1.1f); setTextIsSelectable(true) }
        val scroll = ScrollView(ctx).apply {
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = glassBackground(Color.argb(40, 255, 255, 255), Color.argb(45, 255, 255, 255), radiusDp = 16)
            addView(result)
        }
        body.addView(scroll, LinearLayout.LayoutParams(-1, dp(220)).apply { topMargin = dp(12) })
        listen = button("Nghe đọc", Color.argb(215, 13, 148, 136), Color.argb(180, 94, 234, 212), Color.WHITE, radiusDp = 18) { play() }
        retry = button("Dịch lại", Color.argb(170, 30, 41, 59), Color.argb(130, 56, 189, 248), Color.rgb(241, 245, 249), radiusDp = 18) { hideResult(); capture() }
        val closeBtn = button("Đóng", Color.argb(120, 51, 65, 85), Color.argb(90, 148, 163, 184), Color.rgb(203, 213, 225), radiusDp = 18) { hideResult() }
        for (view in listOf(listen, retry, closeBtn)) {
            body.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        // Keep long text / large fonts reachable on small and landscape displays.
        val outerScroll = object : ScrollView(ctx) {
            override fun onMeasure(w: Int, h: Int) { super.onMeasure(w,
                View.MeasureSpec.makeMeasureSpec((resources.displayMetrics.heightPixels - dp(96)).coerceAtLeast(dp(160)), View.MeasureSpec.AT_MOST)) }
        }.apply { addView(body); visibility = View.GONE }
        root.addView(outerScroll)
        panel = body; overlay = root
        root.tag = Pair(params, outerScroll)
        bubble!!.setOnTouchListener(object : View.OnTouchListener {
            var x = 0f; var y = 0f; var initialX = 0; var initialY = 0; var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { x = e.rawX; y = e.rawY; initialX = params.x; initialY = params.y; moved = false; return true }
                    MotionEvent.ACTION_MOVE -> {
                        if (kotlin.math.abs(e.rawX - x) + kotlin.math.abs(e.rawY - y) > dp(8)) moved = true
                        if (moved) { params.x = (initialX - (e.rawX - x).toInt()).coerceIn(0, (resources.displayMetrics.widthPixels - params.width).coerceAtLeast(0))
                            params.y = (initialY + (e.rawY - y).toInt()).coerceIn(0, (resources.displayMetrics.heightPixels - root.height).coerceAtLeast(0))
                            getSystemService(WindowManager::class.java).updateViewLayout(root, params) }
                        return true
                    }
                    MotionEvent.ACTION_UP -> { if (!moved) v.performClick(); return true }
                }; return false
            }
        })
        root.isFocusableInTouchMode = true
        root.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_BACK && panel?.visibility == View.VISIBLE) {
                if (event.action == KeyEvent.ACTION_UP) hideResult()
                true
            } else false
        }
        getSystemService(WindowManager::class.java).addView(root, params)
        render()
    }

    @Suppress("UNCHECKED_CAST") private fun showResult() {
        val root = overlay ?: return
        val (params, scroll) = root.tag as Pair<WindowManager.LayoutParams, ScrollView>
        params.width = dp(360).coerceAtMost(resources.displayMetrics.widthPixels - dp(24))
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        params.y = params.y.coerceAtMost(dp(48))
        panel?.visibility = View.VISIBLE; scroll.visibility = View.VISIBLE; bubble?.visibility = View.GONE
        root.visibility = View.VISIBLE
        runCatching { getSystemService(WindowManager::class.java).updateViewLayout(root, params) }
        root.requestFocus()
        render()
    }

    @Suppress("UNCHECKED_CAST") private fun hideResult() {
        if (busy) { ++generation; busy = false; client?.close(); client = null; recording?.discard(); recording = null; audioReady = false; detachCapture() }
        stopPlayback()
        val root = overlay ?: return
        val (params, scroll) = root.tag as Pair<WindowManager.LayoutParams, ScrollView>
        params.width = dp(88)
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        scroll.visibility = View.GONE; panel?.visibility = View.GONE; bubble?.visibility = View.VISIBLE
        root.visibility = View.VISIBLE
        runCatching { getSystemService(WindowManager::class.java).updateViewLayout(root, params) }
        updateStatus("Chạm nút Dịch trên trang bạn muốn dịch")
    }

    private fun updateStatus(message: String) { status = message; render() }
    private fun render() {
        statusView?.text = status
        result?.text = translation.ifBlank { if (busy) "Vui lòng chờ…" else "Bản dịch sẽ hiện ở đây." }
        listen?.isEnabled = audioReady && !busy; listen?.alpha = if (audioReady && !busy) 1f else .45f
        retry?.isEnabled = !busy; retry?.alpha = if (busy) .45f else 1f
        ScreenTranslationSession.update { it.copy(status = status, translation = translation) }
    }

    override fun onDestroy() {
        closing = true; ++generation; work.cancel(); main.removeCallbacksAndMessages(null)
        stopPlayback(); client?.close(); recording?.discard(); detachCapture()
        runCatching { display?.release() }; runCatching { projection?.stop() }
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }; overlay = null
        CoroutineScope(Dispatchers.IO).launch { work.coroutineContext[Job]?.join(); PaddleLiveOcr.release() }
        ScreenTranslationSession.reset(ScreenTranslationState(status = "Đã tắt nút Dịch"))
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
