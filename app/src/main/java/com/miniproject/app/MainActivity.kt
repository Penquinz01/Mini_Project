package com.miniproject.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
            updateSoundDisplay(db, label, aboveThreshold)
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
        currentThreshold = prefs.getFloat("db_threshold", 80.0f).toDouble()

        NotificationHelper.createNotificationChannels(this)

        // Initialize threshold display
        binding.editThreshold.setText(String.format("%.0f", currentThreshold))
        binding.textCurrentThreshold.text = "Current threshold: ${String.format("%.0f", currentThreshold)} dB"

        // Set threshold button
        binding.btnSetThreshold.setOnClickListener {
            val input = binding.editThreshold.text.toString().toDoubleOrNull()
            if (input != null && input in 1.0..130.0) {
                currentThreshold = input
                prefs.edit().putFloat("db_threshold", input.toFloat()).apply()
                binding.textCurrentThreshold.text = "Current threshold: ${String.format("%.0f", input)} dB"
                Toast.makeText(this, "Threshold set to ${String.format("%.0f", input)} dB", Toast.LENGTH_SHORT).show()

                // Update running service with new threshold
                if (isMicServiceRunning) {
                    val updateIntent = Intent(this, MicrophoneService::class.java).apply {
                        action = MicrophoneService.ACTION_UPDATE_THRESHOLD
                        putExtra(MicrophoneService.EXTRA_THRESHOLD, input)
                    }
                    startService(updateIntent)
                }
            } else {
                Toast.makeText(this, "Enter a value between 1 and 130", Toast.LENGTH_SHORT).show()
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

        // Toggle mic service
        binding.btnToggleMic.setOnClickListener {
            if (hasMicrophonePermission()) {
                toggleMicService()
            } else {
                Toast.makeText(this, "Grant microphone permission first", Toast.LENGTH_SHORT).show()
            }
        }

        updateUI()

        // Auto-start monitoring if permissions already granted
        if (hasMicrophonePermission() && !isMicServiceRunning) {
            startMicService()
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
            binding.textDbLevel.text = "-- dB"
            binding.textLoudnessLabel.text = "Monitoring stopped"
            binding.soundLevelBar.progress = 0
        } else {
            startMicService()
        }
        updateUI()
    }

    private fun updateSoundDisplay(db: Double, label: String, aboveThreshold: Boolean) {
        binding.textDbLevel.text = "${String.format("%.1f", db)} dB"
        binding.textLoudnessLabel.text = label

        // Turn text red when above threshold
        if (aboveThreshold) {
            binding.textDbLevel.setTextColor(0xFFF44336.toInt()) // Red
            binding.textLoudnessLabel.setTextColor(0xFFF44336.toInt())
        } else {
            binding.textDbLevel.setTextColor(getColor(R.color.primary))
            binding.textLoudnessLabel.setTextColor(0xFF444444.toInt())
        }

        // Map 0-130 dB to 0-100 progress
        val progress = ((db / 130.0) * 100).toInt().coerceIn(0, 100)
        binding.soundLevelBar.progress = progress
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true
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

        binding.btnTestNotification.isEnabled = notifGranted
        binding.btnToggleMic.isEnabled = micGranted
        binding.btnToggleMic.text = if (isMicServiceRunning) "🛑 Stop Monitoring" else "🎙️ Start Monitoring"
    }
}
