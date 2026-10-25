package com.example.cctv.data

import android.graphics.RectF
import androidx.annotation.Keep

enum class DeviceMode {
    CAMERA_SENDER,
    VIEWER_MONITOR
}

enum class MotionSeverity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

enum class AiThreatAssessmentStatus {
    SAFE, LOW_RISK, SUSPICIOUS, THREAT_DETECTED
}

@Keep
data class AiDetectionFeedItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val detail: String,
    val category: String,
    val confidence: Float = 0.90f,
    val timestamp: Long = System.currentTimeMillis(),
    val isGeminiGenerated: Boolean = false
)

@Keep
data class AiChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Keep
data class AiThreatAssessment(
    val score: Int = 10,
    val status: AiThreatAssessmentStatus = AiThreatAssessmentStatus.SAFE,
    val summary: String = "Area clear and secure.",
    val recommendedActions: List<String> = listOf("Monitor Stream", "Night Vision Auto", "Torch Standby")
)

@Keep
data class CctvDevice(
    val id: String,
    val name: String,
    val pairingCode: String,
    val isOnline: Boolean = true,
    val isStreaming: Boolean = true,
    val lensFacingFront: Boolean = false,
    val ipAddress: String = "",
    val batteryLevel: Int = 100,
    val isCharging: Boolean = false,
    val batteryTempCelsius: Float = 28f,
    val torchOn: Boolean = false,
    val sirenActive: Boolean = false,
    val nightVision: Boolean = false,
    val isRecording: Boolean = false,
    val motionSensitivity: Float = 0.5f,
    val motionGuardEnabled: Boolean = false,
    val lastMotionTime: Long = 0L,
    val lastMotionIntensity: Float = 0f,
    val stealthMode: Boolean = false,
    val smartBatterySaverEnabled: Boolean = true,
    val lowPowerThreshold: Int = 20,
    val isLowPowerMode: Boolean = false,
    val fps: Int = 30,
    val notificationVibration: Boolean = true,
    val notificationSound: Boolean = true
)

@Keep
data class DetectedHuman(
    val id: String = java.util.UUID.randomUUID().toString(),
    val boundingBox: RectF,
    val confidence: Float = 0.9f
)

@Keep
data class MotionPoint(
    val xRatio: Float = 0.5f,
    val yRatio: Float = 0.5f,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val intensity: Float = 0.5f,
    val timestamp: Long = System.currentTimeMillis()
)

@Keep
data class MotionEvent(
    val id: String = java.util.UUID.randomUUID().toString(),
    val deviceId: String = "CAM-1",
    val deviceName: String = "Love Cam #1",
    val timestamp: Long = System.currentTimeMillis(),
    val description: String = "Motion Detected",
    val intensity: Float = 0.5f,
    val severity: MotionSeverity = MotionSeverity.MEDIUM,
    val snapshotBase64: String? = null,
    val imageUrl: String? = null,
    val xRatio: Float = 0.5f,
    val yRatio: Float = 0.5f
)
