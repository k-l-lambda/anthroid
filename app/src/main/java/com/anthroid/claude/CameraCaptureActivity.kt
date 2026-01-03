package com.anthroid.claude

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.anthroid.R
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Camera capture activity for taking photos or picking from gallery.
 * Returns captured/selected image URI via setResult().
 */
class CameraCaptureActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CameraCaptureActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        const val EXTRA_IMAGE_URI = "image_uri"
        const val EXTRA_QR_TEXT = "qr_text"
    }

    private lateinit var previewView: PreviewView
    private lateinit var captureButton: ImageButton
    private lateinit var galleryButton: ImageButton
    private lateinit var switchCameraButton: ImageButton
    private lateinit var qrScanButton: ImageButton
    private lateinit var photoControls: LinearLayout
    private lateinit var instructionText: TextView
    private lateinit var qrResultOverlay: LinearLayout
    private lateinit var qrResultText: TextView
    private lateinit var qrCancelButton: Button
    private lateinit var qrConfirmButton: Button
    private lateinit var scanViewfinder: View
    private lateinit var scanControls: LinearLayout
    private lateinit var backToPhotoButton: ImageButton
    private lateinit var cameraExecutor: ExecutorService

    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var isQrScanMode = false
    private var detectedQrText: String? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    // Gallery picker launcher
    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                // Copy to app cache and return
                copyToCache(uri)?.let { cachedUri ->
                    returnImageResult(cachedUri)
                } ?: run {
                    Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_capture)

        supportActionBar?.apply {
            title = "Take Photo"
            setDisplayHomeAsUpEnabled(true)
        }

        previewView = findViewById(R.id.preview_view)
        captureButton = findViewById(R.id.capture_button)
        galleryButton = findViewById(R.id.gallery_button)
        switchCameraButton = findViewById(R.id.switch_camera_button)
        qrScanButton = findViewById(R.id.qr_scan_button)
        photoControls = findViewById(R.id.photo_controls)
        instructionText = findViewById(R.id.instruction_text)
        qrResultOverlay = findViewById(R.id.qr_result_overlay)
        qrResultText = findViewById(R.id.qr_result_text)
        qrCancelButton = findViewById(R.id.qr_cancel_button)
        qrConfirmButton = findViewById(R.id.qr_confirm_button)
        scanViewfinder = findViewById(R.id.scan_viewfinder)
        scanControls = findViewById(R.id.scan_controls)
        backToPhotoButton = findViewById(R.id.back_to_photo_button)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Set up button click listeners
        captureButton.setOnClickListener { takePhoto() }
        galleryButton.setOnClickListener { openGallery() }
        switchCameraButton.setOnClickListener { switchCamera() }
        qrScanButton.setOnClickListener { toggleQrScanMode() }
        qrCancelButton.setOnClickListener { dismissQrResult() }
        qrConfirmButton.setOnClickListener { confirmQrResult() }
        backToPhotoButton.setOnClickListener { toggleQrScanMode() }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun toggleQrScanMode() {
        isQrScanMode = !isQrScanMode
        updateUiForMode()
        startCamera()
    }

    private fun updateUiForMode() {
        if (isQrScanMode) {
            photoControls.visibility = View.GONE
            scanControls.visibility = View.VISIBLE
            scanViewfinder.visibility = View.VISIBLE
            instructionText.text = "Point at QR code"
            supportActionBar?.title = "Scan QR Code"
        } else {
            photoControls.visibility = View.VISIBLE
            scanControls.visibility = View.GONE
            scanViewfinder.visibility = View.GONE
            instructionText.text = "Take a photo or pick from gallery"
            supportActionBar?.title = "Take Photo"
            dismissQrResult()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                cameraProvider.unbindAll()
                if (isQrScanMode) {
                    imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(cameraExecutor, QRCodeAnalyzer(::onQRCodeDetected)) }
                    cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
                } else {
                    cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
                }
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun onQRCodeDetected(text: String) {
        if (detectedQrText != null) return
        detectedQrText = text
        runOnUiThread {
            Log.i(TAG, "QR code detected: " + text.take(50) + "...")
            // Insert text immediately without confirmation
            returnQrResult(text)
        }
    }

    private fun dismissQrResult() {
        detectedQrText = null
        qrResultOverlay.visibility = View.GONE
    }

    private fun confirmQrResult() {
        detectedQrText?.let { returnQrResult(it) }
    }

        private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        // Create output file
        val photoFile = createImageFile()

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    Toast.makeText(baseContext, "Photo capture failed", Toast.LENGTH_SHORT).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = Uri.fromFile(photoFile)
                    Log.d(TAG, "Photo captured: $savedUri")
                    returnImageResult(savedUri)
                }
            }
        )
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        intent.type = "image/*"
        galleryLauncher.launch(intent)
    }

    private fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        startCamera()
    }

    private fun createImageFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val imageDir = File(cacheDir, "images")
        if (!imageDir.exists()) {
            imageDir.mkdirs()
        }
        return File(imageDir, "IMG_${timestamp}.jpg")
    }

    private fun copyToCache(sourceUri: Uri): Uri? {
        return try {
            val inputStream = contentResolver.openInputStream(sourceUri)
            val outputFile = createImageFile()
            inputStream?.use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Uri.fromFile(outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy image to cache", e)
            null
        }
    }

    private fun returnQrResult(text: String) {
        // Copy to clipboard
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("QR Code", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        
        val resultIntent = Intent().apply {
            putExtra(EXTRA_QR_TEXT, text)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun returnImageResult(uri: Uri) {
        val resultIntent = Intent().apply {
            putExtra(EXTRA_IMAGE_URI, uri)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
    private class QRCodeAnalyzer(
        private val onQRCodeDetected: (String) -> Unit
    ) : ImageAnalysis.Analyzer {
        private val scanner = BarcodeScanning.getClient()

        @androidx.camera.core.ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        for (barcode in barcodes) {
                            if (barcode.format == Barcode.FORMAT_QR_CODE) {
                                barcode.rawValue?.let { text ->
                                    onQRCodeDetected(text)
                                    return@addOnSuccessListener
                                }
                            }
                        }
                    }
                    .addOnCompleteListener { imageProxy.close() }
            } else {
                imageProxy.close()
            }
        }
    }
}