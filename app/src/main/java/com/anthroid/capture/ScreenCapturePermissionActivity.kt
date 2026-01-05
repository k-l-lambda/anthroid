package com.anthroid.capture

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Activity to request MediaProjection permission and start ScreenCaptureService.
 * Launch this activity to enable screenshot and audio capture capabilities.
 */
class ScreenCapturePermissionActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ScreenCapturePermission"

        /**
         * Launch this activity to request screen capture permission.
         */
        fun launch(context: Context) {
            val intent = Intent(context, ScreenCapturePermissionActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            Log.i(TAG, "MediaProjection permission granted")

            // Store the result and start the service
            ScreenCaptureService.setProjectionResult(result.resultCode, result.data)

            val started = ScreenCaptureService.start(this)
            if (started) {
                Toast.makeText(this, "Screen capture enabled", Toast.LENGTH_SHORT).show()
                Log.i(TAG, "ScreenCaptureService started successfully")
            } else {
                Toast.makeText(this, "Failed to start screen capture service", Toast.LENGTH_SHORT).show()
                Log.e(TAG, "Failed to start ScreenCaptureService")
            }
        } else {
            Log.w(TAG, "MediaProjection permission denied")
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }

        // Close this activity
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if service is already running
        if (ScreenCaptureService.isRunning()) {
            Toast.makeText(this, "Screen capture already enabled", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Request MediaProjection permission
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = projectionManager.createScreenCaptureIntent()

        Log.i(TAG, "Requesting MediaProjection permission")
        mediaProjectionLauncher.launch(captureIntent)
    }
}
