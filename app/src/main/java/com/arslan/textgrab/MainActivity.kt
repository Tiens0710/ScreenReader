package com.arslan.textgrab

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.nl.translate.TranslateLanguage
import com.arslan.textgrab.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

open class MainActivity : AppCompatActivity(), SelectableOcrView.Listener {

    companion object {
        const val EXTRA_CAPTURED_SCREEN = "com.arslan.textgrab.CAPTURED_SCREEN"
        const val ACTION_CAMERA_PHOTO = "com.arslan.textgrab.action.CAMERA_PHOTO"

        private const val STATE_LAUNCHED_FOR_CAMERA = "launched_for_camera"
        private const val STATE_CAMERA_OPEN = "camera_open"

        private const val SPLASH_FADE_MS = 150L

        private const val TOOLBAR_DELAY = 0.35f
        private const val TOOLBAR_DURATION = 0.5f
        private const val TOOLBAR_SLIDE = 0.4f

        private const val TOOLBAR_SLIDE_FALLBACK_PX = 96

        private const val SPEECH_CHUNK_SIZE = 3500
    }

    private lateinit var binding: ActivityMainBinding
    private val translationLauncher = ScreenTranslationLauncher(this) { binding.root }
    private var currentBitmap: Bitmap? = null
    private var currentResult: OcrEngine.Result? = null
    private var launchedWithImage = false
    private var translateJob: Job? = null
    private var ocrJob: Job? = null
    private var textToSpeech: TextToSpeech? = null
    private var speechReady = false
    private var speechActive = false
    private var lastSpeechUtteranceId: String? = null

    private var contentReady = false

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) openImage(uri)
        }

    private val cameraFile by lazy { CameraActivity.photoFile(this) }

    /** Opened by the camera shortcut's photo: the home screen is never part of this session. */
    private var launchedForCamera = false
    private var cameraOpen = false
    private var viewingCameraPhoto = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(App.localizedContext(newBase))
    }

    private val takePhoto =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            cameraOpen = false
            when {
                result.resultCode == RESULT_OK -> openImage(Uri.fromFile(cameraFile), fromCamera = true)
                launchedForCamera -> finish()
                else -> showHome()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val capturing = intent?.getBooleanExtra(EXTRA_CAPTURED_SCREEN, false) == true
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        splash.setKeepOnScreenCondition { !contentReady }
        splash.setOnExitAnimationListener { provider ->
            if (capturing) {

                provider.remove()
            } else {

                provider.view.animate()
                    .alpha(0f)
                    .setDuration(SPLASH_FADE_MS)
                    .withEndAction { provider.remove() }
            }
        }
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initializeSpeech()

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val base = (24 * resources.displayMetrics.density).toInt()
            binding.homeGroup.updatePadding(top = base + bars.top, bottom = base + bars.bottom)
            insets
        }

        binding.ocrView.listener = this

        binding.topBar.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            binding.ocrView.topInset = v.bottom.toFloat()

            binding.ocrProgress.translationY = v.bottom.toFloat()
        }

        binding.btnPick.setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.btnCamera.isVisible =
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)
        binding.btnCamera.setOnClickListener { launchCamera() }
        binding.btnScan.setOnClickListener { translationLauncher.start() }
        binding.btnVoiceGuide.setOnClickListener { translationLauncher.enableGuide() }
        binding.btnGeminiKey.setOnClickListener {
            translationLauncher.editKey {
                refreshGeminiKeyAction()
                Snackbar.make(binding.root, R.string.live_key_saved, Snackbar.LENGTH_SHORT).show()
            }
        }
        binding.btnScreenTranslationSettings.setOnClickListener {
            startActivity(Intent(this, LiveActivity::class.java))
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ScreenTranslationSession.state.collect { state ->
                    binding.btnScan.setText(if (state.running) R.string.live_minimize else R.string.live_open)
                }
            }
        }
        binding.btnAdvanced.setOnClickListener {
            binding.advancedGroup.isVisible = !binding.advancedGroup.isVisible
            binding.btnAdvanced.setText(
                if (binding.advancedGroup.isVisible) R.string.home_settings_close else R.string.home_settings_open
            )
        }
        OcrEngine.warmUp()

        binding.btnAccessibility.setOnClickListener {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        binding.btnSetAssistant.setOnClickListener {
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }.onFailure {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)) }
            }
        }

        val adbCommand = "adb shell am broadcast --receiver-foreground " +
            "-a ${CaptureReceiver.ACTION_CAPTURE} -p $packageName"
        binding.adbCommand.text = adbCommand
        binding.adbRow.setOnClickListener { copyToClipboard(adbCommand) }

        binding.rowTargetLanguage.setOnClickListener { chooseTargetLanguage() }
        binding.rowLanguagePacks.setOnClickListener {
            LanguagePacksSheet(this) { refreshTranslationRows() }.show()
        }
        binding.rowEngine.setOnClickListener {
            TranslationEngineSheet(this) { refreshTranslationRows() }.show()
        }

        binding.btnBack.setOnClickListener { onBackFromViewer() }
        binding.btnSelectAll.setOnClickListener { binding.ocrView.selectAll() }
        binding.btnCopyAll.setOnClickListener {
            currentResult?.fullText?.takeIf { it.isNotBlank() }?.let { copyToClipboard(it) }
        }
        binding.btnShareAll.setOnClickListener {
            currentResult?.fullText?.takeIf { it.isNotBlank() }?.let { shareText(it) }
        }
        binding.btnTextMode.setOnClickListener { showTextSheet() }
        binding.btnTranslateAll.setOnClickListener {
            if (binding.ocrView.translations != null) resetTranslation() else translateScreen()
        }
        binding.btnTranslateAll.setOnLongClickListener {
            chooseTargetLanguage()
            true
        }
        binding.btnSpeak.setOnClickListener {
            if (speechActive) stopSpeech() else readAloud()
        }

        binding.toolbarCopy.setOnClickListener {
            binding.ocrView.selectedText?.let {
                copyToClipboard(it)
                binding.ocrView.clearSelection()
            }
        }
        binding.toolbarSelectAll.setOnClickListener { binding.ocrView.selectAll() }
        binding.toolbarShare.setOnClickListener { binding.ocrView.selectedText?.let { shareText(it) } }
        binding.toolbarSearch.setOnClickListener { binding.ocrView.selectedText?.let { searchWeb(it) } }

        onBackPressedDispatcher.addCallback(this) {
            when {
                binding.ocrView.hasSelection() -> binding.ocrView.clearSelection()
                binding.viewerGroup.isVisible -> onBackFromViewer()
                else -> finish()
            }
        }

        if (savedInstanceState != null) {
            // A restored activity gets the photo intent again; that photo was already consumed.
            if (intent.action == ACTION_CAMERA_PHOTO) intent.action = Intent.ACTION_MAIN
            cameraOpen = savedInstanceState.getBoolean(STATE_CAMERA_OPEN)
            launchedForCamera = cameraOpen && savedInstanceState.getBoolean(STATE_LAUNCHED_FOR_CAMERA)
        }
        handleIntent(intent)

        binding.root.post { contentReady = true }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_LAUNCHED_FOR_CAMERA, launchedForCamera)
        outState.putBoolean(STATE_CAMERA_OPEN, cameraOpen)
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
        refreshGeminiKeyAction()
        refreshTranslationRows()
    }

    private fun refreshGeminiKeyAction() {
        val label = if (GeminiCredentials.read(this).isBlank()) R.string.home_gemini_key_add
            else R.string.home_gemini_key_change
        binding.btnGeminiKey.setText(label)
    }

    override fun onStop() {
        super.onStop()
        if (ocrJob?.isActive == true || translateJob?.isActive == true) return
        OcrEngine.release()
        Translator.release()
    }

    private fun refreshSetup() {
        binding.btnAccessibility.isVisible = false
        binding.btnSetAssistant.isVisible = !isAssistantApp()
        val needsSetup = binding.btnAccessibility.isVisible
        binding.setupHeader.isVisible = needsSetup
        binding.setupCard.isVisible = needsSetup
        applySegmentShapes(binding.btnAccessibility)
    }

    /** Gives a segmented list its M3 first/middle/last corner shapes for the rows in view. */
    private fun applySegmentShapes(vararg rows: MaterialCardView) {
        val shown = rows.filter { it.isVisible }
        shown.forEachIndexed { index, row ->
            val shape = when {
                shown.size == 1 -> com.google.android.material.R.style.ShapeAppearance_Material3_ListItem_Single
                index == 0 -> com.google.android.material.R.style.ShapeAppearance_Material3_ListItem_First
                index == shown.lastIndex -> com.google.android.material.R.style.ShapeAppearance_Material3_ListItem_Last
                else -> com.google.android.material.R.style.ShapeAppearance_Material3_ListItem_Middle
            }
            row.shapeAppearanceModel = ShapeAppearanceModel.builder(this, shape, 0).build()
        }
    }

    private fun refreshTranslationRows() {
        binding.textTargetLanguage.text = Translator.displayName(Translator.defaultTarget(this))
        val engine = EngineSettings.selected(this)
        binding.textEngine.text = getString(engine.title).let {
            if (engine.isCloud) getString(R.string.engine_row_summary_cloud, it) else it
        }

        binding.rowLanguagePacks.isVisible = !engine.isCloud
        applySegmentShapes(
            binding.btnSetAssistant,
            binding.rowEngine,
            binding.rowLanguagePacks,
            binding.adbRow
        )
        lifecycleScope.launch {
            val count = runCatching { Translator.downloadedLanguages().size }.getOrNull() ?: return@launch
            binding.textLanguagePacks.text = getString(R.string.language_packs_summary, count)
        }
    }

    private fun isAssistantApp(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val roles = getSystemService(android.app.role.RoleManager::class.java) ?: return false
        return roles.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)
    }

    override fun onDestroy() {
        clearViewerContent()
        textToSpeech?.shutdown()
        textToSpeech = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.getBooleanExtra(ScreenTranslationService.EXTRA_ENABLE_GUIDE, false)) {
            intent.removeExtra(ScreenTranslationService.EXTRA_ENABLE_GUIDE)
            translationLauncher.enableGuide()
        }
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }

        val photoTaken = intent.action == ACTION_CAMERA_PHOTO
        val captureRequested = intent.getBooleanExtra(EXTRA_CAPTURED_SCREEN, false)
        intent.removeExtra(EXTRA_CAPTURED_SCREEN)
        intent.action = Intent.ACTION_MAIN
        intent.data = null

        when {
            uri != null -> {
                launchedWithImage = true
                openImage(uri)
            }
            captureRequested -> {
                val captured = CaptureHolder.take()
                if (captured != null) {
                    launchedWithImage = true
                    openBitmap(captured)
                } else if (this is CaptureViewerActivity) finish() else showHome()
            }
            else -> {

                CaptureHolder.take()
                when {
                    photoTaken -> {
                        launchedForCamera = true
                        openImage(Uri.fromFile(cameraFile), fromCamera = true)
                    }
                    cameraOpen -> showCameraPending()
                    else -> showHome()
                }
            }
        }
    }

    private fun launchCamera() {
        cameraOpen = true
        showCameraPending()
        takePhoto.launch(Intent(this, CameraActivity::class.java))
    }

    /** Nothing shows behind the camera, so neither home nor a stale photo flashes when it closes. */
    private fun showCameraPending() {
        clearViewerContent()
        viewingCameraPhoto = false
        binding.homeGroup.isVisible = false
        binding.viewerGroup.isVisible = false
        binding.progressGroup.isVisible = false
    }

    private fun openImage(uri: Uri, fromCamera: Boolean = false) {
        viewingCameraPhoto = fromCamera
        binding.ocrView.capturePreview = false

        showViewer(loading = true)
        startOcr {
            try {
                val bitmap = try {
                    ImageLoader.load(this@MainActivity, uri)
                } finally {
                    if (fromCamera) cameraFile.delete()
                }
                processBitmap(bitmap)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("TextGrab", "Failed to open $uri", e)
                showHome()
                Snackbar.make(
                    binding.root,
                    getString(R.string.load_failed, e.localizedMessage ?: ""),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun openBitmap(bitmap: Bitmap) {
        viewingCameraPhoto = false
        binding.ocrView.capturePreview = true
        clearOpenTransition()

        showImage(bitmap)
        binding.ocrView.playCaptureEntry()
        playToolbarEntry()
        startOcr { recognize(bitmap) }
    }

    private fun clearOpenTransition() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun finishWithoutTransition() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
            finish()
        } else {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun playToolbarEntry() {
        val bar = binding.topBar
        bar.alpha = 0f
        bar.translationY = -bar.height.coerceAtLeast(TOOLBAR_SLIDE_FALLBACK_PX) * TOOLBAR_SLIDE
        bar.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay((SelectableOcrView.CAPTURE_TRANSITION_MS * TOOLBAR_DELAY).toLong())
            .setDuration((SelectableOcrView.CAPTURE_TRANSITION_MS * TOOLBAR_DURATION).toLong())
            .setInterpolator(SelectableOcrView.EMPHASIZED_DECELERATE)

        binding.ocrProgress.alpha = 0f
        binding.ocrProgress.animate()
            .alpha(1f)
            .setStartDelay((SelectableOcrView.CAPTURE_TRANSITION_MS * TOOLBAR_DELAY).toLong())
            .setDuration((SelectableOcrView.CAPTURE_TRANSITION_MS * TOOLBAR_DURATION).toLong())
            .setInterpolator(SelectableOcrView.EMPHASIZED_DECELERATE)
    }

    private fun startOcr(block: suspend () -> Unit) {
        ocrJob?.cancel()
        ocrJob = lifecycleScope.launch { block() }
    }

    private suspend fun processBitmap(bitmap: Bitmap) {
        showImage(bitmap)
        recognize(bitmap)
    }

    private fun showImage(bitmap: Bitmap) {

        resetTranslation()
        binding.selectionToolbar.isVisible = false
        for (v in arrayOf(binding.topBar, binding.ocrProgress, binding.selectionToolbar)) {
            v.animate().cancel()
            v.alpha = 1f
        }

        binding.topBar.translationY = 0f
        currentResult = null
        currentBitmap = bitmap
        binding.ocrView.setImage(bitmap)
        showViewer(loading = false)
    }

    private suspend fun recognize(bitmap: Bitmap) {
        binding.ocrProgress.show()
        try {
            val result = OcrEngine.recognize(bitmap)
            currentResult = result
            binding.ocrView.setContent(bitmap, result)
            if (result.isEmpty) {
                Snackbar.make(binding.root, R.string.no_text_found, Snackbar.LENGTH_LONG).show()
            }
        } finally {
            binding.ocrProgress.hide()
        }
    }

    private fun showHome() {
        clearViewerContent()
        binding.homeGroup.isVisible = true
        binding.viewerGroup.isVisible = false
        binding.progressGroup.isVisible = false
        launchedWithImage = false
        viewingCameraPhoto = false
    }

    private fun showViewer(loading: Boolean) {
        binding.homeGroup.isVisible = false
        binding.viewerGroup.isVisible = true
        binding.progressGroup.isVisible = loading
        if (loading) {
            binding.selectionToolbar.isVisible = false

            clearViewerContent()
        }
    }

    private fun clearViewerContent() {
        stopSpeech()
        ocrJob?.cancel()
        ocrJob = null
        resetTranslation()
        binding.selectionToolbar.isVisible = false
        binding.ocrProgress.hide()
        binding.ocrView.clear()
        currentBitmap = null
        currentResult = null
    }

    private fun onBackFromViewer() {
        if (viewingCameraPhoto) {
            launchCamera()
            return
        }

        if (launchedWithImage && binding.ocrView.canPlayCaptureExit) {
            val fadeOut = (SelectableOcrView.CAPTURE_TRANSITION_MS * TOOLBAR_DURATION).toLong()
            for (v in arrayOf(binding.topBar, binding.ocrProgress, binding.selectionToolbar)) {
                v.animate()
                    .alpha(0f)
                    .setStartDelay(0)
                    .setDuration(fadeOut)
                    .setInterpolator(SelectableOcrView.EMPHASIZED_ACCELERATE)
            }
            ocrJob?.cancel()
            binding.ocrProgress.hide()
            binding.ocrView.playCaptureExit { finishWithoutTransition() }
            return
        }

        clearViewerContent()
        if (launchedWithImage) finish() else showHome()
    }

    private fun initializeSpeech() {
        textToSpeech = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech

            val engine = textToSpeech ?: return@TextToSpeech
            val preferred = engine.setLanguage(Locale.getDefault())
            speechReady = preferred >= TextToSpeech.LANG_AVAILABLE
            if (!speechReady) {
                speechReady = engine.setLanguage(Locale.US) >= TextToSpeech.LANG_AVAILABLE
            }

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    runOnUiThread { setSpeechActive(true) }
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == lastSpeechUtteranceId) {
                        runOnUiThread { setSpeechActive(false) }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == lastSpeechUtteranceId) {
                        runOnUiThread { setSpeechActive(false) }
                    }
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    runOnUiThread { setSpeechActive(false) }
                }
            })
        }
    }

    private fun readAloud() {
        val engine = textToSpeech
        if (!speechReady || engine == null) {
            Snackbar.make(binding.root, R.string.speech_unavailable, Snackbar.LENGTH_LONG).show()
            return
        }

        val selected = binding.ocrView.selectedText?.takeIf { it.isNotBlank() }
        val text = selected ?: currentResult?.let { result ->
            val translations = binding.ocrView.translations
            if (translations == null) {
                result.fullText
            } else {
                result.lineTexts.mapIndexed { index, original ->
                    translations.getOrNull(index)?.takeIf { it.isNotBlank() } ?: original
                }.joinToString("\n")
            }
        }
        if (text.isNullOrBlank()) {
            Snackbar.make(binding.root, R.string.no_text_found, Snackbar.LENGTH_SHORT).show()
            return
        }

        val chunks = splitSpeechText(text)
        val utterancePrefix = "textgrab-${System.currentTimeMillis()}"
        lastSpeechUtteranceId = "$utterancePrefix-${chunks.lastIndex}"
        chunks.forEachIndexed { index, chunk ->
            val result = engine.speak(
                chunk,
                if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                Bundle(),
                "$utterancePrefix-$index",
            )
            if (result == TextToSpeech.ERROR) {
                stopSpeech()
                Snackbar.make(binding.root, R.string.speech_unavailable, Snackbar.LENGTH_LONG).show()
                return
            }
        }
        setSpeechActive(true)
    }

    private fun splitSpeechText(text: String): List<String> {
        val chunks = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            var end = min(start + SPEECH_CHUNK_SIZE, text.length)
            if (end < text.length) {
                val boundary = text.lastIndexOfAny(charArrayOf('\n', '.', '!', '?', ' ', '\t'), end - 1)
                if (boundary > start) end = boundary + 1
            }
            text.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let(chunks::add)
            start = end
        }
        return chunks
    }

    private fun stopSpeech() {
        textToSpeech?.stop()
        setSpeechActive(false)
    }

    private fun setSpeechActive(active: Boolean) {
        if (!::binding.isInitialized || isDestroyed) return
        speechActive = active
        binding.btnSpeak.isChecked = active
        binding.btnSpeak.setIconResource(
            if (active) R.drawable.ic_volume_off else R.drawable.ic_volume_up
        )
        binding.btnSpeak.contentDescription =
            getString(if (active) R.string.stop_reading else R.string.read_aloud)
    }

    override fun onSelectionChanged(text: String?, anchor: RectF?) {
        if (text == null || anchor == null) {
            binding.selectionToolbar.isVisible = false
            return
        }
        binding.selectionToolbar.isVisible = true
        binding.selectionToolbar.post { positionToolbar(anchor) }
    }

    private fun positionToolbar(anchor: RectF) {
        val toolbar = binding.selectionToolbar
        val container = binding.viewerGroup
        val margin = resources.displayMetrics.density * 12f

        var x = anchor.centerX() - toolbar.width / 2f
        x = max(margin, min(x, container.width - toolbar.width - margin))

        var y = anchor.top - toolbar.height - margin * 1.5f
        if (y < container.height * 0.12f) y = anchor.bottom + margin * 1.5f
        y = max(margin, min(y, container.height - toolbar.height - margin))

        toolbar.translationX = x
        toolbar.translationY = y
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))

        if (Build.VERSION.SDK_INT < 33) {
            Snackbar.make(binding.root, R.string.copied, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, null))
    }

    private fun searchWeb(text: String) {
        val search = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(android.app.SearchManager.QUERY, text)
        }
        if (search.resolveActivity(packageManager) != null) {
            startActivity(search)
        } else {
            val browse = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://duckduckgo.com/?q=${Uri.encode(text)}")
            )
            runCatching { startActivity(browse) }
        }
    }

    private fun showTextSheet() {
        val text = currentResult?.fullText
        if (text.isNullOrBlank()) {
            Snackbar.make(binding.root, R.string.no_text_found, Snackbar.LENGTH_SHORT).show()
            return
        }
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_text, null)
        view.findViewById<TextView>(R.id.sheetText).text = text
        view.findViewById<View>(R.id.sheetCopy).setOnClickListener {
            copyToClipboard(text)
            sheet.dismiss()
        }
        view.findViewById<View>(R.id.sheetShare).setOnClickListener { shareText(text) }
        sheet.setContentView(view)
        sheet.show()
    }

    private fun translateScreen() {
        val result = currentResult ?: return
        if (result.isEmpty) {
            Snackbar.make(binding.root, R.string.no_text_found, Snackbar.LENGTH_SHORT).show()
            return
        }
        val target = Translator.defaultTarget(this)
        translateJob?.cancel()
        translateJob = lifecycleScope.launch {
            binding.ocrProgress.show()
            var downloadNote: Snackbar? = null
            try {
                val outcome = Translator.translate(this@MainActivity, result, target) {
                    downloadNote = Snackbar.make(
                        binding.root, R.string.downloading_models, Snackbar.LENGTH_INDEFINITE
                    ).also { it.show() }
                }
                if (currentResult !== result) return@launch
                if (outcome.sources.isEmpty()) {
                    Snackbar.make(
                        binding.root,
                        getString(R.string.already_in_language, Translator.displayName(target)),
                        Snackbar.LENGTH_LONG
                    ).setAction(R.string.change_language) { chooseTargetLanguage() }.show()
                    return@launch
                }
                binding.ocrView.translations = outcome.lines
                setTranslateActive(true)
                Snackbar.make(
                    binding.root,
                    getString(
                        R.string.translated_from,
                        outcome.sources.joinToString(", ") { Translator.displayName(it) },
                        Translator.displayName(target)
                    ),
                    Snackbar.LENGTH_LONG
                ).setAction(R.string.change_language) { chooseTargetLanguage() }.show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("TextGrab", "Translation failed", e)
                Snackbar.make(
                    binding.root,
                    getString(R.string.translation_failed, e.localizedMessage ?: ""),
                    Snackbar.LENGTH_LONG
                ).show()
            } finally {
                downloadNote?.dismiss()
                binding.ocrProgress.hide()
            }
        }
    }

    private fun resetTranslation() {
        translateJob?.cancel()
        translateJob = null
        binding.ocrView.translations = null
        setTranslateActive(false)
    }

    private fun setTranslateActive(active: Boolean) {
        binding.btnTranslateAll.isChecked = active
        binding.btnTranslateAll.contentDescription =
            getString(if (active) R.string.show_original else R.string.translate)
    }

    private fun chooseTargetLanguage() {
        val codes = Translator.languages.sortedBy { Translator.displayName(it) }
        val names = codes.map { Translator.displayName(it) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.translate_to)
            .setSingleChoiceItems(names, codes.indexOf(Translator.defaultTarget(this))) { dialog, which ->
                dialog.dismiss()
                Translator.saveTarget(this, codes[which])
                refreshTranslationRows()
                if (binding.viewerGroup.isVisible) translateScreen()
            }
            .show()
    }
}
