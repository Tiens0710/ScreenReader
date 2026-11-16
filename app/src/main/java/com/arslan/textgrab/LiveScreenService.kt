package com.arslan.textgrab

import android.app.Notification
import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.os.PowerManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class LiveScreenService : Service() {
    companion object {
        const val ACTION_START = "textgrab.live.START"
        const val ACTION_STOP = "textgrab.live.STOP"
        const val ACTION_PAUSE = "textgrab.live.PAUSE"
        const val ACTION_VOICE = "textgrab.live.VOICE"
        const val ACTION_PANEL = "textgrab.live.PANEL"
        const val EXTRA_RESULT = "projection_result"
        const val EXTRA_DATA = "projection_data"
        const val EXTRA_MIC = "microphone"
        const val EXTRA_VOICE = "voice"
        private const val CHANNEL = "gemini_live"
        private const val NOTIFICATION = 38
        private const val LIVE_MLKIT_LONG_EDGE = 1600
    }
    private val lowRam by lazy { getSystemService(ActivityManager::class.java).isLowRamDevice }
    private val captureIntervalMs get() = if (lowRam) 3000L else 2000L
    private val captureLongEdge get() = if (lowRam) 1280 else 1600
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 29) {
            val listener = PowerManager.OnThermalStatusChangedListener { severity ->
                if (severity >= PowerManager.THERMAL_STATUS_SEVERE) main.post {
                    if (!closing) finishSession("Đã dừng Live vì máy quá nóng. Hãy để máy nguội rồi bật lại.")
                }
            }
            thermalListener = listener
            getSystemService(PowerManager::class.java).addThermalStatusListener(mainExecutor, listener)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level == ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
            level == ComponentCallbacks2.TRIM_MEMORY_MODERATE ||
            level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            if (!closing) finishSession("Đã dừng Live vì máy thiếu bộ nhớ. Đóng bớt ứng dụng rồi bật lại.")
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val ocrScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var capturedBitmap: Bitmap? = null
    private var capturedReader: ImageReader? = null
    private var frameWakeScheduled = false
    private var lastFrameSampleAt = 0L
    private val frameWake = Runnable {
        frameWakeScheduled = false
        if (!closing && !paused) {
            captureHandler?.removeCallbacks(captureTick)
            captureTick.run()
        }
    }
    private var firstCaptureLogged = false
    private var lastCaptureDiagnosticAt = 0L
    private val captureTick = object : Runnable {
        override fun run() {
            if (closing) return
            if (paused) { captureHandler?.postDelayed(this, captureIntervalMs); return }
            val memory = ActivityManager.MemoryInfo()
            getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
            if (memory.lowMemory) {
                main.post { if (!closing) finishSession("Đã dừng Live vì máy thiếu bộ nhớ. Đóng bớt ứng dụng rồi bật lại.") }
                return
            }
            val now = SystemClock.elapsedRealtime()
            val source = reader
            if (source !== capturedReader) {
                capturedBitmap?.recycle(); capturedBitmap = null; capturedReader = source
            }
            // OCR owns a snapshot; continue monitoring fresh frames to cancel stale batches.
            val image = runCatching { source?.acquireLatestImage() }.getOrNull()
            try {
                if (paused || LiveSession.appVisible || client?.ready != true) {
                    capturedBitmap?.recycle(); capturedBitmap = null
                    if (now - lastCaptureDiagnosticAt >= 5_000L) {
                        android.util.Log.i("GeminiLive", "Waiting for capture: appVisible=${LiveSession.appVisible}, connected=${client?.ready == true}")
                        lastCaptureDiagnosticAt = now
                    }
                } else {
                    if (image != null) {
                        lastFrameSampleAt = now
                        val plane = image.planes[0]
                        val paddedWidth = image.width + (plane.rowStride - plane.pixelStride * image.width) / plane.pixelStride
                        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                        padded.copyPixelsFromBuffer(plane.buffer)
                        val bitmap = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                        if (bitmap !== padded) padded.recycle()
                        capturedBitmap?.recycle(); capturedBitmap = bitmap
                    }
                    // A static display still gets sampled so a pending AI request is never missed.
                    val frame = capturedBitmap
                    if (frame != null) {
                        if (!firstCaptureLogged) {
                            firstCaptureLogged = true
                            android.util.Log.i("GeminiLive", "First screen frame captured: ${frame.width}x${frame.height}")
                        }
                        processFrame(frame, now)
                    } else if (now - lastCaptureDiagnosticAt >= 5_000L) {
                        android.util.Log.w("GeminiLive", "No screen frame available from MediaProjection yet")
                        lastCaptureDiagnosticAt = now
                    }
                }
            } catch (_: OutOfMemoryError) {
                capturedBitmap?.recycle(); capturedBitmap = null
                main.post { if (!closing) finishSession("Đã dừng Live vì không đủ bộ nhớ để xử lý màn hình.") }
            } catch (_: Exception) {
                // Projection resize/stop can invalidate a buffer in flight; the next tick recovers.
            } finally { image?.close() }
            if (!closing) {
                val delay = if (pendingChange && !ocrInFlight && client?.responding != true && aiTurnComplete)
                    350L else captureIntervalMs
                captureHandler?.postDelayed(this, delay)
            }
        }
    }
    private var client: GeminiLiveClient? = null
    private var audio: LiveAudio? = null
    private var speakingWave: SpeakingWaveView? = null
    private var readingHighlight: ReadingHighlightView? = null
    @Volatile private var selectedRegions = emptyList<android.graphics.RectF>()
    private var lastPlaybackRegions = emptyList<android.graphics.RectF>()
    @Volatile private var displayedRegions = emptyList<android.graphics.RectF>()
    @Volatile private var screenVersion = 0L
    @Volatile private var readingCycleVersion = 0L
    @Volatile private var readingTurnVersion = -1L
    @Volatile private var readingPass = 0
    @Volatile private var turnHadAudio = false
    @Volatile private var highlightAuthorized = false
    @Volatile private var audioWithoutHighlight = false
    @Volatile private var highlightRetries = 0
    @Volatile private var interruptPending = false
    @Volatile private var aiTurnComplete = true
    @Volatile private var closing = false
    @Volatile private var paused = false
    @Volatile private var overlayExpanded = false
    private var micRequested = false
    private var frameWidth = 0
    private var frameHeight = 0
    @Volatile private var lastPromptAt = 0L
    private val screenSettling = ScreenSettling()
    @Volatile private var previousPixels: IntArray? = null
    @Volatile private var pendingChange = true
    @Volatile private var pendingChangeAt = 0L
    @Volatile private var ocrCandidates = emptyList<OcrRegionCandidate>()
    @Volatile private var ocrCandidatesVersion = -1L
    @Volatile private var ocrInFlight = false
    private var overlay: LinearLayout? = null
    private var panel: LinearLayout? = null
    private var overlayStatus: TextView? = null
    private var overlayText: TextView? = null
    private var pauseButton: TextView? = null
    private var voiceButton: TextView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var openPanel: (() -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> finishSession("Đã kết thúc đọc")
            ACTION_PAUSE -> if (projection != null) togglePause()
            ACTION_PANEL -> openPanel?.invoke()
            ACTION_VOICE -> audio?.let {
                it.voiceEnabled = !it.voiceEnabled
                LiveSession.update { state -> state.copy(voice = it.voiceEnabled) }
                renderOverlay()
            }
            ACTION_START -> if (projection == null && !closing) startSession(intent)
        }
        return START_NOT_STICKY
    }

    private fun startSession(intent: Intent) {
        try {
            micRequested = intent.getBooleanExtra(EXTRA_MIC, false)
            var types = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
            if (micRequested && Build.VERSION.SDK_INT >= 30) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Gemini Live · chia sẻ màn hình", NotificationManager.IMPORTANCE_LOW))
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification(), types)
            else startForeground(NOTIFICATION, notification())
            val key = GeminiCredentials.read(this)
            if (key.isBlank()) { finishSession("Chưa có khóa Gemini. Mở app để nhập khóa API."); return }
            val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                else @Suppress("DEPRECATION") (intent.getParcelableExtra(EXTRA_DATA) as? Intent)
            if (data == null) { finishSession("Chưa có quyền chia sẻ màn hình. Bắt đầu phiên Live mới."); return }
            LiveSession.reset(LiveUiState(running = true, voice = intent.getBooleanExtra(EXTRA_VOICE, true),
                status = "Đang kết nối, vui lòng chờ…"))
            audio = LiveAudio(onRegion = { regions -> main.post {
                if (!closing) {
                    val visible = if (LiveSession.appVisible || paused) emptyList()
                        else regions
                    if (visible.isNotEmpty()) displayedRegions = (displayedRegions + visible).distinct().takeLast(24)
                    readingHighlight?.show(visible)
                    if (visible != lastPlaybackRegions) {
                        lastPlaybackRegions = visible
                        maybeContinueReading()
                    }
                }
            } }, onLevel = { level -> main.post { if (!closing) speakingWave?.setLevel(level) } },
                onIdle = { maybeContinueReading() })
                .apply { voiceEnabled = intent.getBooleanExtra(EXTRA_VOICE, true) }
            client = GeminiLiveClient(key, object : GeminiLiveClient.Listener {
                override fun onReady() {
                    previousPixels = null
                    pendingChange = true
                    pendingChangeAt = 0L
                    ocrCandidates = emptyList()
                    ocrCandidatesVersion = -1L
                    android.util.Log.i("GeminiLive", "Session ready; waiting for a target-app frame")
                    status("Đã sẵn sàng · mở trang bạn muốn nghe", connected = true)
                    requestCaptureNow()
                    if (micRequested) main.post { startMicrophone() }
                }
                override fun onStatus(message: String) { status(message, connected = false) }
                override fun onTranscript(text: String) {
                    if (readingTurnVersion != screenVersion || closing || paused) return
                    LiveSession.update { it.copy(transcript = text) }
                    main.post { renderOverlay() }
                }
                override fun onAudio(bytes: ByteArray) {
                    if (paused || closing || readingTurnVersion != screenVersion) return
                    if (!highlightAuthorized) {
                        // Never let a text answer play without the matching visual region.
                        audioWithoutHighlight = true
                        android.util.Log.w("LiveHighlight", "Gemini audio held: no visual region for this turn")
                        return
                    }
                    if (!turnHadAudio) {
                        android.util.Log.i("GeminiLive", "Audio received with visual authorization; regions=${selectedRegions.size}")
                        status("Đang đọc nội dung bằng tiếng Việt…", connected = true)
                    }
                    turnHadAudio = true
                    audio?.play(bytes, selectedRegions)
                }
                override fun onHighlight(regions: List<android.graphics.RectF>, allowNoRegion: Boolean) {
                    if (readingTurnVersion != screenVersion || closing || paused) return
                    // Coordinates belong to the exact frame attached to this blocking Gemini turn.
                    // A clock, animation, or the floating waveform may change later frames while
                    // Gemini is thinking; that must not discard the matching coordinates.
                    highlightAuthorized = regions.isNotEmpty() || allowNoRegion
                    selectedRegions = if (readingTurnVersion >= 0L && highlightAuthorized) regions else emptyList()
                    if (highlightAuthorized) {
                        audioWithoutHighlight = false
                        highlightRetries = 0
                    }
                    android.util.Log.d("LiveHighlight", "Gemini Vision selected ${selectedRegions.size} regions: ${selectedRegions.joinToString { "${it.left},${it.top},${it.right},${it.bottom}" }}")
                    status(if (selectedRegions.isNotEmpty()) "Đã khoanh vùng · đang chuẩn bị đọc" else "Đang chuẩn bị mô tả màn hình", connected = true)
                }
                override fun onTurnComplete() {
                    interruptPending = false
                    aiTurnComplete = true
                    android.util.Log.i("GeminiLive", "Turn complete: audio=$turnHadAudio, highlightedRegions=${selectedRegions.size}")
                    val missingVisualResponse = audioWithoutHighlight || (!highlightAuthorized && readingPass > 0)
                    if (missingVisualResponse && !paused && !closing) {
                        readingPass = (readingPass - 1).coerceAtLeast(0)
                        if (highlightRetries < 2) {
                            highlightRetries++
                            pendingChangeAt = 0L
                            pendingChange = true
                            android.util.Log.w("LiveHighlight", "Retrying visual response without a valid region ($highlightRetries/2)")
                        } else {
                            readingTurnVersion = -1L
                            status("Gemini chưa xác định chắc vùng chữ · đang chờ nội dung mới", connected = true)
                        }
                    }
                    maybeContinueReading()
                    if (pendingChange) requestCaptureNow()
                }
                override fun onInterrupted() {
                    audio?.interrupt()
                    if (interruptPending) {
                        interruptPending = false
                        aiTurnComplete = true
                        android.util.Log.i("GeminiLive", "Previous turn interrupted; new screen ready to submit")
                        requestCaptureNow()
                    }
                }
                override fun onFatal(message: String) { main.post { finishSession(message) } }
            })
            captureThread = HandlerThread("GeminiLiveFrames").apply { start() }
            captureHandler = Handler(captureThread!!.looper)
            projection = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(intent.getIntExtra(EXTRA_RESULT, -1), data)
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { if (!closing) finishSession("Android đã dừng chia sẻ màn hình") }
                override fun onCapturedContentResize(width: Int, height: Int) {
                    if (!closing && width > 0 && height > 0) resizeCapture(width, height)
                }
            }, main)
            val metrics = resources.displayMetrics
            resizeCapture(metrics.widthPixels, metrics.heightPixels)
            captureHandler?.post(captureTick)
            showOverlay()
            showSpeakingWave()
            showReadingHighlight()
            client!!.connect()
        } catch (_: SecurityException) {
            finishSession("Android chưa cấp đủ quyền. Mở app và bắt đầu phiên Live mới.")
        } catch (_: Exception) {
            finishSession("Không thể khởi động phiên Live. Hãy thử lại từ ứng dụng.")
        }
    }

    private fun resizeCapture(width: Int, height: Int) {
        // Bound capture buffers as well as OCR input to reduce concurrent RAM usage.
        val scale = (captureLongEdge.toFloat() / max(width, height)).coerceAtMost(1f)
        val w = (width * scale).roundToInt().coerceAtLeast(1)
        val h = (height * scale).roundToInt().coerceAtLeast(1)
        if (w == frameWidth && h == frameHeight) return
        frameWidth = w; frameHeight = h
        val next = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val old = reader
        reader = next
        next.setOnImageAvailableListener({ source ->
            val handler = captureHandler
            if (source === reader && handler != null && !closing && !paused &&
                !LiveSession.appVisible && client?.ready == true && !frameWakeScheduled) {
                // Wake on real display updates, but coalesce animation bursts.
                val minimumGap = if (lowRam) 1000L else 750L
                frameWakeScheduled = true
                handler.postDelayed(frameWake,
                    (lastFrameSampleAt + minimumGap - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            }
        }, captureHandler)
        val current = display
        if (current == null) {
            display = projection!!.createVirtualDisplay("TextGrabGeminiLive", w, h, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, next.surface, null, captureHandler)
        } else {
            current.resize(w, h, resources.displayMetrics.densityDpi)
            current.surface = next.surface
        }
        old?.setOnImageAvailableListener(null, null)
        old?.close()
        previousPixels = null
        pendingChange = true
    }

    private fun processFrame(bitmap: Bitmap, now: Long) {
        val tiny = Bitmap.createScaledBitmap(bitmap, 32, 56, true)
        val pixels = IntArray(32 * 56)
        tiny.getPixels(pixels, 0, 32, 0, 0, 32, 56)
        tiny.recycle()
        val previous = previousPixels
        // Ignore our own thin outline in change detection, so highlighting does not trigger another reading.
        val outlines = displayedRegions
        val changed = previous == null || pixels.indices.count {
            val x = (it % 32 + .5f) / 32
            val y = (it / 32 + .5f) / 56
            // The speaking indicator is generated by us, not by the target app.
            if (x in .25f.. .75f && y < .075f) return@count false
            if (outlines.any { box -> x >= box.left - .025f && x <= box.right + .025f &&
                    y >= box.top - .018f && y <= box.bottom + .018f &&
                    (abs(x - box.left) < .025f || abs(x - box.right) < .025f ||
                        abs(y - box.top) < .018f || abs(y - box.bottom) < .018f) }) return@count false
            val a = pixels[it]; val b = previous[it]
            abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b)) > 60
        } > pixels.size * 0.006
        previousPixels = pixels
        if (changed) {
            screenVersion++
            if (!interruptPending && !aiTurnComplete) {
                interruptPending = client?.interruptForScreenChange() == true
                if (interruptPending) android.util.Log.i("GeminiLive", "Interrupting previous Gemini turn for new screen")
            }
            android.util.Log.i("GeminiLive", "Screen changed; version=$screenVersion")
            ocrCandidates = emptyList()
            ocrCandidatesVersion = -1L
            readingCycleVersion = screenVersion
            readingPass = 0
            highlightRetries = 0
            // Drop speech queued for the previous screen, including a prefetched turn.
            readingTurnVersion = -1L
            highlightAuthorized = false
            selectedRegions = emptyList()
            turnHadAudio = false
            audio?.interrupt()
            displayedRegions = emptyList()
            main.post { readingHighlight?.show(emptyList()) }
            // Reset on EVERY change, even when a request is already pending.
            // Otherwise an animation's later frames inherit an expired debounce.
            screenSettling.changed(SystemClock.elapsedRealtime())
            pendingChangeAt = now
            pendingChange = true
        }
        if (pendingChange && pendingChangeAt == 0L) pendingChangeAt = now
        val live = client ?: return
        // Local OCR can prepare the new screen while the old cloud turn is being stopped.
        if (pendingChange && ocrCandidatesVersion != screenVersion) {
            if (screenSettling.isReady(SystemClock.elapsedRealtime()) && !ocrInFlight &&
                audio?.hasPendingPlayback != true) {
                android.util.Log.i("GeminiLive", "Screen stable; starting OCR for version=$screenVersion")
                recognizeLiveRegions(bitmap, screenVersion)
            }
            return
        }
        if (pendingChange && readingPass < 3 && ((!live.responding && aiTurnComplete) || interruptPending) && canPrepareNextTurn() &&
            now - lastPromptAt >= 800L && now - pendingChangeAt >= 350L) {
            if (!closing && !paused && !LiveSession.appVisible && readingCycleVersion == screenVersion) {
                selectedRegions = emptyList()
                highlightAuthorized = false
                audioWithoutHighlight = false
                val pass = readingPass
                val replacePrevious = interruptPending
                readingTurnVersion = screenVersion
                turnHadAudio = false
                aiTurnComplete = false
                val candidate = nextReadableCandidate(ocrCandidates, pass)
                val sent = if (candidate != null) {
                    // PaddleOCR chooses the next reading-order block and its exact box locally.
                    // Gemini receives only that text, so it can translate/speak without first
                    // spending a blocking tool-call turn deciding which box to highlight.
                    selectedRegions = listOf(android.graphics.RectF(candidate.bounds))
                    highlightAuthorized = true
                    live.setOcrCandidates(emptyList())
                    status("Đang chuẩn bị đọc…", connected = true)
                    val prompt = "OCR_REGION\nĐọc và dịch sang tiếng Việt đoạn chữ sau. Nói đúng một câu ngắn, tự nhiên; không gọi công cụ khoanh vùng vì ứng dụng đã khoanh đúng vùng này. Nếu đoạn chữ lỗi hoặc không có nghĩa, nói rõ là không đọc rõ.\n${candidate.text.take(800)}"
                    val accepted = live.askText(prompt, replace = replacePrevious)
                    if (accepted) {
                        android.util.Log.i("GeminiLive", "OCR text sent to Gemini: chars=${candidate.text.length}, pass=${pass + 1}")
                    }
                    accepted
                } else {
                    // Image fallback remains sequential; only cached OCR text is prefetched.
                    if (audio?.hasPendingPlayback == true) { aiTurnComplete = true; return }
                    val scale = (1600f / max(bitmap.width, bitmap.height)).coerceAtMost(1f)
                    val networkImage = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt(),
                        (bitmap.height * scale).roundToInt(), true)
                    val jpeg = try { ByteArrayOutputStream().use { stream ->
                        networkImage.compress(Bitmap.CompressFormat.JPEG, 80, stream); stream.toByteArray()
                    } } finally { if (networkImage !== bitmap) networkImage.recycle() }
                    live.setOcrCandidates(emptyList())
                    live.rememberFrame(jpeg)
                    status("Gemini đang xem ảnh để tìm vùng chữ…", connected = true)
                    val prompt = "SCREEN_IMAGE\nMàn hình vừa thay đổi. Chọn vùng chữ mới quan trọng nhất, dịch sang tiếng Việt và nói một câu ngắn. Gọi highlight_screen_regions với kind=text và một regions box chính xác theo ảnh, tọa độ 0–1000. Nếu không có chữ đọc được thì giữ im lặng."
                    val accepted = live.ask(prompt, replace = replacePrevious)
                    if (accepted) {
                        android.util.Log.i("GeminiLive", "Fallback screen image sent: ${(bitmap.width * scale).roundToInt()}x${(bitmap.height * scale).roundToInt()}, ${jpeg.size} bytes, pass=${pass + 1}")
                    }
                    accepted
                }
                if (sent) {
                    interruptPending = false
                    readingPass++
                    lastPromptAt = now
                    pendingChange = false
                    pendingChangeAt = 0L
                    LiveSession.update { it.copy(frames = it.frames + 1) }
                } else {
                    android.util.Log.w("GeminiLive", "Could not send reading request: ready=${live.ready}, responding=${live.responding}")
                    aiTurnComplete = true; readingTurnVersion = -1L
                }
            }
        }
    }

    private fun requestCaptureNow() {
        captureHandler?.post {
            if (!closing && !paused) {
                captureHandler?.removeCallbacks(captureTick)
                captureTick.run()
            }
        }
    }

    private fun recognizeLiveRegions(bitmap: Bitmap, version: Long) {
        if (closing || ocrInFlight) return
        ocrInFlight = true
        status("Đang tìm chữ trên màn hình…", connected = true)
        val snapshot = runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        if (snapshot == null) {
            ocrInFlight = false
            ocrCandidates = emptyList()
            ocrCandidatesVersion = version
            android.util.Log.w("GeminiLive", "Could not copy frame for local text localization; Gemini box fallback enabled")
            return
        }
        ocrScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            var source = "PP-OCRv5 Latin"
            val result = try {
                withContext(NonCancellable) {
                    runCatching {
                        val paddleStartedAt = SystemClock.elapsedRealtime()
                        val paddleAttempt = runCatching {
                            val lines = PaddleLiveOcr.recognize(this@LiveScreenService, snapshot) { closing || version != screenVersion }
                            ScreenRegions.fromPaddleOcr(lines, snapshot.width, snapshot.height)
                        }
                        check(!closing && version == screenVersion) { "OCR frame superseded" }
                        val paddleRegions = paddleAttempt.getOrDefault(emptyList())
                        android.util.Log.i("GeminiLive", "PP-OCRv5 primary localized ${paddleRegions.size} blocks in ${SystemClock.elapsedRealtime() - paddleStartedAt}ms; success=${paddleAttempt.isSuccess}")
                        if (paddleRegions.isNotEmpty()) {
                            paddleRegions
                        } else {
                            check(!closing) { "Live stopped before fallback" }
                            source = "ML Kit Latin fallback"
                            val fallbackStartedAt = SystemClock.elapsedRealtime()
                            val scale = (LIVE_MLKIT_LONG_EDGE.toFloat() / max(snapshot.width, snapshot.height)).coerceAtMost(1f)
                            val input = if (scale < 1f) Bitmap.createScaledBitmap(snapshot,
                                (snapshot.width * scale).roundToInt().coerceAtLeast(1),
                                (snapshot.height * scale).roundToInt().coerceAtLeast(1), true) else snapshot
                            val regions = try {
                                ScreenRegions.fromOcr(OcrEngine.recognizeLatin(input), input.width, input.height)
                            } finally {
                                if (input !== snapshot) input.recycle()
                            }
                            android.util.Log.i("GeminiLive", "ML Kit fallback localized ${regions.size} blocks in ${SystemClock.elapsedRealtime() - fallbackStartedAt}ms")
                            regions
                        }
                    }
                }
            } finally { snapshot.recycle() }
            ensureActive()
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            main.post {
                ocrInFlight = false
                if (closing) return@post
                if (version != screenVersion) {
                    android.util.Log.i("GeminiLive", "Discarded stale OCR version=$version; newest=$screenVersion in ${elapsedMs}ms")
                    requestCaptureNow(); return@post
                }
                ocrCandidates = result.getOrElse { emptyList() }
                ocrCandidatesVersion = version
                if (result.isSuccess) {
                    android.util.Log.i("GeminiLive", "$source localized ${ocrCandidates.size} text blocks for screen version=$version in ${elapsedMs}ms")
                    if (ocrCandidates.isEmpty()) status("Chưa thấy vùng chữ · Gemini đang kiểm tra ảnh…", connected = true)
                } else {
                    android.util.Log.w("GeminiLive", "Local OCR failed; Gemini box fallback enabled (${result.exceptionOrNull()?.javaClass?.simpleName})")
                    status("OCR chưa nhận được chữ · Gemini đang dò trực tiếp…", connected = true)
                }
                requestCaptureNow()
            }
        }.invokeOnCompletion {
            // Also covers cancellation before the coroutine starts executing.
            if (!snapshot.isRecycled) snapshot.recycle()
        }
    }

    private fun canPrepareNextTurn(): Boolean {
        val playback = audio ?: return true
        if (!playback.hasPendingPlayback) return true
        // Permit just one turn ahead: once selectedRegions points to queued speech,
        // wait until that region is actually audible before preparing another turn.
        return ReadingPrefetchPolicy.canPrepare(playback.hasPendingPlayback, playback.activeRegions,
            selectedRegions, ocrCandidatesVersion == screenVersion,
            nextReadableCandidate(ocrCandidates, readingPass) != null)
    }

    private fun maybeContinueReading() {
        if (!turnHadAudio || !aiTurnComplete || !canPrepareNextTurn() || paused || closing ||
            LiveSession.appVisible || readingPass !in 1..2 || readingCycleVersion != readingTurnVersion ||
            readingCycleVersion != screenVersion) return
        main.post {
            if (!closing && !paused && turnHadAudio && aiTurnComplete && canPrepareNextTurn() &&
                readingPass in 1..2 && readingCycleVersion == screenVersion && readingTurnVersion == screenVersion) {
                // The same screen is already stable; continue without another debounce.
                pendingChangeAt = SystemClock.elapsedRealtime() - 350L
                pendingChange = true
                requestCaptureNow()
            }
        }
    }

    private fun nextReadableCandidate(candidates: List<OcrRegionCandidate>, pass: Int): OcrRegionCandidate? {
        val overlays = listOf("gemini live", "textgrab", "đang tự đọc", "đang đọc", "đang quét",
            "tạm dừng", "tiếp tục", "tắt loa", "bật loa", "kết thúc")
        val visible = candidates.filter { candidate ->
            val text = candidate.text.trim().lowercase()
            candidate.bounds.bottom > .055f && candidate.bounds.top < .93f &&
                text.isNotBlank() && overlays.none(text::contains)
        }
        val content = visible.filter { candidate ->
            candidate.text.count(Char::isLetterOrDigit) >= 6
        }
        return (content.ifEmpty { visible }).getOrNull(pass)
    }

    private fun startMicrophone() {
        if (closing || paused || !micRequested) return
        audio?.startMicrophone({ bytes -> if (!paused && !closing) client?.sendMicrophone(bytes) }, {
            main.post { LiveSession.update { it.copy(microphone = false, status = "Live màn hình vẫn chạy · không mở được micro") }; renderOverlay() }
        })
        LiveSession.update { it.copy(microphone = true) }
        renderOverlay()
    }

    private fun togglePause() {
        paused = !paused
        if (paused) { audio?.stopMicrophone(); audio?.interrupt(); client?.endMicrophone() }
        else { pendingChange = true; if (micRequested) startMicrophone() }
        LiveSession.update { it.copy(paused = paused, microphone = !paused && micRequested,
            status = if (paused) "Đã tạm dừng gửi màn hình và micro" else "Đã sẵn sàng · mở trang bạn muốn nghe") }
        renderOverlay()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
    }

    private fun status(message: String, connected: Boolean) {
        if (closing) return
        LiveSession.update { it.copy(status = message, connected = connected) }
        main.post { renderOverlay() }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, LiveActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun command(action: String, request: Int) = PendingIntent.getService(this, request,
            Intent(this, LiveScreenService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Đọc màn hình ${if (paused) "· tạm dừng" else "· đang chia sẻ màn hình"}")
            .setContentText("Chạm để tạm dừng hoặc kết thúc đọc").setContentIntent(open).setOngoing(true)
            .addAction(0, if (paused) "Tiếp tục" else "Tạm dừng", command(ACTION_PAUSE, 1))
            .addAction(0, "Kết thúc", command(ACTION_STOP, 2)).build()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        val ctx = App.localizedContext(this)
        val ink = Color.rgb(237, 246, 242)
        val mint = Color.rgb(175, 237, 207)
        val muted = Color.rgb(160, 184, 175)
        fun rounded(color: Int, radius: Int = 14, stroke: Int? = null) = GradientDrawable().apply {
            setColor(color); cornerRadius = dp(radius).toFloat()
            stroke?.let { setStroke(dp(1), it) }
        }
        val params = WindowManager.LayoutParams(dp(64), -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT).apply {
                title = "TextGrab Gemini Live"; gravity = Gravity.TOP or Gravity.END; x = dp(12); y = dp(100)
            }
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var setExpanded: (Boolean) -> Unit = {}
        val bubble = TextView(ctx).apply {
            text = "Đọc"; textSize = 20f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(mint); gravity = Gravity.CENTER
            contentDescription = "Mở điều khiển đọc: tạm dừng hoặc kết thúc"; elevation = dp(8).toFloat()
            background = rounded(Color.rgb(19, 54, 45), 24)
        }
        root.addView(bubble, LinearLayout.LayoutParams(dp(64), dp(64)).apply { gravity = Gravity.END })
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(Color.rgb(22, 37, 32), 24, Color.rgb(61, 86, 75))
            elevation = dp(12).toFloat()
        }
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(ctx).apply {
            text = "Đọc màn hình"; textSize = 20f; setTextColor(mint)
            gravity = Gravity.CENTER_VERTICAL
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        val minimize = TextView(ctx).apply {
            text = "−"; textSize = 28f; gravity = Gravity.CENTER; setTextColor(ink)
            contentDescription = "Thu gọn bảng phản hồi"
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.rgb(58, 91, 76)), rounded(Color.rgb(37, 58, 48)), null)
            setOnClickListener { setExpanded(false) }
        }
        header.addView(minimize, LinearLayout.LayoutParams(dp(48), dp(48)))
        body.addView(header)
        overlayStatus = TextView(ctx).apply {
            textSize = 18f; setTextColor(muted); setPadding(0, dp(6), 0, dp(12))
        }
        body.addView(overlayStatus)
        overlayText = TextView(ctx).apply {
            textSize = 22f; setTextColor(ink); setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, 0, 0, dp(4))
        }
        val response = object : ScrollView(ctx) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(dp(210), View.MeasureSpec.AT_MOST))
            }
        }.apply {
            isFillViewport = false; isVerticalScrollBarEnabled = true
            addView(overlayText, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
        body.addView(response, LinearLayout.LayoutParams(-1, -2))
        fun chip(label: String, color: Int, foreground: Int, action: () -> Unit) = TextView(ctx).apply {
            text = label; textSize = 20f; gravity = Gravity.CENTER; setTextColor(foreground)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            isClickable = true; isFocusable = true
            minimumHeight = dp(64)
            setPadding(dp(8), dp(14), dp(8), dp(14))
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.argb(55, 255, 255, 255)), rounded(color), null)
            setOnClickListener { action() }
        }
        fun cell(weight: Float = 1f) = LinearLayout.LayoutParams(0, -2, weight)
        val controls = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        pauseButton = chip("Tạm dừng", Color.rgb(34, 49, 41), ink) { togglePause() }
        voiceButton = chip("Tắt tiếng", Color.rgb(34, 49, 41), ink) {
            audio?.let { it.voiceEnabled = !it.voiceEnabled; LiveSession.update { state -> state.copy(voice = it.voiceEnabled) } }
            renderOverlay()
        }
        val stop = chip("Kết thúc", Color.rgb(65, 36, 34), Color.rgb(255, 187, 177)) { finishSession("Đã kết thúc đọc") }
        controls.addView(pauseButton, cell().apply { rightMargin = dp(4) })
        controls.addView(voiceButton, cell().apply { leftMargin = dp(4); rightMargin = dp(4) })
        stop.text = "Kết thúc đọc"
        body.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        body.addView(stop, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        root.addView(body)
        fun dragHandle(handle: View) {
            var downX = 0f; var downY = 0f; var initialX = 0; var initialY = 0; var moved = false
            handle.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX; downY = event.rawY; initialX = params.x; initialY = params.y; moved = false; true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX; val dy = event.rawY - downY
                        if (abs(dx) + abs(dy) > dp(8)) moved = true
                        if (moved) {
                            params.x = (initialX - dx.toInt()).coerceIn(0, (resources.displayMetrics.widthPixels - params.width).coerceAtLeast(0))
                            params.y = (initialY + dy.toInt()).coerceIn(0, (resources.displayMetrics.heightPixels - root.height - dp(48)).coerceAtLeast(0))
                            runCatching { getSystemService(WindowManager::class.java).updateViewLayout(root, params) }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> { if (!moved) view.performClick(); true }
                    else -> true
                }
            }
        }
        dragHandle(bubble); dragHandle(title)
        setExpanded = { value ->
            overlayExpanded = value
            bubble.visibility = if (value) View.GONE else View.VISIBLE
            body.visibility = if (value) View.VISIBLE else View.GONE
            params.width = if (value) dp(340).coerceAtMost(resources.displayMetrics.widthPixels - dp(24)) else dp(64)
            params.flags = if (value) params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                else params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            params.x = params.x.coerceAtMost((resources.displayMetrics.widthPixels - params.width).coerceAtLeast(0))
            params.y = params.y.coerceAtMost((resources.displayMetrics.heightPixels - if (value) dp(430) else dp(100)).coerceAtLeast(0))
            getSystemService(WindowManager::class.java).updateViewLayout(root, params)
            if (value) root.requestFocus()
            renderOverlay()
        }
        root.isFocusableInTouchMode = true
        root.setOnKeyListener { _, keyCode, event ->
            if (overlayExpanded && keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                if (event.action == android.view.KeyEvent.ACTION_UP) setExpanded(false)
                true
            } else false
        }
        bubble.setOnClickListener { setExpanded(!overlayExpanded) }
        openPanel = { setExpanded(true) }
        overlay = root; panel = body; overlayParams = params
        getSystemService(WindowManager::class.java).addView(root, params)
        renderOverlay()
    }

    private fun showReadingHighlight() {
        if (!Settings.canDrawOverlays(this)) return
        val view = ReadingHighlightView(this)
        val params = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            alpha = .6f // Below Android's maximum obscuring opacity; touches pass to the app underneath.
            title = "Gemini reading highlight"
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        runCatching { getSystemService(WindowManager::class.java).addView(view, params) }
            .onSuccess { readingHighlight = view }
    }

    private fun showSpeakingWave() {
        if (!Settings.canDrawOverlays(this)) return
        val density = resources.displayMetrics.density
        val view = SpeakingWaveView(this)
        val params = WindowManager.LayoutParams((172 * density).roundToInt(), (38 * density).roundToInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (8 * density).roundToInt()
            title = "Gemini speaking waveform"
        }
        runCatching { getSystemService(WindowManager::class.java).addView(view, params) }
            .onSuccess { speakingWave = view }
    }

    private fun renderOverlay() {
        val state = LiveSession.state.value
        overlayStatus?.text = when {
            state.paused -> "Đã tạm dừng tự đọc"
            state.connected -> state.status.ifBlank { if (state.voice) "Đang tự đọc · tiếng Việt" else "Đang theo dõi · loa đã tắt" }
            else -> state.status
        }
        overlayText?.text = state.transcript.ifBlank { "Gemini sẽ đọc, dịch hoặc giải thích nội dung mới trên màn hình bằng tiếng Việt." }
        pauseButton?.text = if (state.paused) "Tiếp tục" else "Tạm dừng"
        voiceButton?.text = if (state.voice) "Tắt tiếng" else "Bật tiếng"
    }

    private fun finishSession(message: String) {
        if (closing) return
        closing = true
        LiveSession.update { it.copy(running = false, connected = false, microphone = false, status = message) }
        stopSelf()
    }

    override fun onDestroy() {
        closing = true
        ocrScope.cancel()
        if (Build.VERSION.SDK_INT >= 29) {
            thermalListener?.let { getSystemService(PowerManager::class.java).removeThermalStatusListener(it) }
            thermalListener = null
        }
        CoroutineScope(Dispatchers.IO).launch {
            ocrScope.coroutineContext[Job]?.join()
            PaddleLiveOcr.release()
        }
        ocrCandidates = emptyList()
        previousPixels = null
        client?.close(); client = null
        audio?.close(); audio = null
        runCatching { display?.release() }; display = null
        reader?.setOnImageAvailableListener(null, null)
        runCatching { reader?.close() }; reader = null
        runCatching { projection?.stop() }; projection = null
        captureHandler?.removeCallbacksAndMessages(null)
        captureHandler?.post { capturedBitmap?.recycle(); capturedBitmap = null; capturedReader = null }
        captureThread?.quitSafely(); captureThread = null
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        overlay = null
        speakingWave?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        speakingWave = null
        readingHighlight?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        readingHighlight = null
        openPanel = null
        main.removeCallbacksAndMessages(null)
        LiveSession.update { it.copy(running = false, connected = false, microphone = false) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
