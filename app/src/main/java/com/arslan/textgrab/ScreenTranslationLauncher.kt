package com.arslan.textgrab

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Starts the overlay from the current screen after Android's permission prompts. */
internal class ScreenTranslationLauncher(
    private val activity: AppCompatActivity,
    private val anchor: () -> View,
) {
    private var waitingForOverlay: Job? = null
    private val notificationPermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { start() }
    private val microphonePermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startWithMicrophone()
        else Snackbar.make(anchor(), R.string.guide_mic_required, Snackbar.LENGTH_LONG).show()
    }
    private val overlayPermission = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(activity)) start()
        else Snackbar.make(anchor(), R.string.live_overlay_needed, Snackbar.LENGTH_LONG).show()
    }
    private val projectionPermission = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            startService(result.resultCode, data)
        }
    }

    fun start() {
        if (ScreenTranslationSession.state.value.running) {
            activity.moveTaskToBack(true)
            return
        }
        if (waitingForOverlay?.isActive == true) return
        if (GeminiCredentials.read(activity).isBlank()) {
            editKey { start() }
            return
        }
        val prefs = activity.getSharedPreferences("gemini_live_ui", Activity.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("notification_asked", false)) {
            prefs.edit().putBoolean("notification_asked", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (!Settings.canDrawOverlays(activity)) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.live_overlay_title)
                .setMessage(R.string.live_overlay_needed)
                .setNegativeButton(R.string.home_cancel, null)
                .setPositiveButton(R.string.live_open_settings) { _, _ ->
                    overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}")))
                }.show()
            return
        }
        val manager = activity.getSystemService(MediaProjectionManager::class.java)
        val consent = if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else manager.createScreenCaptureIntent()
        projectionPermission.launch(consent)
    }

    private fun hasMicrophonePermission() =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun enableGuide() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.guide_enable)
            .setMessage(R.string.guide_consent)
            .setNegativeButton(R.string.home_cancel, null)
            .setPositiveButton(R.string.guide_enable) { _, _ ->
                if (hasMicrophonePermission()) startWithMicrophone()
                else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            }.show()
    }

    private fun startWithMicrophone() {
        activity.getSharedPreferences("gemini_live_ui", Activity.MODE_PRIVATE)
            .edit().putBoolean("voice_guide_enabled", true).apply()
        if (ScreenTranslationSession.state.value.running && !ScreenTranslationSession.state.value.guideReady) {
            activity.stopService(Intent(activity, ScreenTranslationService::class.java))
            activity.lifecycleScope.launch {
                val stopped = withTimeoutOrNull(3_000) { ScreenTranslationSession.state.first { !it.running } }
                if (stopped != null) start()
                else Snackbar.make(anchor(), "Hãy tắt nút nổi rồi bật lại để dùng micro.", Snackbar.LENGTH_LONG).show()
            }
        } else start()
    }

    private fun startService(resultCode: Int, data: Intent) {
        if (ScreenTranslationSession.state.value.running || waitingForOverlay?.isActive == true) return
        val starting = ScreenTranslationState(status = "Đang bật nút Dịch…")
        ScreenTranslationSession.reset(starting)
        try {
            val guideEnabled = activity.getSharedPreferences("gemini_live_ui", Activity.MODE_PRIVATE)
                .getBoolean("voice_guide_enabled", false) && hasMicrophonePermission()
            ContextCompat.startForegroundService(activity, Intent(activity, ScreenTranslationService::class.java)
                .setAction(ScreenTranslationService.START)
                .putExtra(ScreenTranslationService.EXTRA_ENABLE_GUIDE, guideEnabled)
                .putExtra(LiveScreenService.EXTRA_RESULT, resultCode)
                .putExtra(LiveScreenService.EXTRA_DATA, data))
        } catch (_: Exception) {
            val failed = ScreenTranslationState(status = "Không bật được nút Dịch. Hãy thử lại.")
            ScreenTranslationSession.reset(failed)
            Snackbar.make(anchor(), failed.status, Snackbar.LENGTH_LONG).show()
            return
        }
        waitingForOverlay = activity.lifecycleScope.launch {
            val state = ScreenTranslationSession.state.first { it != starting }
            if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
            if (state.running && state.ready) activity.moveTaskToBack(true)
            else Snackbar.make(anchor(), state.status, Snackbar.LENGTH_LONG).show()
        }
    }

    fun editKey(onSaved: () -> Unit = {}) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val input = EditText(activity).apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            typeface = Typeface.DEFAULT
            hint = activity.getString(R.string.live_key_hint)
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            textSize = 16f
            setTextColor(Color.rgb(248, 252, 249))
            setHintTextColor(Color.rgb(180, 204, 197))
            minHeight = dp(52)
            background = GradientDrawable().apply {
                setColor(Color.rgb(26, 52, 48))
                setStroke(dp(1), Color.rgb(103, 146, 134))
                cornerRadius = dp(12).toFloat()
            }
            setPaddingRelative(dp(14), dp(10), dp(14), dp(10))
        }
        val form = FrameLayout(activity).apply {
            setPaddingRelative(dp(24), dp(6), dp(24), dp(10))
            addView(input, FrameLayout.LayoutParams(-1, -2))
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.live_key_title)
            .setMessage(R.string.live_key_instructions)
            .setBackground(GradientDrawable().apply {
                setColor(Color.rgb(15, 35, 32))
                cornerRadius = dp(24).toFloat()
            })
            .setView(form)
            .setNegativeButton(R.string.home_cancel, null).setPositiveButton(R.string.engine_save, null).create()
        dialog.window?.let { window ->
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setDimAmount(.55f)
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim()
                if (value.isBlank()) {
                    input.error = activity.getString(R.string.live_key_missing)
                    return@setOnClickListener
                }
                runCatching { GeminiCredentials.save(activity, value) }.onSuccess {
                    input.text.clear()
                    dialog.dismiss()
                    onSaved()
                }.onFailure { input.error = activity.getString(R.string.live_key_save_failed) }
            }
        }
        dialog.show()
    }
}
