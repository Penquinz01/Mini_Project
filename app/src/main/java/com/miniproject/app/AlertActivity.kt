package com.miniproject.app

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.KeyguardManager
import android.content.Context
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.AnimationUtils
import androidx.appcompat.app.AppCompatActivity
import com.miniproject.app.databinding.ActivityAlertBinding

class AlertActivity : AppCompatActivity() {

    companion object {
        const val TAG = "AlertActivity"
        const val EXTRA_SOUND_TYPE = "sound_type"
        const val EXTRA_DB_LEVEL = "db_level"
        const val EXTRA_CONFIDENCE = "confidence"
        private const val AUTO_DISMISS_MS = 30_000L // auto-dismiss after 30s
    }

    private lateinit var binding: ActivityAlertBinding
    private val handler = Handler(Looper.getMainLooper())
    private val autoDismissRunnable = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ---- Show over lock screen / turn screen on ----
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityAlertBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Populate alert data from intent
        val soundType = intent.getStringExtra(EXTRA_SOUND_TYPE) ?: "Unknown Sound"
        val dbLevel = intent.getFloatExtra(EXTRA_DB_LEVEL, 0f)
        val confidence = intent.getIntExtra(EXTRA_CONFIDENCE, 0)

        binding.textAlertSoundType.text = soundType
        binding.textAlertDetails.text = "${String.format("%.0f", dbLevel)} dB  •  Confidence: $confidence%"

        // Dismiss button
        binding.btnDismissAlert.setOnClickListener {
            handler.removeCallbacks(autoDismissRunnable)
            finish()
        }

        // Start pulsing animation on the icon frame
        startPulseAnimation()

        // Start background flash animation
        startFlashAnimation()

        // Auto-dismiss after 30 seconds
        handler.postDelayed(autoDismissRunnable, AUTO_DISMISS_MS)

        Log.d(TAG, "AlertActivity shown: $soundType @ ${dbLevel}dB ($confidence%)")
    }

    private fun startPulseAnimation() {
        val scaleX = ObjectAnimator.ofFloat(binding.iconFrame, View.SCALE_X, 1f, 1.18f, 1f).apply {
            repeatCount = android.animation.ValueAnimator.INFINITE
            duration = 700
        }
        val scaleY = ObjectAnimator.ofFloat(binding.iconFrame, View.SCALE_Y, 1f, 1.18f, 1f).apply {
            repeatCount = android.animation.ValueAnimator.INFINITE
            duration = 700
        }
        val alpha = ObjectAnimator.ofFloat(binding.iconFrame, View.ALPHA, 1f, 0.7f, 1f).apply {
            repeatCount = android.animation.ValueAnimator.INFINITE
            duration = 700
        }
        AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
            start()
        }
    }

    private fun startFlashAnimation() {
        // Alternate the background between deep red and bright red
        val bgFlash = ObjectAnimator.ofArgb(
            binding.alertRoot,
            "backgroundColor",
            0xFFCC0000.toInt(),
            0xFFFF2222.toInt(),
            0xFFCC0000.toInt()
        ).apply {
            duration = 900
            repeatCount = android.animation.ValueAnimator.INFINITE
        }
        bgFlash.start()
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoDismissRunnable)
        super.onDestroy()
    }
}
