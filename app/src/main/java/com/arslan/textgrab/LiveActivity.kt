package com.arslan.textgrab

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.arslan.textgrab.databinding.ActivityLiveBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class LiveActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLiveBinding
    private var minimizeWhenReady = false
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { beginLive() }
    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) beginLive()
        else Snackbar.make(binding.root, R.string.live_overlay_needed, Snackbar.LENGTH_LONG).show()
    }
    private val projectionPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            try {
                minimizeWhenReady = true
                ScreenTranslationSession.reset(ScreenTranslationState(status = "Đang bật nút Dịch…"))
                ContextCompat.startForegroundService(this, Intent(this, ScreenTranslationService::class.java)
                    .setAction(ScreenTranslationService.START)
                    .putExtra(LiveScreenService.EXTRA_RESULT, result.resultCode)
                    .putExtra(LiveScreenService.EXTRA_DATA, data))
            } catch (_: Exception) {
                minimizeWhenReady = false
                ScreenTranslationSession.reset(ScreenTranslationState(status = "Không bật được nút Dịch. Hãy thử lại."))
            }
        } else {
            ScreenTranslationSession.update { it.copy(status = "Chưa bật nút Dịch vì chưa được phép chia sẻ màn hình") }
        }
    }

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(App.localizedContext(newBase)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        binding = ActivityLiveBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        runCatching { GeminiCredentials.importIfPresent(this) }
        refreshKeyStatus()
        binding.btnLiveBack.setOnClickListener { finish() }
        binding.btnLiveKey.setOnClickListener { editKey() }
        binding.btnLiveOptions.setOnClickListener {
            binding.liveOptions.isVisible = !binding.liveOptions.isVisible
            binding.btnLiveOptions.setText(if (binding.liveOptions.isVisible)
                R.string.simple_options_close else R.string.simple_options)
        }
        binding.btnLiveStart.setOnClickListener {
            if (ScreenTranslationSession.state.value.running) command(ScreenTranslationService.STOP) else beginLive()
        }
        binding.btnLiveMinimize.setOnClickListener { moveTaskToBack(true) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ScreenTranslationSession.state.collect { state ->
                    binding.liveStatus.text = state.status
                    binding.btnLiveStart.setText(if (state.running) R.string.tap_disable else R.string.tap_enable)
                    binding.btnLiveStart.setBackgroundResource(if (state.running) R.drawable.bg_glass_danger else R.drawable.bg_glass_hero)
                    binding.btnLiveStart.backgroundTintList = null
                    binding.btnLiveMinimize.isVisible = state.running
                    binding.btnLiveKey.isEnabled = !state.running
                    if (!state.running) minimizeWhenReady = false
                    if (minimizeWhenReady && state.running && state.ready) {
                        minimizeWhenReady = false
                        moveTaskToBack(true)
                    }
                }
            }
        }
    }

    private fun command(action: String) {
        if (ScreenTranslationSession.state.value.running) startService(Intent(this, ScreenTranslationService::class.java).setAction(action))
    }
    override fun onStop() {
        minimizeWhenReady = false
        super.onStop()
    }
    private fun refreshKeyStatus() {
        binding.liveKeyStatus.setText(if (GeminiCredentials.read(this).isNotBlank()) R.string.live_key_saved else R.string.live_key_missing)
    }
    private fun editKey() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.live_key_hint)
            isSingleLine = true
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.live_key_title).setView(input)
            .setNegativeButton(R.string.home_cancel, null).setPositiveButton(R.string.engine_save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim()
                if (value.isBlank()) { input.error = getString(R.string.live_key_missing); return@setOnClickListener }
                runCatching { GeminiCredentials.save(this, value) }.onSuccess {
                    input.text.clear(); refreshKeyStatus(); dialog.dismiss()
                }.onFailure { input.error = getString(R.string.live_key_save_failed) }
            }
        }
        dialog.show()
    }

    private fun beginLive() {
        if (ScreenTranslationSession.state.value.running) return
        if (GeminiCredentials.read(this).isBlank()) {
            binding.liveOptions.isVisible = true
            binding.btnLiveOptions.setText(R.string.simple_options_close)
            editKey(); return
        }
        val prefs = getSharedPreferences("gemini_live_ui", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !prefs.getBoolean("notification_asked", false)) {
            prefs.edit().putBoolean("notification_asked", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS); return
        }
        if (!Settings.canDrawOverlays(this)) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.live_overlay_title).setMessage(R.string.live_overlay_needed)
                .setNegativeButton(R.string.home_cancel, null).setPositiveButton(R.string.live_open_settings) { _, _ ->
                    overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }.show()
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        val consent = if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else manager.createScreenCaptureIntent()
        projectionPermission.launch(consent)
    }
}
