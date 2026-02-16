package com.miniproject.app

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlin.math.log10
import kotlin.math.sqrt

class MicrophoneService : Service() {

    companion object {
        const val TAG = "MicrophoneService"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.miniproject.app.STOP_MIC_SERVICE"
        const val ACTION_UPDATE_THRESHOLD = "com.miniproject.app.UPDATE_THRESHOLD"

        // Broadcast action to send dB updates to the activity
        const val ACTION_DB_UPDATE = "com.miniproject.app.DB_UPDATE"
        const val EXTRA_DB_LEVEL = "db_level"
        const val EXTRA_LOUDNESS_LABEL = "loudness_label"
        const val EXTRA_THRESHOLD = "threshold"
        const val EXTRA_ABOVE_THRESHOLD = "above_threshold"

        // Loudness thresholds (dB SPL approximation)
        const val THRESHOLD_QUIET = 40.0
        const val THRESHOLD_MODERATE = 60.0
        const val THRESHOLD_LOUD = 75.0
        const val THRESHOLD_VERY_LOUD = 85.0
        const val THRESHOLD_DANGEROUS = 100.0
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentDb: Double = 0.0
    private var userThreshold: Double = 80.0
    private var lastThresholdAlertTime: Long = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MicrophoneService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRecording()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_THRESHOLD -> {
                userThreshold = intent.getDoubleExtra(EXTRA_THRESHOLD, 80.0)
                Log.d(TAG, "Threshold updated to $userThreshold dB")
                return START_STICKY
            }
        }

        // Read threshold from intent if provided
        userThreshold = intent?.getDoubleExtra(EXTRA_THRESHOLD, 80.0) ?: 80.0
        Log.d(TAG, "Starting with threshold: $userThreshold dB")

        startForegroundWithNotification("Starting...", 0.0)
        acquireWakeLock()
        startRecording()

        return START_STICKY
    }

    private fun startForegroundWithNotification(loudnessLabel: String, dbLevel: Double) {
        val stopIntent = Intent(this, MicrophoneService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID_MIC_SERVICE)
            .setContentTitle("🔊 Sound Level: ${String.format("%.1f", dbLevel)} dB")
            .setContentText("$loudnessLabel — Monitoring surroundings")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(loudnessLabel: String, dbLevel: Double) {
        val stopIntent = Intent(this, MicrophoneService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID_MIC_SERVICE)
            .setContentTitle("🔊 Sound Level: ${String.format("%.1f", dbLevel)} dB")
            .setContentText("$loudnessLabel — Monitoring surroundings")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot update notification", e)
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MiniProject::MicWakeLock"
        ).apply {
            acquire() // No timeout — runs indefinitely
        }
    }

    private fun startRecording() {
        if (isRecording) return

        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            audioRecord?.startRecording()
            isRecording = true
            Log.d(TAG, "Recording started — monitoring sound levels")

            recordingThread = Thread {
                val buffer = ShortArray(bufferSize)
                var updateCounter = 0

                while (isRecording) {
                    val shortsRead = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                    if (shortsRead > 0) {
                        // Calculate RMS amplitude
                        var sum = 0.0
                        for (i in 0 until shortsRead) {
                            sum += (buffer[i] * buffer[i]).toDouble()
                        }
                        val rms = sqrt(sum / shortsRead)

                        // Convert to dB (approximate SPL)
                        // Ref: Short.MAX_VALUE = 32767 for 16-bit audio
                        currentDb = if (rms > 0) {
                            20 * log10(rms / 1.0) // relative dB
                        } else {
                            0.0
                        }

                        // Map to approximate real-world dB SPL range
                        // Raw mic dB is roughly 0-90, we scale to 30-120 dB SPL
                        val dbSpl = (currentDb * 0.9) + 20
                        val clampedDb = dbSpl.coerceIn(0.0, 130.0)

                        val label = getLoudnessLabel(clampedDb)

                        // Update notification every ~1 second (avoid spamming)
                        updateCounter++
                        if (updateCounter >= (sampleRate / bufferSize)) {
                            updateCounter = 0
                            val aboveThreshold = clampedDb >= userThreshold
                            updateNotification(label, clampedDb)

                            // Broadcast dB level to activity
                            val updateIntent = Intent(ACTION_DB_UPDATE).apply {
                                putExtra(EXTRA_DB_LEVEL, clampedDb)
                                putExtra(EXTRA_LOUDNESS_LABEL, label)
                                putExtra(EXTRA_ABOVE_THRESHOLD, aboveThreshold)
                                setPackage(packageName)
                            }
                            sendBroadcast(updateIntent)

                            // Alert if above user-set threshold (max once every 10 seconds)
                            val now = System.currentTimeMillis()
                            if (aboveThreshold && (now - lastThresholdAlertTime) > 10_000) {
                                lastThresholdAlertTime = now
                                NotificationHelper.sendNotification(
                                    this,
                                    "⚠️ Sound Alert!",
                                    "${String.format("%.0f", clampedDb)} dB exceeds your threshold of ${String.format("%.0f", userThreshold)} dB"
                                )
                            }
                        }
                    }
                }
            }.apply {
                name = "SoundLevelMonitor"
                start()
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "Microphone permission not granted", e)
            stopSelf()
        }
    }

    private fun getLoudnessLabel(db: Double): String {
        return when {
            db < THRESHOLD_QUIET -> "🤫 Quiet"
            db < THRESHOLD_MODERATE -> "🔈 Moderate"
            db < THRESHOLD_LOUD -> "🔉 Noisy"
            db < THRESHOLD_VERY_LOUD -> "🔊 Loud"
            db < THRESHOLD_DANGEROUS -> "📢 Very Loud"
            else -> "🚨 DANGEROUS"
        }
    }

    private fun stopRecording() {
        isRecording = false
        recordingThread?.join(2000)
        recordingThread = null

        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null

        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null

        Log.d(TAG, "Recording stopped")
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
        Log.d(TAG, "MicrophoneService destroyed")
    }
}
