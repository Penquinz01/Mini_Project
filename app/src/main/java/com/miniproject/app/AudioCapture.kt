package com.miniproject.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility for capturing audio snippets and writing them as WAV files.
 * Files are saved to "Mini Project Audio Recording" folder in Downloads,
 * visible in the Android Files app.
 */
object AudioCapture {

    private const val TAG = "AudioCapture"
    private const val FOLDER_NAME = "Mini Project Audio Recording"

    /**
     * Saves a short array of PCM 16-bit mono samples as a WAV file.
     *
     * @param context    Application context
     * @param samples    The raw PCM 16-bit samples to save
     * @param sampleRate The sample rate used during recording (e.g. 44100)
     */
    fun saveCapture(context: Context, samples: ShortArray, sampleRate: Int, soundLabel: String = "") {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(Date())
        // Sanitize label for use in filename
        val sanitized = soundLabel.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(30)
        val labelPart = if (sanitized.isNotEmpty()) "${sanitized}_" else ""
        val fileName = "capture_${labelPart}$timestamp.wav"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ — use MediaStore so files appear in Files app
                saveViaMediaStore(context, fileName, samples, sampleRate)
            } else {
                // Android 9 and below — write directly to public Downloads folder
                saveDirectly(fileName, samples, sampleRate)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save WAV file", e)
        }
    }

    /**
     * Save via MediaStore (Android 10+). Files end up in:
     * Downloads/Mini Project Audio Recording/capture_xxx.wav
     */
    private fun saveViaMediaStore(context: Context, fileName: String, samples: ShortArray, sampleRate: Int) {
        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER_NAME")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

        if (uri == null) {
            Log.e(TAG, "Failed to create MediaStore entry")
            return
        }

        resolver.openOutputStream(uri)?.use { outputStream ->
            writeWavToStream(outputStream, sampleRate, samples)
        }

        // Mark file as complete so it's visible immediately
        contentValues.clear()
        contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, contentValues, null, null)

        Log.d(TAG, "WAV saved via MediaStore: $fileName (${samples.size} samples)")
    }

    /**
     * Direct file save for Android 9 and below.
     */
    @Suppress("DEPRECATION")
    private fun saveDirectly(fileName: String, samples: ShortArray, sampleRate: Int) {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val folder = File(downloadsDir, FOLDER_NAME)
        if (!folder.exists()) {
            folder.mkdirs()
        }

        val file = File(folder, fileName)
        FileOutputStream(file).use { fos ->
            writeWavToStream(fos, sampleRate, samples)
        }

        Log.d(TAG, "WAV saved directly: ${file.absolutePath} (${samples.size} samples)")
    }

    /**
     * Writes a proper WAV file (RIFF header + 16-bit PCM mono data) to an OutputStream.
     */
    private fun writeWavToStream(outputStream: OutputStream, sampleRate: Int, samples: ShortArray) {
        val numChannels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val dataSize = samples.size * 2
        val fileSize = 36 + dataSize

        val buffer = ByteBuffer.allocate(44 + dataSize).apply {
            order(ByteOrder.LITTLE_ENDIAN)

            // RIFF header
            put('R'.code.toByte())
            put('I'.code.toByte())
            put('F'.code.toByte())
            put('F'.code.toByte())
            putInt(fileSize)
            put('W'.code.toByte())
            put('A'.code.toByte())
            put('V'.code.toByte())
            put('E'.code.toByte())

            // fmt sub-chunk
            put('f'.code.toByte())
            put('m'.code.toByte())
            put('t'.code.toByte())
            put(' '.code.toByte())
            putInt(16)
            putShort(1)
            putShort(numChannels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())

            // data sub-chunk
            put('d'.code.toByte())
            put('a'.code.toByte())
            put('t'.code.toByte())
            put('a'.code.toByte())
            putInt(dataSize)

            // PCM sample data
            for (sample in samples) {
                putShort(sample)
            }
        }

        outputStream.write(buffer.array())
    }
}
