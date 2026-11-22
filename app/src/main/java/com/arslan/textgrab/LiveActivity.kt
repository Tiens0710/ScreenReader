package com.arslan.textgrab

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.arslan.textgrab.databinding.ActivityLiveBinding
import kotlinx.coroutines.launch

class LiveActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLiveBinding
    private val translationLauncher = ScreenTranslationLauncher(this) { binding.root }

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
        binding.btnLiveKey.setOnClickListener { translationLauncher.editKey(::refreshKeyStatus) }
        binding.btnLiveOptions.setOnClickListener {
            binding.liveOptions.isVisible = !binding.liveOptions.isVisible
            binding.btnLiveOptions.setText(if (binding.liveOptions.isVisible)
                R.string.simple_options_close else R.string.simple_options)
        }
        binding.btnLiveStart.setOnClickListener {
            if (ScreenTranslationSession.state.value.running) command(ScreenTranslationService.STOP) else translationLauncher.start()
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

                }
            }
        }
    }

    private fun command(action: String) {
        if (ScreenTranslationSession.state.value.running) startService(Intent(this, ScreenTranslationService::class.java).setAction(action))
    }
    private fun refreshKeyStatus() {
        binding.liveKeyStatus.setText(if (GeminiCredentials.read(this).isNotBlank()) R.string.live_key_saved else R.string.live_key_missing)
    }
}
