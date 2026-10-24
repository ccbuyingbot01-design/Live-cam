package com.example.cctv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.NeonCyanPrimary
import com.example.ui.theme.SecurityGreen

@Composable
fun NetworkStatusIndicator(
    isWifi: Boolean,
    networkName: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.82f))
            .border(
                1.dp,
                if (isWifi) SecurityGreen.copy(alpha = 0.8f) else NeonCyanPrimary.copy(alpha = 0.8f),
                RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = if (isWifi) Icons.Default.Wifi else Icons.Default.SignalCellularAlt,
                contentDescription = if (isWifi) "Wi-Fi Connected" else "Cellular Connected",
                tint = if (isWifi) SecurityGreen else NeonCyanPrimary,
                modifier = Modifier.size(14.dp)
            )

            Text(
                text = if (isWifi) {
                    "📶 WI-FI | 720p HD @ 30 FPS (High Bandwidth / Local P2P + LiveKit WebRTC)"
                } else {
                    "📡 MOBILE DATA | 480p SD @ 30 FPS (LiveKit Cloud WebRTC Mobile CGNAT Optimized)"
                },
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
