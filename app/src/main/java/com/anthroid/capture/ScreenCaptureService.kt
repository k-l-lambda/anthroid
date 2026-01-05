package com.anthroid.capture

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.anthroid.R
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground service that holds MediaProjection for screen capture and audio recording.
 * MediaProjection must be held by a foreground service to survive activity lifecycle.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val NOTIFICATION_ID = 9999

        @Volatile
        private var instance: ScreenCaptureService? = null

        @Volatile
        private var mediaProjection: MediaProjection? = null

        @Volatile
        private var pendingResultCode: Int = Activity.RESULT_CANCELED

        @Volatile
        private var pendingResultData: Intent? = null

        private val initLatch = AtomicReference<CountDownLatch?>(null)

        fun isRunning(): Boolean = instance != null && mediaProjection != null

        /**
         * Set the MediaProjection permission result from activity.
         * Call this before starting the service.
         */
        fun setProjectionResult(resultCode: Int, data: Intent?) {
            pendingResultCode = resultCode
            pendingResultData = data
            Log.i(TAG, "Projection result set: resultCode=$resultCode")
        }

        /**
         * Start the service. Returns true if service start was initiated.
         * Check isRunning() after a short delay to confirm initialization.
         */
        fun start(context: Context): Boolean {
            if (pendingResultCode != Activity.RESULT_OK || pendingResultData == null) {
                Log.e(TAG, "No valid projection result")
                return false
            }

            val intent = Intent(context, ScreenCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            Log.i(TAG, "Service start initiated")
            return true
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }

        /**
         * Take a screenshot and save to file.
         * @return File path of the screenshot, or null on failure
         */
        fun takeScreenshot(context: Context): String? {
            val projection = mediaProjection
            if (projection == null) {
                Log.e(TAG, "MediaProjection not available")
                return null
            }

            val inst = instance
            if (inst == null) {
                Log.e(TAG, "Service instance not available")
                return null
            }

            return inst.captureScreen(context, projection)
        }

        /**
         * Start recording system audio (API 29+).
         * @return File path where audio will be saved, or null on failure
         */
        fun startAudioCapture(context: Context): String? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                Log.e(TAG, "Audio capture requires API 29+")
                return null
            }

            val projection = mediaProjection
            if (projection == null) {
                Log.e(TAG, "MediaProjection not available")
                return null
            }

            val inst = instance
            if (inst == null) {
                Log.e(TAG, "Service instance not available")
                return null
            }

            return inst.startAudioRecording(context, projection)
        }

        /**
         * Stop audio recording.
         * @return File path of the recorded audio, or null if not recording
         */
        fun stopAudioCapture(): String? {
            return instance?.stopAudioRecording()
        }

        fun isRecordingAudio(): Boolean = instance?.isRecording == true
    }

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var audioRecord: AudioRecord? = null
    private var audioFile: File? = null
    private var isRecording = false
    private var recordingThread: Thread? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate")
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service onStartCommand")

        // Start foreground with notification
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Initialize MediaProjection
        initializeMediaProjection()

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Service onDestroy")
        cleanup()
        instance = null
        super.onDestroy()
    }

    private fun initializeMediaProjection() {
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(pendingResultCode, pendingResultData!!)

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection stopped")
                    cleanup()
                }
            }, handler)

            Log.i(TAG, "MediaProjection initialized successfully")

            // Signal initialization complete
            initLatch.get()?.countDown()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaProjection", e)
            initLatch.get()?.countDown()
            stopSelf()
        }
    }

    private fun captureScreen(context: Context, projection: MediaProjection): String? {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        Log.i(TAG, "Capturing screen: ${width}x${height} @ $density dpi")

        val latch = CountDownLatch(1)
        var resultPath: String? = null

        try {
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

            // Set listener BEFORE creating VirtualDisplay to catch the first frame
            imageReader!!.setOnImageAvailableListener({ reader ->
                var image: android.media.Image? = null
                try {
                    image = reader.acquireLatestImage()
                    if (image != null) {
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * width

                        val bitmap = Bitmap.createBitmap(
                            width + rowPadding / pixelStride,
                            height,
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap.copyPixelsFromBuffer(buffer)

                        // Crop to actual size if needed
                        val croppedBitmap = if (rowPadding > 0) {
                            Bitmap.createBitmap(bitmap, 0, 0, width, height)
                        } else {
                            bitmap
                        }

                        // Save to file
                        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                        val file = File(context.cacheDir, "screenshot_$timestamp.png")
                        FileOutputStream(file).use { out ->
                            croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }

                        resultPath = file.absolutePath
                        Log.i(TAG, "Screenshot saved: $resultPath")

                        if (croppedBitmap !== bitmap) {
                            croppedBitmap.recycle()
                        }
                        bitmap.recycle()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to capture image", e)
                } finally {
                    image?.close()
                    latch.countDown()
                }
            }, handler)

            // Now create VirtualDisplay - listener is ready to catch the first frame
            virtualDisplay = projection.createVirtualDisplay(
                "ScreenCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null, handler
            )

            // Wait for capture (max 3 seconds)
            latch.await(3, TimeUnit.SECONDS)

        } catch (e: Exception) {
            Log.e(TAG, "Screenshot failed", e)
        } finally {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
        }

        return resultPath
    }

    @Suppress("DEPRECATION")
    private fun startAudioRecording(context: Context, projection: MediaProjection): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null
        }

        if (isRecording) {
            Log.w(TAG, "Already recording audio")
            return audioFile?.absolutePath
        }

        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            audioFile = File(context.cacheDir, "audio_$timestamp.wav")

            // Configure AudioPlaybackCapture
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_IN_STEREO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                return null
            }

            isRecording = true
            audioRecord?.startRecording()

            // Start recording thread
            recordingThread = Thread {
                writeAudioToFile(sampleRate, 2, bufferSize)
            }
            recordingThread?.start()

            Log.i(TAG, "Audio recording started: ${audioFile?.absolutePath}")
            return audioFile?.absolutePath

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio recording", e)
            isRecording = false
            audioRecord?.release()
            audioRecord = null
            return null
        }
    }

    private fun writeAudioToFile(sampleRate: Int, channels: Int, bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        val audioData = mutableListOf<Byte>()

        try {
            while (isRecording && audioRecord != null) {
                val read = audioRecord?.read(buffer, 0, bufferSize) ?: -1
                if (read > 0) {
                    for (i in 0 until read) {
                        audioData.add(buffer[i])
                    }
                }
            }

            // Write WAV file
            audioFile?.let { file ->
                FileOutputStream(file).use { out ->
                    writeWavHeader(out, audioData.size, sampleRate, channels, 16)
                    out.write(audioData.toByteArray())
                }
                Log.i(TAG, "Audio saved: ${file.absolutePath} (${audioData.size} bytes)")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error writing audio", e)
        }
    }

    private fun writeWavHeader(out: FileOutputStream, audioLength: Int, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataLength = audioLength
        val totalLength = 36 + dataLength

        out.write("RIFF".toByteArray())
        out.write(intToByteArray(totalLength))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(intToByteArray(16)) // Subchunk1Size
        out.write(shortToByteArray(1)) // AudioFormat (PCM)
        out.write(shortToByteArray(channels.toShort()))
        out.write(intToByteArray(sampleRate))
        out.write(intToByteArray(byteRate))
        out.write(shortToByteArray(blockAlign.toShort()))
        out.write(shortToByteArray(bitsPerSample.toShort()))
        out.write("data".toByteArray())
        out.write(intToByteArray(dataLength))
    }

    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte(),
            ((value shr 16) and 0xff).toByte(),
            ((value shr 24) and 0xff).toByte()
        )
    }

    private fun shortToByteArray(value: Short): ByteArray {
        return byteArrayOf(
            (value.toInt() and 0xff).toByte(),
            ((value.toInt() shr 8) and 0xff).toByte()
        )
    }

    private fun stopAudioRecording(): String? {
        if (!isRecording) {
            return null
        }

        isRecording = false
        try {
            recordingThread?.join(2000)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted waiting for recording thread", e)
        }

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        recordingThread = null

        val path = audioFile?.absolutePath
        Log.i(TAG, "Audio recording stopped: $path")
        return path
    }

    private fun cleanup() {
        stopAudioRecording()
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Screen capture service notification"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Capture Active")
            .setContentText("Anthroid is capturing screen content")
            .setSmallIcon(R.drawable.ic_service_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
