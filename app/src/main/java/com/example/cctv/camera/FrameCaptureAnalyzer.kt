package com.example.cctv.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Base64
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * FrameCaptureAnalyzer
 *
 * Periodically captures a camera frame, converts it to a small JPEG,
 * encodes as base64, and delivers it via [onFrameCaptured].
 * Used for cloud live streaming through Supabase.
 *
 * Default interval: 1500 ms (~0.7 FPS) — keeps bandwidth reasonable
 * while providing near-real-time CCTV surveillance.
 */
class FrameCaptureAnalyzer(
    private val onFrameCaptured: (String) -> Unit,
    private val intervalMs: Long = 1500L,
    private val jpegQuality: Int = 35
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "FrameCaptureAnalyzer"
        // Scale down to this max dimension for bandwidth savings
        private const val MAX_DIMENSION = 320
    }

    private var lastCaptureTime = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastCaptureTime < intervalMs) {
                image.close()
                return
            }
            lastCaptureTime = now

            if (image.format != ImageFormat.YUV_420_888) {
                image.close()
                return
            }

            val bitmap = yuvToBitmap(image)
            if (bitmap != null) {
                val scaled = scaleBitmap(bitmap)
                val base64 = bitmapToBase64(scaled)
                onFrameCaptured(base64)
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame capture error", e)
        } finally {
            image.close()
        }
    }

    private fun yuvToBitmap(image: ImageProxy): Bitmap? {
        return try {
            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), jpegQuality, out)
            val imageBytes = out.toByteArray()
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "YUV to bitmap error", e)
            null
        }
    }

    private fun scaleBitmap(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= MAX_DIMENSION && height <= MAX_DIMENSION) return bitmap

        val ratio = minOf(MAX_DIMENSION.toFloat() / width, MAX_DIMENSION.toFloat() / height)
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }
}
