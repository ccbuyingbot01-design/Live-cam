package com.example.cctv.data

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

@Keep
data class CctvDevice(
    val id: String,
    val name: String,
    val pairingCode: String,
    val isOnline: Boolean = true,
    val isStreaming: Boolean = true,
    val torchOn: Boolean = false,
    val lensFacingFront: Boolean = false,
    val motionGuardEnabled: Boolean = true,
    val motionSensitivity: Float = 0.4f,
    val lastMotionTime: Long = System.currentTimeMillis(),
    val lastMotionIntensity: Float = 0f,
    val batteryLevel: Int = 85,
    val isCharging: Boolean = true,
    val stealthMode: Boolean = false,
    val sirenActive: Boolean = false,
    val nightVision: Boolean = false,
    val ipAddress: String = "192.168.1.105",
    val fps: Int = 30,
    // Remote-controllable recording state (set by viewer, obeyed by camera)
    val isRecording: Boolean = false,
    val notificationVibration: Boolean = true,
    val notificationSound: Boolean = true,
    val isLowPowerMode: Boolean = false,
    val smartBatterySaverEnabled: Boolean = true,
    val lowPowerThreshold: Int = 40,
    val batteryTempCelsius: Float = 28f
)

@Keep
data class MotionPoint(
    val x: Float, // Normalized 0.0f..1.0f
    val y: Float, // Normalized 0.0f..1.0f
    val intensity: Float,
    val timestamp: Long = System.currentTimeMillis()
)

@Keep
data class MotionEvent(
    val id: String,
    val deviceId: String,
    val deviceName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val intensity: Float,
    val severity: MotionSeverity,
    val snapshotBase64: String? = null,
    val description: String,
    val xRatio: Float = 0.5f,
    val yRatio: Float = 0.5f
)
