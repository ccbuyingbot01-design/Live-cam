// HumanDetectionAnalyzer.kt
package com.example.cctv.camera

import androidx.camera.mlkit.vision.MlKitAnalyzer
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetector
import androidx.camera.core.ImageAnalysis
import androidx.core.util.Consumer

// In CameraXManager.kt or wherever analyzer is initialized:
// val options = ObjectDetectorOptions.Builder()
//     .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
//     .enableClassification() 
//     .build()
// val detector = ObjectDetection.getClient(options)
// val mlKitAnalyzer = MlKitAnalyzer(
//     listOf(detector),
//     COORDINATE_SYSTEM_VIEW_REFERENCED,
//     ContextCompat.getMainExecutor(context)
// ) { result: MlKitAnalyzer.Result? ->
//     val detectedObjects = result?.getResult(detector)
//     // Update ViewModel/UI with detectedObjects for green boxes
// }

// In UI (ViewerModeScreen.kt), add an Overlay component:
// Canvas(modifier = Modifier.fillMaxSize()) {
//     detectedObjects.forEach { obj ->
//         drawRect(
//             color = Color.Green,
//             topLeft = Offset(obj.boundingBox.left.toFloat(), obj.boundingBox.top.toFloat()),
//             size = Size(obj.boundingBox.width().toFloat(), obj.boundingBox.height().toFloat()),
//             style = Stroke(width = 4.dp.toPx())
//         )
//     }
// }
