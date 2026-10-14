package com.example.cctv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.LoveAlertRed
import com.example.ui.theme.NeonCyanPrimary
import com.example.ui.theme.SecurityGreen
import com.example.ui.theme.SecurityYellow

@Composable
fun MotionMeterBar(
    intensity: Float, // 0.0f to 1.0f
    isGuardActive: Boolean,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = intensity.coerceIn(0f, 1f),
        label = "motion_intensity_progress"
    )

    val barColor = when {
        animatedProgress > 0.6f -> LoveAlertRed
        animatedProgress > 0.3f -> SecurityYellow
        else -> SecurityGreen
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .border(1.dp, DarkSurfaceVariant, RoundedCornerShape(12.dp))
            .padding(12.dp)
            .testTag("motion_meter_bar")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (isGuardActive) "MOTION GUARD ACTIVE" else "MOTION GUARD PAUSED",
                color = if (isGuardActive) NeonCyanPrimary else Color.Gray,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${(animatedProgress * 100).toInt()}% ACTIVITY",
                color = barColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Progress Bar Track
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF1E293B))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedProgress.coerceAtLeast(0.02f))
                    .clip(RoundedCornerShape(4.dp))
                    .background(barColor)
            )
        }
    }
}
