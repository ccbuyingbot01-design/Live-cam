package com.example.cctv.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * MotionDetector
 *
 * High-sensitivity YUV_420_888 image analyzer.
 * Calculates frame-by-frame luminance delta, motion center-of-mass (X/Y ratios),
 * and dynamic motion intensity gain so the visual sensor gauge responds smoothly.
 */
class MotionDetector(
    private var sensitivity: Float = 0.5f,
    private val onMotionDetected: (intensity: Float, snapshot: Bitmap?, xRatio: Float, yRatio: Float) -> Unit
) : ImageAnalysis.Analyzer {

    private var previousFrameY: ByteArray? = null
    private var lastSnapshotTime = 0L
    private val snapshotCooldownMs = 2000L // 2 seconds between image snapshot captures

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
                // High-density sampling (step = 4 for high sensitivity)
                val step = 4
                var changedPixels = 0
                var totalSampled = 0
                var sumX = 0L
                var sumY = 0L
                val width = image.width
                val height = image.height

                // Adaptive luminance pixel threshold based on sensitivity
                val pixelThreshold = (22 * (1.1f - sensitivity)).toInt().coerceAtLeast(6)

                var i = 0
                while (i < currentFrameY.size) {
                    val currVal = currentFrameY[i].toInt() and 0xFF
                    val prevVal = prev[i].toInt() and 0xFF
                    val diff = Math.abs(currVal - prevVal)

                    if (diff > pixelThreshold) {
                        changedPixels++
                        if (width > 0) {
                            val pixelIndex = i
                            sumX += (pixelIndex % width)
                            sumY += (pixelIndex / width)
                        }
                    }
                    totalSampled++
                    i += step
                }

                val motionRatio = if (totalSampled > 0) changedPixels.toFloat() / totalSampled else 0f

                // Dynamic gain multiplier: ensures motion level bar grows smoothly and visibly
                val rawIntensity = (motionRatio * 18f).coerceIn(0.0f, 1.0f)

                // Minimum threshold to trigger motion event
                val triggerThreshold = 0.02f * (1.2f - sensitivity)

                if (motionRatio > triggerThreshold) {
                    val now = System.currentTimeMillis()
                    val centerX = if (changedPixels > 0 && width > 0) (sumX.toFloat() / changedPixels) / width else 0.5f
                    val centerY = if (changedPixels > 0 && height > 0) (sumY.toFloat() / changedPixels) / height else 0.5f

                    val snapshotBitmap = if (now - lastSnapshotTime > snapshotCooldownMs) {
                        lastSnapshotTime = now
                        convertYuvToBitmap(image)
                    } else null

                    onMotionDetected(rawIntensity.coerceAtLeast(0.15f), snapshotBitmap, centerX, centerY)
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
            e.printStackTrace()
            null
        }
    }
}
