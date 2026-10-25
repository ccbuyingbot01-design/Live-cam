package com.example.cctv.data

enum class MotionSeverity { LOW, MEDIUM, HIGH }

data class MotionPoint(
    val x: Float,
    val y: Float,
    val intensity: Float,
    val timestamp: Long
)

data class MotionEvent(
    val id: String,
    val timestamp: Long,
    val severity: MotionSeverity,
    val description: String,
    val snapshotBase64: String?,
    val deviceName: String
)
