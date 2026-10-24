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
 * Safe memory handling with zero premature bitmap recycling to prevent native crashes.
 */
class FrameCaptureAnalyzer(
    private val onFrameCaptured: (String) -> Unit,
    private val onCompressedBytesCaptured: ((ByteArray) -> Unit)? = null,
    private val onRawNv21Captured: ((ByteArray, Int, Int) -> Unit)? = null,
    private val onBitmapCaptured: ((Bitmap) -> Unit)? = null,
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

    private val objectDetectorOptions by lazy {
        try {
            com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions.Builder()
                .setDetectorMode(com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions.STREAM_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create ObjectDetectorOptions", e)
            null
        }
    }

    private val objectDetector by lazy {
        try {
            objectDetectorOptions?.let {
                com.google.mlkit.vision.objects.ObjectDetection.getClient(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ML Kit ObjectDetector", e)
            null
        }
    }

    fun updatePerformanceProfile(targetFps: Int, targetQuality: Int, targetMaxDim: Int) {
        intervalMs = (1000L / targetFps).coerceAtLeast(33L)
        jpegQuality = targetQuality.coerceIn(10, 80)
        maxDimension = targetMaxDim
    }

    private var lastBase64Time = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastCaptureTime < intervalMs) {
                image.close()
                return
            }
            lastCaptureTime = now

            val sourceBitmap = imageProxyToBitmap(image) ?: run {
                image.close()
                return
            }

            val rotationDegrees = image.imageInfo.rotationDegrees
            val bitmap = if (rotationDegrees != 0) {
                try {
                    val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                    Bitmap.createBitmap(sourceBitmap, 0, 0, sourceBitmap.width, sourceBitmap.height, matrix, true)
                } catch (e: Exception) {
                    sourceBitmap
                }
            } else {
                sourceBitmap
            }

            // 1. Deliver bitmap directly to LiveKit / renderer with zero delay
            try {
                onBitmapCaptured?.invoke(bitmap)
            } catch (e: Exception) {
                Log.w(TAG, "onBitmapCaptured callback error", e)
            }

            // 2. Local UDP Stream JPEG Bytes (only if listener attached)
            if (onCompressedBytesCaptured != null) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
                val jpegBytes = out.toByteArray()
                try {
                    onCompressedBytesCaptured.invoke(jpegBytes)
                } catch (e: Exception) {
                    Log.w(TAG, "onCompressedBytesCaptured callback error", e)
                }
            }

            // 3. Supabase Cloud Base64 Fallback Frame (throttled to 1 FPS max to prevent GC allocation lag)
            if (now - lastBase64Time >= 1000L) {
                lastBase64Time = now
                val scaled = scaleBitmap(bitmap, maxDimension)
                val base64 = bitmapToBase64(scaled, jpegQuality)
                try {
                    onFrameCaptured(base64)
                } catch (e: Exception) {
                    Log.w(TAG, "onFrameCaptured callback error", e)
                }
            }

            // 4. AI Human Detection (runs on background thread, max 2.5 FPS)
            val detector = objectDetector
            if (onHumanDetected != null && detector != null && now - lastHumanDetectTime > 400L) {
                lastHumanDetectTime = now
                val scaledForAi = scaleBitmap(bitmap, 320)
                try {
                    val inputImg = com.google.mlkit.vision.common.InputImage.fromBitmap(scaledForAi, 0)
                    detector.process(inputImg)
                        .addOnSuccessListener { detectedObjects ->
                            try {
                                val results = mutableListOf<HumanDetectorAnalyzer.HumanDetectionResult>()
                                val imgWidth = scaledForAi.width.toFloat()
                                val imgHeight = scaledForAi.height.toFloat()

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
                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing detected objects", e)
                            }
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "ML Kit detection error", e)
                        }
                } catch (e: Exception) {
                    Log.e(TAG, "InputImage creation error", e)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Frame capture error", e)
        } finally {
            try {
                image.close()
            } catch (_: Exception) {}
        }
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        return try {
            image.toBitmap()
        } catch (e: Exception) {
            try {
                if (image.format == android.graphics.ImageFormat.YUV_420_888) {
                    val nv21 = yuv420ToNv21(image)
                    val yuvImage = android.graphics.YuvImage(
                        nv21,
                        android.graphics.ImageFormat.NV21,
                        image.width,
                        image.height,
                        null
                    )
                    val out = ByteArrayOutputStream()
                    yuvImage.compressToJpeg(android.graphics.Rect(0, 0, image.width, image.height), 80, out)
                    val imageBytes = out.toByteArray()
                    BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                } else {
                    null
                }
            } catch (ex: Exception) {
                Log.e(TAG, "YUV fallback conversion error: ${ex.message}")
                null
            }
        }
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + image.width * image.height / 2)

        yBuffer.get(nv21, 0, ySize)

        val vRowStride = image.planes[2].rowStride
        val vPixelStride = image.planes[2].pixelStride
        val uRowStride = image.planes[1].rowStride
        val uPixelStride = image.planes[1].pixelStride

        val width = image.width
        val height = image.height

        var nvIndex = ySize
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val vIndex = row * vRowStride + col * vPixelStride
                val uIndex = row * uRowStride + col * uPixelStride
                if (vIndex < vSize) {
                    nv21[nvIndex++] = vBuffer.get(vIndex)
                }
                if (uIndex < uSize) {
                    nv21[nvIndex++] = uBuffer.get(uIndex)
                }
            }
        }
        return nv21
    }

    private fun scaleBitmap(bitmap: Bitmap, maxDim: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDim && height <= maxDim) return bitmap

        val ratio = minOf(maxDim.toFloat() / width, maxDim.toFloat() / height)
        val newWidth = (width * ratio).toInt().coerceAtLeast(1)
        val newHeight = (height * ratio).toInt().coerceAtLeast(1)
        return try {
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } catch (e: Exception) {
            bitmap
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap, quality: Int): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }
}
