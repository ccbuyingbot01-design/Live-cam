package com.example.cctv.ui.components

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.ViewGroup
import android.widget.ImageView
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.cctv.data.CctvDevice
import com.example.ui.theme.LoveAlertRed
import com.example.ui.theme.NeonCyanPrimary
import com.example.ui.theme.NightVisionGreen
import com.example.cctv.viewmodel.CctvViewModel

@Composable
fun CameraPreviewOverlay(
    viewModel: CctvViewModel,
    device: CctvDevice,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingDuration by viewModel.recordingDurationSeconds.collectAsState()
    val pendingAutoRecord by viewModel.pendingAutoRecord.collectAsState()

    // Auto-start recording when camera first opens in Camera Sender mode
    LaunchedEffect(Unit) {
        viewModel.autoStartRecording(context)
    }

    // React to remote viewer commanding a start-recording
    LaunchedEffect(pendingAutoRecord) {
        if (pendingAutoRecord) {
            viewModel.autoStartRecording(context)
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "rec_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("camera_preview_container")
    ) {
        // CameraX Surface View — uses VideoCapture-capable binding
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
            update = { previewView ->
                viewModel.bindCameraWithRecorder(context, lifecycleOwner, previewView)
            },
            modifier = Modifier.fillMaxSize()
        )

        // Green Night Vision Filter Shader
        if (device.nightVision) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    color = NightVisionGreen.copy(alpha = 0.22f)
                )
            }
        }

        // Surveillance Grid Reticle Overlay
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 1.5f)
            val color = if (device.nightVision) NightVisionGreen.copy(alpha = 0.4f) else NeonCyanPrimary.copy(alpha = 0.35f)

            // Corner reticles
            val lineLength = 32.dp.toPx()
            val padding = 24.dp.toPx()

            // Top Left
            drawLine(color, Offset(padding, padding), Offset(padding + lineLength, padding), strokeWidth = 3f)
            drawLine(color, Offset(padding, padding), Offset(padding, padding + lineLength), strokeWidth = 3f)

            // Top Right
            drawLine(color, Offset(size.width - padding, padding), Offset(size.width - padding - lineLength, padding), strokeWidth = 3f)
            drawLine(color, Offset(size.width - padding, padding), Offset(size.width - padding, padding + lineLength), strokeWidth = 3f)

            // Bottom Left
            drawLine(color, Offset(padding, size.height - padding), Offset(padding + lineLength, size.height - padding), strokeWidth = 3f)
            drawLine(color, Offset(padding, size.height - padding), Offset(padding, size.height - padding - lineLength), strokeWidth = 3f)

            // Bottom Right
            drawLine(color, Offset(size.width - padding, size.height - padding), Offset(size.width - padding - lineLength, size.height - padding), strokeWidth = 3f)
            drawLine(color, Offset(size.width - padding, size.height - padding), Offset(size.width - padding, size.height - padding - lineLength), strokeWidth = 3f)
        }

        // Top HUD Status Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .align(Alignment.TopStart),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isRecording) {
                // REC badge with live timer
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(LoveAlertRed.copy(alpha = 0.85f))
                        .border(1.dp, LoveAlertRed, RoundedCornerShape(20.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = pulseAlpha))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    val minutes = recordingDuration / 60
                    val seconds = recordingDuration % 60
                    Text(
                        text = "REC  %02d:%02d".format(minutes, seconds),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            } else {
                // LIVE STREAM badge
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .border(1.dp, LoveAlertRed.copy(alpha = 0.8f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(LoveAlertRed.copy(alpha = pulseAlpha))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "LIVE STREAM",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Active Modes Badges
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (device.torchOn) {
                    Box(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .clip(CircleShape)
                            .background(Color.Yellow.copy(alpha = 0.8f))
                            .padding(6.dp)
                    ) {
                        Icon(Icons.Default.FlashOn, contentDescription = "Torch Active", tint = Color.Black, modifier = Modifier.size(14.dp))
                    }
                }

                if (device.nightVision) {
                    Box(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .clip(CircleShape)
                            .background(NightVisionGreen.copy(alpha = 0.9f))
                            .padding(6.dp)
                    ) {
                        Icon(Icons.Default.Nightlight, contentDescription = "Night Vision", tint = Color.Black, modifier = Modifier.size(14.dp))
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .border(1.dp, NeonCyanPrimary.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "FPS: ${device.fps} | 1080p",
                        color = NeonCyanPrimary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
