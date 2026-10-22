package com.example.cctv.camera

import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions

/**
 * HumanDetectorAnalyzer
 *
 * Real-time AI Human & Motion Spotting analyzer powered by Google ML Kit.
 * Processes camera frames, identifies human entities, and produces
 * normalized bounding boxes [0.0..1.0] for overlay rendering.
 */
class HumanDetectorAnalyzer(
    private val onHumansDetected: (List<HumanDetectionResult>) -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "HumanDetectorAnalyzer"
    }

    data class HumanDetectionResult(
        val boundingBox: RectF, // Normalized coordinates [0.0f..1.0f]
        val confidence: Float,
        val label: String = "HUMAN"
    )

    private val options = ObjectDetectorOptions.Builder()
        .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
        .enableMultipleObjects()
        .enableClassification()
        .build()

    private val detector = ObjectDetection.getClient(options)
    private var lastProcessTime = 0L
    private val processIntervalMs = 150L // Process ~6.6 FPS to conserve battery

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastProcessTime < processIntervalMs) {
            imageProxy.close()
            return
        }
        lastProcessTime = now

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val image = InputImage.fromMediaImage(mediaImage, rotationDegrees)

        detector.process(image)
            .addOnSuccessListener { detectedObjects ->
                val imageWidth = if (rotationDegrees == 90 || rotationDegrees == 270) imageProxy.height else imageProxy.width
                val imageHeight = if (rotationDegrees == 90 || rotationDegrees == 270) imageProxy.width else imageProxy.height

                val humanResults = mutableListOf<HumanDetectionResult>()

                for (obj in detectedObjects) {
                    val box = obj.boundingBox
                    val isPersonCategory = obj.labels.any { label ->
                        label.text.equals("Person", ignoreCase = true) ||
                                label.text.equals("Human", ignoreCase = true)
                    } || obj.labels.isEmpty() // Default detection in stream mode

                    if (isPersonCategory && imageWidth > 0 && imageHeight > 0) {
                        // Normalize bounding box to [0.0f .. 1.0f] relative scale
                        val normalizedBox = RectF(
                            (box.left.toFloat() / imageWidth).coerceIn(0f, 1f),
                            (box.top.toFloat() / imageHeight).coerceIn(0f, 1f),
                            (box.right.toFloat() / imageWidth).coerceIn(0f, 1f),
                            (box.bottom.toFloat() / imageHeight).coerceIn(0f, 1f)
                        )
                        val topConfidence = obj.labels.maxByOrNull { it.confidence }?.confidence ?: 0.85f

                        humanResults.add(
                            HumanDetectionResult(
                                boundingBox = normalizedBox,
                                confidence = topConfidence,
                                label = "HUMAN ${(topConfidence * 100).toInt()}%"
                            )
                        )
                    }
                }

                onHumansDetected(humanResults)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Human detection error", e)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }
}
