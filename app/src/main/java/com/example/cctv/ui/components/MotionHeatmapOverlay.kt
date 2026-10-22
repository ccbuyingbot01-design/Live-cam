package com.example.cctv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.cctv.data.MotionPoint

/**
 * MotionHeatmapOverlay
 *
 * Renders a daily activity density heatmap over the camera feed.
 * Highlights high-traffic zones using multi-color radial gradient hot-spots.
 */
@Composable
fun MotionHeatmapOverlay(
    motionPoints: List<MotionPoint>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    if (!enabled || motionPoints.isEmpty()) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        for (point in motionPoints) {
            val centerX = (point.x.coerceIn(0.05f, 0.95f)) * width
            val centerY = (point.y.coerceIn(0.05f, 0.95f)) * height
            val radius = (48.dp.toPx() * (0.8f + point.intensity * 0.6f)).coerceIn(30f, 180f)

            // Multi-stop heat gradient (Red hot core -> Orange -> Green soft edge)
            val heatGradient = Brush.radialGradient(
                colors = listOf(
                    Color(0xFFFF1744).copy(alpha = (0.55f * point.intensity).coerceIn(0.2f, 0.8f)), // Hot Red Core
                    Color(0xFFFF9100).copy(alpha = (0.38f * point.intensity).coerceIn(0.15f, 0.6f)), // Warm Orange
                    Color(0xFFFFEA00).copy(alpha = (0.22f * point.intensity).coerceIn(0.10f, 0.4f)), // Yellow Glow
                    Color(0xFF00E676).copy(alpha = 0.05f), // Soft Green Boundary
                    Color.Transparent
                ),
                center = Offset(centerX, centerY),
                radius = radius
            )

            drawCircle(
                brush = heatGradient,
                radius = radius,
                center = Offset(centerX, centerY)
            )
        }
    }
}
