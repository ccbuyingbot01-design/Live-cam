package com.example.cctv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cctv.data.CctvDevice
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.LoveAlertRed
import com.example.ui.theme.NeonCyanPrimary
import com.example.ui.theme.NightVisionGreen
import com.example.ui.theme.TextSecondary

@Composable
fun RemoteControlPanel(
    device: CctvDevice,
    onToggleTorch: () -> Unit,
    onToggleSiren: () -> Unit,
    onToggleLens: () -> Unit,
    onToggleNightVision: () -> Unit,
    onToggleRecording: () -> Unit,
    onToggleIntercom: (() -> Unit)? = null,
    isIntercomActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSurface)
            .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
            .testTag("remote_control_panel")
    ) {
        Text(
            text = "REMOTE CAMERA CONTROLS",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Row 1: Core controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Flash Toggle
            ControlButton(
                icon = Icons.Default.FlashOn,
                label = "Torch Light",
                isActive = device.torchOn,
                activeColor = Color.Yellow,
                onClick = onToggleTorch,
                testTag = "remote_torch_btn"
            )

            // Siren Trigger
            ControlButton(
                icon = Icons.Default.VolumeUp,
                label = "Siren Alarm",
                isActive = device.sirenActive,
                activeColor = LoveAlertRed,
                onClick = onToggleSiren,
                testTag = "remote_siren_btn"
            )

            // Lens Switch
            ControlButton(
                icon = Icons.Default.Cameraswitch,
                label = if (device.lensFacingFront) "Front Lens" else "Back Lens",
                isActive = device.lensFacingFront,
                activeColor = NeonCyanPrimary,
                onClick = onToggleLens,
                testTag = "remote_lens_btn"
            )

            // Night Vision Toggle
            ControlButton(
                icon = Icons.Default.Nightlight,
                label = "Night Vision",
                isActive = device.nightVision,
                activeColor = NightVisionGreen,
                onClick = onToggleNightVision,
                testTag = "remote_night_btn"
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Row 2: 2-Way Intercom & Record buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            onToggleIntercom?.let {
                Box(modifier = Modifier.weight(1f)) {
                    ControlButton(
                        icon = if (isIntercomActive) Icons.Default.Mic else Icons.Default.MicOff,
                        label = if (isIntercomActive) "Intercom ON" else "Talk Back",
                        isActive = isIntercomActive,
                        activeColor = NeonCyanPrimary,
                        onClick = it,
                        testTag = "remote_intercom_btn"
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                ControlButton(
                    icon = if (device.isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                    label = if (device.isRecording) "Stop Rec" else "Start Rec",
                    isActive = device.isRecording,
                    activeColor = LoveAlertRed,
                    onClick = onToggleRecording,
                    testTag = "remote_record_btn"
                )
            }
        }
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    testTag: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }.testTag(testTag)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(if (isActive) activeColor.copy(alpha = 0.25f) else Color(0xFF1E293B))
                .border(
                    width = 1.5.dp,
                    color = if (isActive) activeColor else DarkBorder,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive) activeColor else TextSecondary,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = label,
            color = if (isActive) activeColor else TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
