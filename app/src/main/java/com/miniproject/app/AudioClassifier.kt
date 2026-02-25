package com.miniproject.app

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Wraps the YAMNet TFLite model for audio classification.
 * Classifies 1-second audio into 521 sound categories.
 *
 * YAMNet expects: 15600 float samples at 16 kHz (0.975s).
 * We resample from 44100 Hz and normalize Short PCM → Float [-1.0, 1.0].
 */
class AudioClassifier(private val context: Context) {

    companion object {
        private const val TAG = "AudioClassifier"
        private const val MODEL_FILE = "yamnet.tflite"
        private const val CLASS_MAP_FILE = "yamnet_class_map.csv"
        private const val YAMNET_SAMPLE_RATE = 16000
        private const val YAMNET_INPUT_SAMPLES = 15600 // 0.975s at 16kHz
    }

    private var interpreter: Interpreter? = null
    private var classLabels: List<String> = emptyList()
    private var isInitialized = false

    data class ClassificationResult(
        val label: String,
        val confidence: Float,
        val topResults: List<Pair<String, Float>> // top-3 labels with scores
    )

    /**
     * Initialize the model and load class labels.
     * Called automatically on first classify() call (lazy loading).
     */
    private fun initialize() {
        if (isInitialized) return
        try {
            val model = loadModelFile()
            val options = Interpreter.Options().apply {
                numThreads = 2
            }
            interpreter = Interpreter(model, options)
            classLabels = loadClassLabels()
            isInitialized = true
            Log.d(TAG, "YAMNet initialized with ${classLabels.size} classes")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize YAMNet", e)
        }
    }

    /**
     * Classify a short array of PCM 16-bit mono audio.
     *
     * @param samples    Raw PCM samples (e.g. from AudioRecord)
     * @param sampleRate Source sample rate (e.g. 44100)
     * @return Classification result with top label and confidence, or null on failure
     */
    fun classify(samples: ShortArray, sampleRate: Int): ClassificationResult? {
        // Lazy-load model on first use
        if (!isInitialized) initialize()
        val interp = interpreter ?: return null
        if (samples.isEmpty()) return null

        try {
            // 1. Resample to 16 kHz
            val resampled = resample(samples, sampleRate, YAMNET_SAMPLE_RATE)

            // 2. Take exactly YAMNET_INPUT_SAMPLES, pad with zeros if needed
            val inputSamples = FloatArray(YAMNET_INPUT_SAMPLES)
            val copyLen = minOf(resampled.size, YAMNET_INPUT_SAMPLES)
            for (i in 0 until copyLen) {
                // Normalize Short to Float [-1.0, 1.0]
                inputSamples[i] = resampled[i].toFloat() / 32768f
            }

            // 3. Prepare input buffer
            val inputBuffer = ByteBuffer.allocateDirect(YAMNET_INPUT_SAMPLES * 4).apply {
                order(ByteOrder.nativeOrder())
                for (sample in inputSamples) {
                    putFloat(sample)
                }
                rewind()
            }

            // 4. Prepare output — YAMNet outputs [1][521] scores
            val outputScores = Array(1) { FloatArray(521) }

            // 5. Run inference
            interp.run(inputBuffer, outputScores)

            // 6. Find top results
            val scores = outputScores[0]
            val indexedScores = scores.mapIndexed { index, score -> index to score }
                .sortedByDescending { it.second }
                .take(3)

            val topResults = indexedScores.map { (idx, score) ->
                val label = if (idx < classLabels.size) classLabels[idx] else "Unknown"
                label to score
            }

            val topLabel = topResults.firstOrNull()?.first ?: "Unknown"
            val topConf = topResults.firstOrNull()?.second ?: 0f

            Log.d(TAG, "Classification: $topLabel (${String.format("%.1f", topConf * 100)}%) " +
                    "| ${topResults.joinToString { "${it.first}: ${String.format("%.0f", it.second * 100)}%" }}")

            return ClassificationResult(
                label = topLabel,
                confidence = topConf,
                topResults = topResults
            )
        } catch (e: Exception) {
            Log.e(TAG, "Classification failed", e)
            return null
        }
    }

    /**
     * Resample audio using linear interpolation.
     */
    private fun resample(input: ShortArray, srcRate: Int, dstRate: Int): ShortArray {
        if (srcRate == dstRate) return input

        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val outputLength = (input.size / ratio).toInt()
        val output = ShortArray(outputLength)

        for (i in 0 until outputLength) {
            val srcIndex = i * ratio
            val srcIndexInt = srcIndex.toInt()
            val fraction = srcIndex - srcIndexInt

            val sample1 = input[srcIndexInt]
            val sample2 = if (srcIndexInt + 1 < input.size) input[srcIndexInt + 1] else sample1

            output[i] = (sample1 + fraction * (sample2 - sample1)).toInt().toShort()
        }

        return output
    }

    /**
     * Load TFLite model from assets as memory-mapped file for efficiency.
     */
    private fun loadModelFile(): MappedByteBuffer {
        val assetFd = context.assets.openFd(MODEL_FILE)
        val inputStream = FileInputStream(assetFd.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            assetFd.startOffset,
            assetFd.declaredLength
        )
    }

    /**
     * Load class labels from yamnet_class_map.csv.
     * Format: index,mid,display_name
     */
    private fun loadClassLabels(): List<String> {
        val labels = mutableListOf<String>()
        context.assets.open(CLASS_MAP_FILE).use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                // Skip header
                reader.readLine()
                var line = reader.readLine()
                while (line != null) {
                    // Parse CSV: index,mid,display_name
                    // display_name may be quoted if it contains commas
                    val parts = parseCsvLine(line)
                    if (parts.size >= 3) {
                        labels.add(parts[2])
                    }
                    line = reader.readLine()
                }
            }
        }
        return labels
    }

    /**
     * Simple CSV parser that handles quoted fields.
     */
    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false

        for (char in line) {
            when {
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    result.add(current.toString().trim())
                    current = StringBuilder()
                }
                else -> current.append(char)
            }
        }
        result.add(current.toString().trim())
        return result
    }

    /**
     * Clean up resources.
     */
    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
