package com.anthroid.claude

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.anthroid.R
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
    }

    private lateinit var previewView: PreviewView
    private lateinit var captureButton: ImageButton
    private lateinit var galleryButton: ImageButton
    private lateinit var switchCameraButton: ImageButton
    private lateinit var cameraExecutor: ExecutorService

    private var imageCapture: ImageCapture? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    // Gallery picker launcher
    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                // Copy to app cache and return
                copyToCache(uri)?.let { cachedUri ->
                    returnResult(cachedUri)
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

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Set up button click listeners
        captureButton.setOnClickListener { takePhoto() }
        galleryButton.setOnClickListener { openGallery() }
        switchCameraButton.setOnClickListener { switchCamera() }

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
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
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
                    returnResult(savedUri)
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

    private fun returnResult(uri: Uri) {
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
}
