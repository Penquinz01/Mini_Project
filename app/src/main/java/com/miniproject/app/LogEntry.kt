package com.miniproject.app

/**
 * Represents a single sound event recorded by the MicrophoneService.
 */
data class LogEntry(
    val id: Long = 0,
    val timestamp: Long,
    val soundClass: String,
    val dbLevel: Float,
    val confidence: Float,
    val isEmergency: Boolean
)
