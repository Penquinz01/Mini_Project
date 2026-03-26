package com.miniproject.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object NotificationHelper {

    private const val TAG = "NotificationHelper"

    const val CHANNEL_ID_GENERAL = "general_notifications"
    const val CHANNEL_ID_MIC_SERVICE = "mic_service"
    const val CHANNEL_ID_EMERGENCY = "emergency_alerts"

    // Sound classifications → which alert sound to play
    private val CAR_SOUND_KEYWORDS = listOf(
        "car", "vehicle", "horn", "traffic", "motor", "engine", "truck", "bus"
    )
    private val ANIMAL_SOUND_KEYWORDS = listOf(
        "dog", "bark", "animal", "cat", "bird"
    )

    /**
     * Create notification channels (required for Android 8.0+).
     * Call this once during app startup.
     */
    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val generalChannel = NotificationChannel(
                CHANNEL_ID_GENERAL,
                "General Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "General app notifications"
            }

            val micServiceChannel = NotificationChannel(
                CHANNEL_ID_MIC_SERVICE,
                "Microphone Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when the microphone is actively recording in the background"
            }

            // Emergency alerts — max priority, bypass DND
            val emergencyChannel = NotificationChannel(
                CHANNEL_ID_EMERGENCY,
                "Emergency Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Urgent alerts when emergency sounds (sirens, alarms) are detected"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 400)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                enableLights(true)
                lightColor = 0xFFFF0000.toInt()
                // Use alarm audio stream so it plays even in silent mode
                setSound(
                    Uri.parse("android.resource://${context.packageName}/${R.raw.alert_siren}"),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }

            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(generalChannel)
            notificationManager.createNotificationChannel(micServiceChannel)
            notificationManager.createNotificationChannel(emergencyChannel)
        }
    }

    /**
     * Send a regular push-style local notification.
     */
    fun sendNotification(
        context: Context,
        title: String,
        message: String,
        notificationId: Int = System.currentTimeMillis().toInt()
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_GENERAL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    /**
     * Send a full-screen emergency notification that disrupts the screen (like mobile operator alerts).
     * - Launches AlertActivity over the lock screen
     * - Plays a custom alert sound based on the detected sound class
     * - Triggers deep vibration
     */
    fun sendEmergencyNotification(
        context: Context,
        title: String,
        message: String,
        soundClass: String = "",
        dbLevel: Float = 0f,
        confidence: Int = 0,
        notificationId: Int = (System.currentTimeMillis() + 1).toInt()
    ) {
        // ---- Build the full-screen AlertActivity intent ----
        val fullScreenIntent = Intent(context, AlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(AlertActivity.EXTRA_SOUND_TYPE, soundClass.ifEmpty { "Emergency Sound" })
            putExtra(AlertActivity.EXTRA_DB_LEVEL, dbLevel)
            putExtra(AlertActivity.EXTRA_CONFIDENCE, confidence)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // ---- Tap-on-notification also opens the main app ----
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_EMERGENCY)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(openAppPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)   // ← THE KEY: full-screen takeover
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 400, 200, 400, 200, 400, 200, 400))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "Notification permission not granted", e)
        }

        // ---- Vibration ----
        triggerEmergencyVibration(context)

        // ---- Play custom alert sound via MediaPlayer on ALARM stream ----
        playAlertSound(context, soundClass)
    }

    /**
     * Plays a custom alert sound based on the detected sound class:
     *   - Car/vehicle sounds → car horn
     *   - Animal sounds      → dog bark
     *   - Everything else    → siren
     *
     * Plays on the ALARM audio stream so it sounds even in silent/DND mode.
     */
    fun playAlertSound(context: Context, soundClass: String) {
        val lowerClass = soundClass.lowercase()
        val rawResId = when {
            CAR_SOUND_KEYWORDS.any { lowerClass.contains(it) } -> R.raw.alert_car_horn
            ANIMAL_SOUND_KEYWORDS.any { lowerClass.contains(it) } -> R.raw.alert_dog_bark
            else -> R.raw.alert_siren
        }

        try {
            val mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                val afd = context.resources.openRawResourceFd(rawResId)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                isLooping = false
                prepare()
                start()
                // Release after playback completes
                setOnCompletionListener { mp -> mp.release() }
            }
            Log.d(TAG, "Alert sound started: ${context.resources.getResourceEntryName(rawResId)}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play alert sound", e)
        }
    }

    /**
     * Triggers a deep repeating vibration pattern for emergency alerts.
     */
    private fun triggerEmergencyVibration(context: Context) {
        val pattern = longArrayOf(0, 400, 200, 400, 200, 400, 200, 400, 200, 600)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager =
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        }
    }
}
