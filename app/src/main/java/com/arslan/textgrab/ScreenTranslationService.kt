package com.arslan.textgrab

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
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
import kotlin.math.ceil
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
    private var floatingMenu: FloatingGlassMenu? = null
    private var highlight: View? = null
    private var lineHighlight: ScreenReadingHighlightView? = null
    private var regionPicker: View? = null
    private var speakingWave: SpeakingWaveView? = null
    private var lines = emptyList<CapturedLine>()
    private var lineIndex = 0
    private var requestTimeout: Runnable? = null
    private var emptyAudioRetry = false
    private var receivedAudiblePcm = false
    private var fallbackRecording: TranslationRecording? = null
    private var fallbackPlayer: MediaPlayer? = null
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
                            if (regionPicker != null) dismissRegionPicker()
                            floatingMenu?.dismiss()
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

    private fun chooseRegion() {
        if (closing || regionPicker != null) return
        if (busy) {
            finishRequest()
            updateStatus("Đã dừng. Chạm Dịch để chọn vùng khác.")
            return
        }
        if (LiveSession.appVisible) { updateStatus("Mở trang cần dịch rồi chạm nút Dịch nổi."); return }
        val key = GeminiCredentials.read(this)
        if (key.isBlank()) {
            Toast.makeText(this, "Hãy nhập khóa Gemini trong Screen Reader.", Toast.LENGTH_LONG).show()
            return
        }
        val picker = FrameLayout(this)
        picker.addView(ScreenRegionSelectionView(this) { region ->
            dismissRegionPicker(showBubble = false)
            capture(region, key)
        }, FrameLayout.LayoutParams(-1, -1))
        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(4), dp(6), dp(4))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(15, 44, 39))
                cornerRadius = dp(16).toFloat()
            }
            addView(TextView(context).apply {
                text = "Kéo để khoanh vùng chữ"
                textSize = 16f
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Button(context).apply {
                text = "Hủy"
                setTextColor(Color.rgb(165, 242, 205))
                background = null
                minHeight = dp(48)
                setOnClickListener { dismissRegionPicker() }
            })
        }
        val toolbar = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(dp(16), 0, dp(16), dp(36))
        }
        picker.addView(controls, toolbar)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(picker) { _, insets ->
            val bars = insets.getInsetsIgnoringVisibility(androidx.core.view.WindowInsetsCompat.Type.navigationBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            toolbar.bottomMargin = maxOf(bars.bottom, dp(16)) + dp(8)
            controls.requestLayout()
            insets
        }
        picker.isFocusableInTouchMode = true
        picker.setOnKeyListener { _, code, event ->
            if (code != KeyEvent.KEYCODE_BACK) false
            else {
                if (event.action == KeyEvent.ACTION_UP) dismissRegionPicker()
                true
            }
        }
        val params = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            title = "${getString(R.string.app_name)} region selection"
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        runCatching { getSystemService(WindowManager::class.java).addView(picker, params) }
            .onSuccess {
                regionPicker = picker
                bubble?.visibility = View.INVISIBLE
                picker.requestFocus()
            }.onFailure {
                Toast.makeText(this, "Không mở được vùng chọn. Hãy thử trên trang khác.", Toast.LENGTH_LONG).show()
            }
    }

    private fun dismissRegionPicker(showBubble: Boolean = true) {
        removeOverlay(regionPicker)
        regionPicker = null
        if (showBubble) bubble?.visibility = View.VISIBLE
    }

    private fun capture(region: RectF, key: String) {
        val memory = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        if (memory.lowMemory) {
            bubble?.visibility = View.VISIBLE
            Toast.makeText(this, "Máy thiếu bộ nhớ. Đóng bớt ứng dụng rồi thử lại.", Toast.LENGTH_LONG).show()
            return
        }
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
                    val left = (region.left * image.width).toInt().coerceIn(0, image.width - 1)
                    val top = (region.top * image.height).toInt().coerceIn(0, image.height - 1)
                    val right = ceil(region.right * image.width).toInt().coerceIn(left + 1, image.width)
                    val bottom = ceil(region.bottom * image.height).toInt().coerceIn(top + 1, image.height)
                    val crop = Selection(left, top, image.width, image.height)
                    try {
                        val plane = image.planes[0]
                        val paddedWidth = image.width + (plane.rowStride - plane.pixelStride * image.width) / plane.pixelStride
                        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                        try {
                            padded.copyPixelsFromBuffer(plane.buffer)
                            bitmap = Bitmap.createBitmap(padded, left, top, right - left, bottom - top)
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
                    recognize(snapshot, crop, id)
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

    private data class Selection(val left: Int, val top: Int, val width: Int, val height: Int)

    private fun recognize(snapshot: Bitmap, crop: Selection, id: Int) {
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
                    mergeLineFragments(detected).mapNotNull { line ->
                        val clipped = RectF(line.box)
                        if (line.text.isBlank() ||
                            !clipped.intersect(0f, 0f, snapshot.width.toFloat(), snapshot.height.toFloat())) {
                            return@mapNotNull null
                        }
                        line.copy(box = RectF((crop.left + clipped.left) / crop.width, (crop.top + clipped.top) / crop.height,
                            (crop.left + clipped.right) / crop.width, (crop.top + clipped.bottom) / crop.height))
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
                            fail(id, "Không tìm thấy chữ trong vùng đã chọn. Hãy khoanh rộng hơn.")
                        recognized.sumOf { it.text.length + 1 } > 8000 ->
                            fail(id, "Vùng có quá nhiều chữ. Hãy khoanh vùng nhỏ hơn.")
                        else -> {
                            lines = recognized
                            android.util.Log.i("ScreenTranslate", "Recognized ${lines.size} line(s) in selected region")
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

    private fun mergeLineFragments(detected: List<CapturedLine>): List<CapturedLine> {
        val rows = mutableListOf<CapturedLine>()
        for (line in detected) {
            val previous = rows.lastOrNull()
            if (previous != null) {
                val a = previous.box
                val b = line.box
                val height = minOf(a.height(), b.height())
                val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
                val gap = b.left - a.right
                if (height > 0 && overlap >= height * .65f && gap in -height * .2f..height * .85f) {
                    rows[rows.lastIndex] = CapturedLine("${previous.text} ${line.text}", RectF(a).apply { union(b) })
                    continue
                }
            }
            rows.add(line)
        }
        return rows
    }

    private fun sendWhenReady(id: Int) {
        val line = lines.getOrNull(lineIndex) ?: return
        if (!connectionReady || requestSent) return
        if (client?.sendRealtimeText("TRANSLATE_SCREEN\n${line.text}") != true) {
            fail(id, "Không gửi được nội dung. Hãy chạm Dịch để thử lại.")
            return
        }
        requestSent = true
        resetTimeout(id)
    }

    private fun resetTimeout(id: Int) {
        requestTimeout?.let(main::removeCallbacks)
        requestTimeout = Runnable {
            if (id == generation) fail(id, "Dịch lâu quá. Hãy kiểm tra mạng rồi thử lại.")
        }.also { main.postDelayed(it, 60_000) }
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
                if (!receivedAudiblePcm) {
                    // Zero or near-zero PCM can arrive even when Gemini does not produce a voice.
                    for (offset in 0 until bytes.size - 1 step 2) {
                        val sample = (bytes[offset + 1].toInt() shl 8) or (bytes[offset].toInt() and 0xff)
                        if (sample !in -128..128) {
                            receivedAudiblePcm = true
                            break
                        }
                    }
                    if (!receivedAudiblePcm) return@post
                }
                if (audio == null && fallbackRecording == null) startPlayback(id)
                runCatching { fallbackRecording?.append(bytes) }.onFailure {
                    fail(id, "Không lưu được âm thanh tạm. Hãy kiểm tra dung lượng máy.")
                }
                audio?.play(bytes)
            } }
            override fun onTurnComplete() { main.post {
                if (!isCurrent() || !requestSent || responseComplete) return@post
                if (!receivedAudiblePcm && !emptyAudioRetry) {
                    emptyAudioRetry = true
                    requestSent = false
                    android.util.Log.w("ScreenTranslate", "Gemini returned no audible PCM; retrying once")
                    sendWhenReady(id)
                    return@post
                }
                responseComplete = true
                when {
                    fallbackRecording != null -> playFallback(id)
                    audio != null -> finishAfterPlayback(id)
                    else -> fail(id, "Gemini không trả về giọng đọc. Hãy thử lại sau.")
                }
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
        resetTimeout(id)
    }

    private fun startPlayback(id: Int) {
        val index = lineIndex
        var lastLevelAt = 0L
        try {
            audio = LiveAudio(onLevel = { level ->
                val now = SystemClock.uptimeMillis()
                if ((lastLevelAt != 0L || level > 0f) && now - lastLevelAt >= 33) {
                    lastLevelAt = now
                    main.post { if (isCurrentLine(id, index)) updateSpeechIndicator(level) }
                }
            }, onIdle = { main.post { if (isCurrentLine(id, index)) finishAfterPlayback(id) } },
                onError = { main.post {
                    if (isCurrentLine(id, index)) fail(id, "Điện thoại không phát được âm thanh. Hãy thử lại.")
                } })
        } catch (error: Exception) {
            android.util.Log.w("ScreenTranslate", "Could not initialize AudioTrack (${error.javaClass.simpleName}); using MediaPlayer")
            runCatching { TranslationRecording(File(cacheDir, "selected-region-audio.wav")) }
                .onSuccess { fallbackRecording = it }
                .onFailure { fail(id, "Không mở được bộ phát âm thanh. Hãy thử lại.") }
        }
    }

    private fun playFallback(id: Int) {
        val recording = fallbackRecording ?: return
        try {
            check(recording.finish())
            val media = MediaPlayer()
            fallbackPlayer = media
            media.apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(recording.file.absolutePath)
                setOnPreparedListener { player ->
                    if (id == generation && player === fallbackPlayer) {
                        runCatching { player.start() }
                            .onSuccess { showSpeechHighlight() }
                            .onFailure { fail(id, "Không phát được giọng đọc. Hãy thử lại.") }
                    }
                }
                setOnCompletionListener { player ->
                    if (player === fallbackPlayer) finishPlayback(id)
                }
                setOnErrorListener { player, _, _ ->
                    if (player === fallbackPlayer) fail(id, "Không phát được giọng đọc. Hãy thử lại.")
                    true
                }
            }
            media.prepareAsync()
        } catch (_: Exception) {
            fail(id, "Không phát được giọng đọc. Hãy thử lại.")
        }
    }

    private fun finishAfterPlayback(id: Int) {
        if (responseComplete && audio?.hasPendingPlayback == false) finishPlayback(id)
    }

    private fun finishPlayback(id: Int) {
        if (id != generation || closing) return
        clearPlayback()
        lineIndex++
        if (lineIndex == lines.size) {
            finishRequest()
            updateStatus("Đã đọc xong")
        } else {
            updateStatus("Đang dịch dòng tiếp theo…")
            sendWhenReady(id)
        }
    }

    private fun isCurrentLine(id: Int, index: Int) = id == generation && !closing && index == lineIndex

    private fun updateSpeechIndicator(level: Float) {
        if (level > 0f) showSpeechHighlight()
        lineHighlight?.setLevel(level)
        speakingWave?.setLevel(level)
    }

    private fun showSpeechHighlight() {
        val line = lines.getOrNull(lineIndex) ?: return
        if (speechStarted) return
        speechStarted = true
        android.util.Log.i("ScreenTranslate", "Reading line ${lineIndex + 1}/${lines.size} after ${SystemClock.elapsedRealtime() - tappedAt} ms")
        showHighlight(line.box)
        updateStatus("Đang đọc tiếng Việt…")
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
        clearPlayback()
        detachCapture()
        lines = emptyList()
        lineIndex = 0
        connectionReady = false
        bubble?.visibility = View.VISIBLE
        bubble?.show(TranslationBubble.State.READY)
    }

    private fun clearPlayback() {
        requestTimeout?.let(main::removeCallbacks)
        requestTimeout = null
        fallbackPlayer?.release()
        fallbackPlayer = null
        runCatching { fallbackRecording?.discard() }
        fallbackRecording = null
        emptyAudioRetry = false
        receivedAudiblePcm = false
        audio?.close()
        audio = null
        removeOverlay(highlight)
        highlight = null
        lineHighlight = null
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
            x = 0
            y = dp(140)
            title = getString(R.string.app_name)
        }
        val context = App.localizedContext(this)
        val button = TranslationBubble(context, ::dp)
        val menu = FloatingGlassMenu(
            context, windows, ::dp,
            top = { (params.y - dp(22)).coerceIn(0, (resources.displayMetrics.heightPixels - dp(125)).coerceAtLeast(0)) },
            onTranslate = {
                if (busy) finishRequest()
                chooseRegion()
            },
            onLock = ::lockFromMenu,
            onDismiss = { button.setInteracting(false) },
        )
        floatingMenu = menu
        button.setOnClickListener {
            if (busy) chooseRegion()
            else if (menu.isOpen) menu.dismiss(animated = true)
            else {
                button.setInteracting(true)
                menu.open()
            }
        }
        button.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0f
            private var startY = 0f
            private var initialY = 0
            private var movingVertically = false

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                val inward = startX - event.rawX
                val vertical = event.rawY - startY
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        initialY = params.y
                        movingVertically = false
                        button.setInteracting(true)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!menu.isOpen) {
                            if (button.isReady && kotlin.math.abs(vertical) > dp(12) &&
                                kotlin.math.abs(vertical) > kotlin.math.abs(inward) * 1.8f) {
                                movingVertically = true
                                menu.dismiss()
                                button.setInteracting(true)
                            }
                            if (movingVertically) {
                                params.y = (initialY + vertical.roundToInt()).coerceIn(
                                    0, (resources.displayMetrics.heightPixels - view.height).coerceAtLeast(0))
                                windows.updateViewLayout(view, params)
                            } else if (inward > dp(4)) {
                                button.followSwipe(inward / 4)
                                menu.follow((inward / dp(42)).coerceIn(0f, 1f))
                            } else menu.dismiss()
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        button.followSwipe(0f)
                        val released = event.actionMasked == MotionEvent.ACTION_UP
                        val opensMenu = released && !movingVertically && inward >= dp(24) &&
                            inward > kotlin.math.abs(vertical) * 1.2f
                        if (opensMenu) {
                            val wasOpen = menu.isOpen
                            menu.open()
                            if (!wasOpen && menu.isOpen) view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            button.setInteracting(menu.isOpen)
                        } else if (menu.isOpen) {
                            if (released) menu.dismiss(animated = true)
                        } else {
                            menu.dismiss(animated = true)
                            button.setInteracting(false)
                            if (released && busy && kotlin.math.abs(inward) < dp(8) && kotlin.math.abs(vertical) < dp(8)) {
                                view.performClick()
                            }
                        }
                    }
                }
                return true
            }
        })
        windows.addView(button, params)
        bubble = button
    }

    private fun lockFromMenu() {
        if (Build.VERSION.SDK_INT < 28) {
            Toast.makeText(this, R.string.screen_lock_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        val lockService = ScreenLockService.instance
        if (lockService == null) {
            Toast.makeText(this, R.string.screen_lock_enable, Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure {
                Toast.makeText(this, R.string.screen_lock_settings_failed, Toast.LENGTH_LONG).show()
            }
            return
        }
        finishRequest()
        dismissRegionPicker(showBubble = false)
        runCatching { projection?.stop() }
        stopSelf()
        if (!lockService.lockScreen()) {
            Toast.makeText(this, R.string.screen_lock_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun showHighlight(box: RectF) {
        val view = ScreenReadingHighlightView(this, box)
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
        val wavePosition = FrameLayout.LayoutParams(dp(154), dp(32), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
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
                lineHighlight = view
                speakingWave = wave
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
        floatingMenu?.dismiss()
        floatingMenu = null
        dismissRegionPicker(showBubble = false)
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
