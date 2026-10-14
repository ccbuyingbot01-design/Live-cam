package com.example.cctv.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

class MotionDetector(
    private var sensitivity: Float = 0.4f,
    private val onMotionDetected: (intensity: Float, snapshot: Bitmap?) -> Unit
) : ImageAnalysis.Analyzer {

    private var previousFrameY: ByteArray? = null
    private var lastTriggerTime = 0L
    private val triggerCooldownMs = 2500L // 2.5 seconds cooldown between event logs

    fun updateSensitivity(newSensitivity: Float) {
        sensitivity = newSensitivity.coerceIn(0.1f, 1.0f)
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (image.format != ImageFormat.YUV_420_888) {
                image.close()
                return
            }

            val yBuffer = image.planes[0].buffer
            val ySize = yBuffer.remaining()
            val currentFrameY = ByteArray(ySize)
            yBuffer.get(currentFrameY)

            val prev = previousFrameY
            if (prev != null && prev.size == currentFrameY.size) {
                // Downsample pixel comparison for high performance
                val step = 16
                var changedPixels = 0
                var totalSampled = 0
                val pixelThreshold = (30 * (1.1f - sensitivity)).toInt().coerceAtLeast(8)

                var i = 0
                while (i < currentFrameY.size) {
                    val currVal = currentFrameY[i].toInt() and 0xFF
                    val prevVal = prev[i].toInt() and 0xFF
                    if (Math.abs(currVal - prevVal) > pixelThreshold) {
                        changedPixels++
                    }
                    totalSampled++
                    i += step
                }

                val motionRatio = if (totalSampled > 0) changedPixels.toFloat() / totalSampled else 0f

                // Trigger motion threshold scaled by sensitivity
                val triggerThreshold = 0.05f * (1.2f - sensitivity)

                if (motionRatio > triggerThreshold) {
                    val now = System.currentTimeMillis()
                    if (now - lastTriggerTime > triggerCooldownMs) {
                        lastTriggerTime = now
                        val snapshotBitmap = convertYuvToBitmap(image)
                        val intensity = (motionRatio * 5f).coerceIn(0.1f, 1.0f)
                        onMotionDetected(intensity, snapshotBitmap)
                    }
                }
            }

            previousFrameY = currentFrameY
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            image.close()
        }
    }

    private fun convertYuvToBitmap(image: ImageProxy): Bitmap? {
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
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 70, out)
            val imageBytes = out.toByteArray()
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        } catch (e: Exception) {
            null
        }
    }
}
