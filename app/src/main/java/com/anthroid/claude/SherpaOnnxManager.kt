package com.anthroid.claude

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manager for sherpa-onnx speech recognition using SenseVoice model.
 * Model files are loaded from app's files directory (downloaded separately).
 */
class SherpaOnnxManager(private val context: Context) {

    companion object {
        private const val TAG = "SherpaOnnxManager"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val MODEL_DIR = "sherpa-onnx-sensevoice"
        private const val MODEL_FILENAME = "model.int8.onnx"
        private const val TOKENS_FILENAME = "tokens.txt"

        fun isModelInstalled(context: Context): Boolean {
            val modelDir = File(context.filesDir, MODEL_DIR)
            val modelFile = File(modelDir, MODEL_FILENAME)
            val tokensFile = File(modelDir, TOKENS_FILENAME)
            return modelFile.exists() && tokensFile.exists()
        }

        fun getModelDir(context: Context): File = File(context.filesDir, MODEL_DIR)
    }

    private var recognizer: OfflineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var isRecording = false
    private val audioBuffer = mutableListOf<Float>()

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (_isInitialized.value) {
            Log.d(TAG, "Already initialized")
            return@withContext true
        }

        if (!isModelInstalled(context)) {
            Log.e(TAG, "SenseVoice model not installed. Please install from Settings.")
            return@withContext false
        }

        try {
            Log.d(TAG, "Initializing SenseVoice recognizer...")

            val modelDir = getModelDir(context)
            val modelPath = File(modelDir, MODEL_FILENAME).absolutePath
            val tokensPath = File(modelDir, TOKENS_FILENAME).absolutePath

            val featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0.0f)
            val senseVoiceConfig = OfflineSenseVoiceModelConfig(model = modelPath, language = "", useInverseTextNormalization = true)
            val modelConfig = OfflineModelConfig(senseVoice = senseVoiceConfig, tokens = tokensPath, numThreads = 2, debug = false, provider = "cpu", modelType = "sense_voice")
            val config = OfflineRecognizerConfig(featConfig = featConfig, modelConfig = modelConfig, decodingMethod = "greedy_search")

            recognizer = OfflineRecognizer(config = config)

            _isInitialized.value = true
            Log.d(TAG, "SenseVoice recognizer initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize recognizer: ${e.message}", e)
            false
        }
    }

    fun startRecording(): Boolean {
        if (!_isInitialized.value) { Log.e(TAG, "Recognizer not initialized"); return false }
        if (isRecording) { Log.w(TAG, "Already recording"); return false }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted"); return false
        }
        try {
            audioBuffer.clear()
            _recognizedText.value = ""
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT).coerceAtLeast(SAMPLE_RATE)
            audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) { Log.e(TAG, "AudioRecord failed to initialize"); audioRecord?.release(); audioRecord = null; return false }
            isRecording = true; _isListening.value = true; audioRecord?.startRecording()
            recordingThread = Thread { collectAudio(bufferSize) }.apply { start() }
            Log.d(TAG, "Started recording"); return true
        } catch (e: Exception) { Log.e(TAG, "Failed to start recording: ${e.message}", e); stopRecording(); return false }
    }

    fun stopRecording(): String {
        if (!isRecording) return _recognizedText.value
        isRecording = false; _isListening.value = false
        try {
            audioRecord?.stop(); audioRecord?.release(); audioRecord = null
            recordingThread?.join(500); recordingThread = null
            val audioSamples = synchronized(audioBuffer) { audioBuffer.toFloatArray() }
            if (audioSamples.isEmpty()) { Log.w(TAG, "No audio collected"); return "" }
            Log.d(TAG, "Recognizing ${audioSamples.size} samples (${audioSamples.size / SAMPLE_RATE.toFloat()}s)")
            val stream = recognizer?.createStream() ?: run { Log.e(TAG, "Failed to create stream"); return "" }
            stream.acceptWaveform(audioSamples, SAMPLE_RATE)
            recognizer?.decode(stream)
            val result = recognizer?.getResult(stream)
            val text = result?.text?.trim() ?: ""
            stream.release()
            Log.d(TAG, "Recognition result: $text")
            _recognizedText.value = text
            return text
        } catch (e: Exception) { Log.e(TAG, "Error during recognition: ${e.message}", e); return "" }
    }

    /** Check if currently recording */
    fun isRecording(): Boolean = isRecording

    private fun collectAudio(bufferSize: Int) {
        val shortBuffer = ShortArray(bufferSize / 2)
        while (isRecording && audioRecord != null) {
            try {
                val read = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: 0
                if (read > 0) { synchronized(audioBuffer) { for (i in 0 until read) { audioBuffer.add(shortBuffer[i] / 32768.0f) } } }
            } catch (e: Exception) { Log.e(TAG, "Error collecting audio: ${e.message}", e) }
        }
        Log.d(TAG, "Finished collecting audio, total samples: ${audioBuffer.size}")
    }

    fun release() { Log.d(TAG, "Releasing resources"); stopRecording(); recognizer?.release(); recognizer = null; _isInitialized.value = false }
}
