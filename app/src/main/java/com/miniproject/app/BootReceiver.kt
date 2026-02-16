package com.miniproject.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Starts the MicrophoneService automatically when the device boots up.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Device booted — starting MicrophoneService")
            val serviceIntent = Intent(context, MicrophoneService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
