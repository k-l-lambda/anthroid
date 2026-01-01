package com.anthroid.claude

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Utility class for image processing - resize, compress, and base64 encode
 * images for sending to Claude API.
 */
object ImageUtils {
    private const val TAG = "ImageUtils"
    private const val MAX_IMAGE_DIMENSION = 1024  // Max width/height in pixels
    private const val JPEG_QUALITY = 85

    /**
     * Process an image for API submission.
     * Resizes if necessary and compresses as JPEG.
     * Returns base64-encoded string.
     */
    fun processImageForApi(context: Context, uri: Uri): String? {
        return try {
            // First, get dimensions without loading full image
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            // Calculate sample size for initial downsampling
            val sampleSize = calculateSampleSize(options.outWidth, options.outHeight)

            // Decode with sample size
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return null

            // Further resize if still too large
            val resized = resizeIfNeeded(bitmap)

            // Compress to JPEG
            val outputStream = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)

            // Recycle bitmaps if they're different objects
            if (resized != bitmap) {
                bitmap.recycle()
            }

            // Base64 encode
            val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

            Log.d(TAG, "Processed image: ${options.outWidth}x${options.outHeight} -> ${resized.width}x${resized.height}, base64 size: ${base64.length}")

            base64
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process image", e)
            null
        }
    }

    /**
     * Calculate inSampleSize for BitmapFactory to efficiently load large images.
     */
    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        while (width / sampleSize > MAX_IMAGE_DIMENSION * 2 ||
               height / sampleSize > MAX_IMAGE_DIMENSION * 2) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * Resize bitmap if it exceeds MAX_IMAGE_DIMENSION.
     */
    private fun resizeIfNeeded(bitmap: Bitmap): Bitmap {
        val maxDim = maxOf(bitmap.width, bitmap.height)
        if (maxDim <= MAX_IMAGE_DIMENSION) return bitmap

        val scale = MAX_IMAGE_DIMENSION.toFloat() / maxDim
        val newWidth = (bitmap.width * scale).toInt()
        val newHeight = (bitmap.height * scale).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Get MIME type from URI.
     */
    fun getMimeType(context: Context, uri: Uri): String {
        return context.contentResolver.getType(uri) ?: "image/jpeg"
    }

    /**
     * Get file path from URI if it's a file URI.
     */
    fun getFilePath(uri: Uri): String? {
        return if (uri.scheme == "file") {
            uri.path
        } else {
            null
        }
    }

    /**
     * Copy content URI to a file in cache directory.
     * Returns the file path or null on failure.
     */
    fun copyToCache(context: Context, uri: Uri, prefix: String = "image"): File? {
        return try {
            val timestamp = System.currentTimeMillis()
            val cacheDir = File(context.cacheDir, "images")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            val outputFile = File(cacheDir, "${prefix}_${timestamp}.jpg")

            context.contentResolver.openInputStream(uri)?.use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy to cache", e)
            null
        }
    }
}
