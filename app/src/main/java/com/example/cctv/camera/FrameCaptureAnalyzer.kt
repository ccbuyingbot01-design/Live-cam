package com.example.cctv.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * FrameCaptureAnalyzer
 *
 * Periodically captures camera frames, converts them to small JPEG images,
 * and delivers them via P2P UDP transport and Supabase cloud fallback.
 */
class FrameCaptureAnalyzer(
    private val onFrameCaptured: (String) -> Unit,
    private val onCompressedBytesCaptured: ((ByteArray) -> Unit)? = null,
    private val onRawNv21Captured: ((ByteArray, Int, Int) -> Unit)? = null,
    private val onHumanDetected: ((List<HumanDetectorAnalyzer.HumanDetectionResult>) -> Unit)? = null,
    private var intervalMs: Long = 100L,
    private var jpegQuality: Int = 30,
    private var maxDimension: Int = 320
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "FrameCaptureAnalyzer"
    }

    private var lastCaptureTime = 0L
    private var lastHumanDetectTime = 0L

    private val objectDetectorOptions = com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions.Builder()
        .setDetectorMode(com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions.STREAM_MODE)
        .enableMultipleObjects()
        .enableClassification()
        .build()

    private val objectDetector = com.google.mlkit.vision.objects.ObjectDetection.getClient(objectDetectorOptions)

    fun updatePerformanceProfile(targetFps: Int, targetQuality: Int, targetMaxDim: Int) {
        intervalMs = (1000L / targetFps).coerceAtLeast(33L)
        jpegQuality = targetQuality.coerceIn(10, 80)
        maxDimension = targetMaxDim
    }

    override fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastCaptureTime < intervalMs) {
                image.close()
                return
            }
            lastCaptureTime = now

            val sourceBitmap = image.toBitmap()

            val rotationDegrees = image.imageInfo.rotationDegrees
            val bitmap = if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                Bitmap.createBitmap(sourceBitmap, 0, 0, sourceBitmap.width, sourceBitmap.height, matrix, true)
            } else {
                sourceBitmap
            }

            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
            val jpegBytes = out.toByteArray()

            onCompressedBytesCaptured?.invoke(jpegBytes)

            val scaled = scaleBitmap(bitmap, maxDimension)
            val base64 = bitmapToBase64(scaled, jpegQuality)
            onFrameCaptured(base64)

            // AI Human Detection Processing
            if (onHumanDetected != null && now - lastHumanDetectTime > 200L) {
                lastHumanDetectTime = now
                val inputImg = com.google.mlkit.vision.common.InputImage.fromBitmap(scaled, 0)
                objectDetector.process(inputImg)
                    .addOnSuccessListener { detectedObjects ->
                        val results = mutableListOf<HumanDetectorAnalyzer.HumanDetectionResult>()
                        val imgWidth = scaled.width.toFloat()
                        val imgHeight = scaled.height.toFloat()

                        for (obj in detectedObjects) {
                            val box = obj.boundingBox
                            val isPerson = obj.labels.any { l ->
                                l.text.equals("Person", ignoreCase = true) || l.text.equals("Human", ignoreCase = true)
                            } || obj.labels.isEmpty()

                            if (isPerson && imgWidth > 0 && imgHeight > 0) {
                                val normalizedBox = android.graphics.RectF(
                                    (box.left.toFloat() / imgWidth).coerceIn(0f, 1f),
                                    (box.top.toFloat() / imgHeight).coerceIn(0f, 1f),
                                    (box.right.toFloat() / imgWidth).coerceIn(0f, 1f),
                                    (box.bottom.toFloat() / imgHeight).coerceIn(0f, 1f)
                                )
                                val conf = obj.labels.maxByOrNull { it.confidence }?.confidence ?: 0.88f
                                results.add(
                                    HumanDetectorAnalyzer.HumanDetectionResult(
                                        boundingBox = normalizedBox,
                                        confidence = conf,
                                        label = "HUMAN ${(conf * 100).toInt()}%"
                                    )
                                )
                            }
                        }
                        onHumanDetected.invoke(results)
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "ML Kit detection error", e)
                    }
            }

            if (scaled !== bitmap && scaled !== sourceBitmap) scaled.recycle()
            if (bitmap !== sourceBitmap) bitmap.recycle()
            sourceBitmap.recycle()

        } catch (e: Exception) {
            Log.e(TAG, "Frame capture error", e)
        } finally {
            image.close()
        }
    }

    private fun scaleBitmap(bitmap: Bitmap, maxDim: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDim && height <= maxDim) return bitmap

        val ratio = minOf(maxDim.toFloat() / width, maxDim.toFloat() / height)
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun bitmapToBase64(bitmap: Bitmap, quality: Int): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }
}
