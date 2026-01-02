package com.anthroid.claude

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Manager for sherpa-onnx streaming speech recognition.
 * Supports Chinese + English bilingual recognition using Zipformer model.
 */
class SherpaOnnxManager(private val context: Context) {

    companion object {
        private const val TAG = "SherpaOnnxManager"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // Model file paths in assets
        private const val MODEL_DIR = "sherpa-onnx-zh-en"
        private const val ENCODER_FILE = "$MODEL_DIR/encoder-epoch-99-avg-1.int8.onnx"
        private const val DECODER_FILE = "$MODEL_DIR/decoder-epoch-99-avg-1.int8.onnx"
        private const val JOINER_FILE = "$MODEL_DIR/joiner-epoch-99-avg-1.int8.onnx"
        private const val TOKENS_FILE = "$MODEL_DIR/tokens.txt"
    }

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    @Volatile
    private var isRecording = false

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    /**
     * Initialize the recognizer with model files from assets.
     * Should be called once during app startup.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (_isInitialized.value) {
            Log.d(TAG, "Already initialized")
            return@withContext true
        }

        try {
            Log.d(TAG, "Initializing sherpa-onnx recognizer...")

            // Feature config - 16kHz sample rate, 80 mel bins
            val featConfig = FeatureConfig(
                sampleRate = SAMPLE_RATE,
                featureDim = 80,
                dither = 0.0f
            )

            // Transducer model config - Zipformer encoder/decoder/joiner
            val transducerConfig = OnlineTransducerModelConfig(
                encoder = ENCODER_FILE,
                decoder = DECODER_FILE,
                joiner = JOINER_FILE
            )

            // Model config
            val modelConfig = OnlineModelConfig(
                transducer = transducerConfig,
                tokens = TOKENS_FILE,
                numThreads = 2,
                debug = false,
                provider = "cpu",
                modelType = "zipformer"
            )

            // Endpoint config for automatic sentence detection
            val endpointConfig = EndpointConfig(
                rule1 = EndpointRule(
                    mustContainNonSilence = false,
                    minTrailingSilence = 2.4f,
                    minUtteranceLength = 0.0f
                ),
                rule2 = EndpointRule(
                    mustContainNonSilence = true,
                    minTrailingSilence = 1.2f,
                    minUtteranceLength = 0.0f
                ),
                rule3 = EndpointRule(
                    mustContainNonSilence = false,
                    minTrailingSilence = 0.0f,
                    minUtteranceLength = 20.0f
                )
            )

            // Full recognizer config
            val config = OnlineRecognizerConfig(
                featConfig = featConfig,
                modelConfig = modelConfig,
                endpointConfig = endpointConfig,
                enableEndpoint = true,
                decodingMethod = "greedy_search",
                maxActivePaths = 4
            )

            // Create recognizer from assets
            recognizer = OnlineRecognizer(
                assetManager = context.assets,
                config = config
            )

            _isInitialized.value = true
            Log.d(TAG, "Sherpa-onnx recognizer initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize recognizer: ${e.message}", e)
            false
        }
    }

    /**
     * Start recording and recognizing speech.
     * Returns false if permission is not granted or already recording.
     */
    fun startRecording(): Boolean {
        if (!_isInitialized.value) {
            Log.e(TAG, "Recognizer not initialized")
            return false
        }

        if (isRecording) {
            Log.w(TAG, "Already recording")
            return false
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            return false
        }

        try {
            // Create new stream for this recording session
            stream = recognizer?.createStream("")
            if (stream == null) {
                Log.e(TAG, "Failed to create stream")
                return false
            }

            // Reset recognized text
            _recognizedText.value = ""

            // Calculate buffer size
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            ).coerceAtLeast(SAMPLE_RATE) // At least 1 second buffer

            // Create AudioRecord
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            isRecording = true
            _isListening.value = true
            audioRecord?.startRecording()

            // Start recording thread
            recordingThread = Thread {
                processAudio(bufferSize)
            }.apply { start() }

            Log.d(TAG, "Started recording")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            stopRecording()
            return false
        }
    }

    /**
     * Stop recording and finalize recognition.
     * Returns the final recognized text.
     */
    fun stopRecording(): String {
        if (!isRecording) {
            return _recognizedText.value
        }

        isRecording = false
        _isListening.value = false

        try {
            // Stop recording
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            // Wait for recording thread to finish
            recordingThread?.join(500)
            recordingThread = null

            // Finalize stream and get final result
            stream?.inputFinished()

            // Decode remaining audio
            while (recognizer?.isReady(stream!!) == true) {
                recognizer?.decode(stream!!)
            }

            // Get final result
            val result = recognizer?.getResult(stream!!)
            val finalText = result?.text ?: _recognizedText.value

            // Release stream
            stream?.release()
            stream = null

            Log.d(TAG, "Stopped recording, final text: $finalText")
            return finalText
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording: ${e.message}", e)
            return _recognizedText.value
        }
    }

    /**
     * Process audio in recording thread.
     */
    private fun processAudio(bufferSize: Int) {
        val shortBuffer = ShortArray(bufferSize / 2)
        val floatBuffer = FloatArray(shortBuffer.size)

        while (isRecording && audioRecord != null && stream != null) {
            try {
                // Read audio data
                val read = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: 0

                if (read > 0) {
                    // Convert to float (normalize to [-1, 1])
                    for (i in 0 until read) {
                        floatBuffer[i] = shortBuffer[i] / 32768.0f
                    }

                    // Feed to recognizer
                    stream?.acceptWaveform(floatBuffer.copyOf(read), SAMPLE_RATE)

                    // Decode if ready
                    while (recognizer?.isReady(stream!!) == true) {
                        recognizer?.decode(stream!!)
                    }

                    // Get partial result
                    val result = recognizer?.getResult(stream!!)
                    val text = result?.text ?: ""

                    if (text.isNotEmpty() && text != _recognizedText.value) {
                        _recognizedText.value = text
                        Log.d(TAG, "Partial result: $text")
                    }

                    // Check for endpoint (sentence end)
                    if (recognizer?.isEndpoint(stream!!) == true) {
                        Log.d(TAG, "Endpoint detected")
                        recognizer?.reset(stream!!)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing audio: ${e.message}", e)
            }
        }
    }

    /**
     * Release all resources.
     */
    fun release() {
        Log.d(TAG, "Releasing resources")
        stopRecording()
        recognizer?.release()
        recognizer = null
        _isInitialized.value = false
    }
}
