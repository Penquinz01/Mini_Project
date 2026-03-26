package com.miniproject.app

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.miniproject.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var isMicServiceRunning = false
    private var currentThreshold = 80.0

    // Receiver for dB updates from the service
    private val dbUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val db = intent?.getDoubleExtra(MicrophoneService.EXTRA_DB_LEVEL, 0.0) ?: 0.0
            val label = intent?.getStringExtra(MicrophoneService.EXTRA_LOUDNESS_LABEL) ?: ""
            val aboveThreshold = intent?.getBooleanExtra(MicrophoneService.EXTRA_ABOVE_THRESHOLD, false) ?: false
            val soundClass = intent?.getStringExtra(MicrophoneService.EXTRA_SOUND_CLASS) ?: ""
            val isCalibrating = intent?.getBooleanExtra(MicrophoneService.EXTRA_CALIBRATING, false) ?: false
            val soundState = intent?.getStringExtra(MicrophoneService.EXTRA_SOUND_STATE) ?: MicrophoneService.STATE_AMBIENT

            if (isCalibrating) {
                val secondsLeft = intent?.getIntExtra(MicrophoneService.EXTRA_CALIBRATION_SECONDS_LEFT, 0) ?: 0
                binding.textCalibrationStatus.text = "🎯 Calibrating... ${secondsLeft}s remaining"
                binding.textCalibrationStatus.visibility = View.VISIBLE
                binding.btnCalibrate.isEnabled = false
                binding.btnCalibrate.text = "Calibrating..."
            } else {
                val calibratedAvg = intent?.getDoubleExtra(MicrophoneService.EXTRA_CALIBRATED_AVG, -1.0) ?: -1.0
                val newThreshold = intent?.getDoubleExtra(MicrophoneService.EXTRA_THRESHOLD, -1.0) ?: -1.0
                if (calibratedAvg >= 0 && newThreshold >= 0) {
                    currentThreshold = newThreshold
                    prefs.edit().putFloat("db_threshold", newThreshold.toFloat()).apply()
                    binding.textCurrentThreshold.text = "Current threshold: ${String.format("%.0f", newThreshold)} dB"
                    binding.textCalibrationStatus.text = "✅ Avg: ${String.format("%.0f", calibratedAvg)} dB → threshold ${String.format("%.0f", newThreshold)} dB"
                    binding.textCalibrationStatus.setTextColor(0xFF388E3C.toInt())
                    binding.textCalibrationStatus.visibility = View.VISIBLE
                }
                binding.btnCalibrate.isEnabled = true
                binding.btnCalibrate.text = "🎯 Recalibrate Now"
            }

            updateSoundState(db, soundClass, soundState, aboveThreshold)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(this, "All permissions granted!", Toast.LENGTH_SHORT).show()
            updateUI()
            // Auto-start mic service after permissions are granted
            if (!isMicServiceRunning && hasMicrophonePermission()) {
                startMicService()
            }
        } else {
            Toast.makeText(this, "Some permissions were denied", Toast.LENGTH_LONG).show()
            updateUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        // calibration result updates; no need to load saved threshold

        NotificationHelper.createNotificationChannels(this)

        binding.textCurrentThreshold.text = "Current threshold: -- dB"

        // Auto-calibrate button
        binding.btnCalibrate.setOnClickListener {
            if (isMicServiceRunning) {
                val calibrateIntent = Intent(this, MicrophoneService::class.java).apply {
                    action = MicrophoneService.ACTION_CALIBRATE
                }
                startService(calibrateIntent)
                binding.textCalibrationStatus.text = "🎯 Starting calibration..."
                binding.textCalibrationStatus.setTextColor(0xFF666666.toInt())
                binding.textCalibrationStatus.visibility = View.VISIBLE
            } else {
                Toast.makeText(this, "Start monitoring first", Toast.LENGTH_SHORT).show()
            }
        }

        // Request permissions
        binding.btnRequestPermissions.setOnClickListener {
            requestAllPermissions()
        }

        // Test notification
        binding.btnTestNotification.setOnClickListener {
            if (hasNotificationPermission()) {
                NotificationHelper.sendNotification(
                    this,
                    "Hello from Mini Project!",
                    "This is a test push notification 🔔"
                )
            } else {
                Toast.makeText(this, "Grant notification permission first", Toast.LENGTH_SHORT).show()
            }
        }

        // Test emergency alert
        binding.btnTestEmergency.setOnClickListener {
            if (hasNotificationPermission()) {
                NotificationHelper.sendEmergencyNotification(
                    context = this,
                    title = "🚨 EMERGENCY DETECTED!",
                    message = "Ambulance siren — Test Alert (100%)",
                    soundClass = "Ambulance (siren)",
                    dbLevel = 95f,
                    confidence = 100
                )
            } else {
                Toast.makeText(this, "Grant notification permission first", Toast.LENGTH_SHORT).show()
            }
        }

        // Toggle mic service
        binding.btnToggleMic.setOnClickListener {
            if (hasMicrophonePermission()) {
                toggleMicService()
            } else {
                Toast.makeText(this, "Grant microphone permission first", Toast.LENGTH_SHORT).show()
            }
        }

        updateUI()
        checkFullScreenIntentPermission()

        // Auto-start monitoring if permissions already granted — always calibrate on start
        if (hasMicrophonePermission() && !isMicServiceRunning) {
            startMicService()
            // Always calibrate on startup to adapt to current environment
            binding.root.postDelayed({
                val calibrateIntent = Intent(this, MicrophoneService::class.java).apply {
                    action = MicrophoneService.ACTION_CALIBRATE
                }
                startService(calibrateIntent)
            }, 1000)
        } else if (!hasMicrophonePermission()) {
            requestAllPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        // Register broadcast receiver for dB updates
        val filter = IntentFilter(MicrophoneService.ACTION_DB_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dbUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(dbUpdateReceiver, filter)
        }
        updateUI()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(dbUpdateReceiver)
    }

    private fun requestAllPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            Toast.makeText(this, "All permissions already granted!", Toast.LENGTH_SHORT).show()
            if (!isMicServiceRunning) startMicService()
        }
    }

    private fun startMicService() {
        val serviceIntent = Intent(this, MicrophoneService::class.java).apply {
            putExtra(MicrophoneService.EXTRA_THRESHOLD, currentThreshold)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        isMicServiceRunning = true
        updateUI()
    }

    private fun toggleMicService() {
        val serviceIntent = Intent(this, MicrophoneService::class.java)
        if (isMicServiceRunning) {
            serviceIntent.action = MicrophoneService.ACTION_STOP
            startService(serviceIntent)
            isMicServiceRunning = false
            
            // Reset UI when stopped
            binding.textDbLevel.text = "-- dB"
            binding.textDetectedSound.text = "❌ Stopped"
            binding.textDetectedSound.setTextColor(0xFF777777.toInt())
            
            val bgColor = 0xFFF5F5F5.toInt()
            binding.rootLayout.setBackgroundColor(bgColor)
            binding.rootScrollView.setBackgroundColor(bgColor)
            binding.cardNowDetecting.setCardBackgroundColor(0xFFE0E0E0.toInt())
        } else {
            startMicService()
        }
        updateUI()
    }

    private fun updateSoundState(db: Double, soundClass: String, soundState: String, aboveThreshold: Boolean) {
        // Update dB readout
        binding.textDbLevel.text = "${String.format("%.1f", db)} dB"

        // Determine display label and colors
        when (soundState) {
            MicrophoneService.STATE_URGENT -> {
                val displaySound = if (soundClass.isNotEmpty()) soundClass else "⚠️ Urgent Sound"
                binding.textDetectedSound.text = "🚨 $displaySound"
                binding.textDetectedSound.setTextColor(0xFFB71C1C.toInt())
                val bgColor = 0xFFFFEBEE.toInt()
                binding.rootLayout.setBackgroundColor(bgColor)
                binding.rootScrollView.setBackgroundColor(bgColor)
                binding.cardNowDetecting.setCardBackgroundColor(0xFFFFCDD2.toInt())
            }
            MicrophoneService.STATE_NORMAL -> {
                val displaySound = if (soundClass.isNotEmpty()) soundClass else "🔊 Loud Sound"
                binding.textDetectedSound.text = displaySound
                binding.textDetectedSound.setTextColor(0xFFE65100.toInt())
                val bgColor = 0xFFFFF8E1.toInt()
                binding.rootLayout.setBackgroundColor(bgColor)
                binding.rootScrollView.setBackgroundColor(bgColor)
                binding.cardNowDetecting.setCardBackgroundColor(0xFFFFECB3.toInt())
            }
            else -> { // STATE_AMBIENT
                binding.textDetectedSound.text = "🤫 Ambient"
                binding.textDetectedSound.setTextColor(0xFF1B5E20.toInt())
                val bgColor = 0xFFE8F5E9.toInt()
                binding.rootLayout.setBackgroundColor(bgColor)
                binding.rootScrollView.setBackgroundColor(bgColor)
                binding.cardNowDetecting.setCardBackgroundColor(0xFFC8E6C9.toInt())
            }
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true
    }

    /**
     * On Android 14+ (UPSIDE_DOWN_CAKE), USE_FULL_SCREEN_INTENT requires explicit user grant.
     * Check and prompt via a toast with a link to Settings if not granted.
     */
    private fun checkFullScreenIntentPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val granted = notifManager.canUseFullScreenIntent()
            binding.textStatusFullScreen.text = if (granted)
                "🚨 Full-Screen Alerts: ✅ Active"
            else
                "🚨 Full-Screen Alerts: ❌ Tap to enable"
            binding.textStatusFullScreen.visibility = View.VISIBLE
            if (!granted) {
                binding.textStatusFullScreen.setOnClickListener {
                    // Open the special app access settings page
                    val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
                Toast.makeText(
                    this,
                    "🚨 Enable 'Full-screen notifications' in Settings for disruptive alerts",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            // Pre-Android 14: full-screen intent works automatically
            binding.textStatusFullScreen.text = "🚨 Full-Screen Alerts: ✅ Active"
            binding.textStatusFullScreen.visibility = View.VISIBLE
        }
    }

    private fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun updateUI() {
        val notifGranted = hasNotificationPermission()
        val micGranted = hasMicrophonePermission()

        binding.textStatusNotification.text = if (notifGranted) "✅ Granted" else "❌ Not Granted"
        binding.textStatusMic.text = if (micGranted) "✅ Granted" else "❌ Not Granted"

        // Hide permission button if all permissions already granted
        val allGranted = notifGranted && micGranted
        binding.btnRequestPermissions.visibility = if (allGranted) View.GONE else View.VISIBLE

        binding.btnTestEmergency.isEnabled = notifGranted
        binding.btnToggleMic.isEnabled = micGranted
        binding.btnToggleMic.text = if (isMicServiceRunning) "🛑 Stop Monitoring" else "🎙️ Start Monitoring"
    }
}
