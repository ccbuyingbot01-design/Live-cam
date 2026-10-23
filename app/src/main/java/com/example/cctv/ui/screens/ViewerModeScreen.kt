package com.example.cctv.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.cctv.data.CctvDevice
import com.example.cctv.ui.components.AiCopilotChatDialog
import com.example.cctv.ui.components.MotionHeatmapOverlay
import com.example.cctv.ui.components.RemoteControlPanel
import com.example.cctv.viewmodel.CctvViewModel
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.LoveAlertRed
import com.example.ui.theme.NeonCyanPrimary
import com.example.ui.theme.NightVisionGreen
import com.example.ui.theme.SecurityGreen
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ViewerModeScreen(
    viewModel: CctvViewModel,
    onNavigateToLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pairedDevices by viewModel.pairedDevices.collectAsState()
    val selectedCameraId by viewModel.selectedCameraId.collectAsState()
    val recentAlert by viewModel.recentMotionAlert.collectAsState()

    val activeCamera = pairedDevices.find { it.id == selectedCameraId } ?: pairedDevices.firstOrNull()
    var pairingCodeInput by remember { mutableStateOf("") }
    var showPairInput by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }

    val liveStreamBitmap by viewModel.liveStreamBitmap.collectAsState()
    val liveKitVideoTrack by viewModel.remoteLiveKitTrack.collectAsState()
    val detectedHumans by viewModel.detectedHumans.collectAsState()
    val dailyMotionPoints by viewModel.dailyMotionPoints.collectAsState()
    val motionEvents by viewModel.motionEvents.collectAsState()
    val aiDetectionFeeds by viewModel.aiDetectionFeeds.collectAsState()
    val aiThreatAssessment by viewModel.aiThreatAssessment.collectAsState()
    val aiChatMessages by viewModel.aiChatMessages.collectAsState()
    val isCopilotThinking by viewModel.isCopilotThinking.collectAsState()
    val isAnalyzingGemini by viewModel.isAnalyzingGemini.collectAsState()
    val liveKitReconnecting by viewModel.liveKitReconnecting.collectAsState()
    val liveKitHealthStatus by viewModel.liveKitHealthStatus.collectAsState()
    val liveKitReconnectAttempt by viewModel.liveKitReconnectAttempt.collectAsState()

    var showHeatmap by remember { mutableStateOf(true) }
    var isTimelineOpen by remember { mutableStateOf(false) }
    var isFullScreen by remember { mutableStateOf(false) }
    var showAiCopilotDialog by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()
    val timeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val supabaseUser by viewModel.supabaseUser.collectAsState()

    LaunchedEffect(supabaseUser?.username) {
        viewModel.startLiveKitViewer(viewModel.getSanitizedRoomName(supabaseUser?.username))
    }

    Box(modifier = modifier.fillMaxSize().testTag("viewer_mode_screen")) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B1021))
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top Paired Camera Selector Carousel
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "PAIRED CAMERAS (${pairedDevices.size})",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )

                    Row {
                        IconButton(
                            onClick = { showQrScanner = true },
                            modifier = Modifier.testTag("scan_qr_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = "Scan QR",
                                tint = NeonCyanPrimary
                            )
                        }

                        IconButton(
                            onClick = { showPairInput = !showPairInput },
                            modifier = Modifier.testTag("toggle_pair_input_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Pair Code",
                                tint = NeonCyanPrimary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(pairedDevices) { device ->
                        val isSelected = device.id == (activeCamera?.id ?: "")
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) DarkSurfaceVariant else DarkSurface
                            ),
                            shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.5.dp,
                                if (isSelected) NeonCyanPrimary else DarkBorder
                            ),
                            modifier = Modifier
                                .width(140.dp)
                                .clickable { viewModel.selectCamera(device.id) }
                                .testTag("camera_card_${device.id}")
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (device.isOnline) SecurityGreen else LoveAlertRed)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = device.name,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (device.isOnline) "● ONLINE" else "OFFLINE",
                                    color = if (device.isOnline) SecurityGreen else LoveAlertRed,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = showPairInput) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = pairingCodeInput,
                                onValueChange = { pairingCodeInput = it },
                                placeholder = { Text("Enter Code (e.g. LOVE-8832)", color = TextSecondary, fontSize = 12.sp) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = NeonCyanPrimary,
                                    unfocusedBorderColor = DarkBorder,
                                    focusedTextColor = Color.White
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("pairing_input_field")
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            Button(
                                onClick = {
                                    if (pairingCodeInput.isNotBlank()) {
                                        viewModel.pairNewDevice(pairingCodeInput)
                                        pairingCodeInput = ""
                                        showPairInput = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = NeonCyanPrimary),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.testTag("submit_pair_btn")
                            ) {
                                Text("Pair", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Live Stream Display Canvas
            if (activeCamera != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.Black),
                    shape = RoundedCornerShape(18.dp),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, if (activeCamera.nightVision) NightVisionGreen else NeonCyanPrimary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.33f)
                        .clickable { isFullScreen = true }
                        .testTag("viewer_feed_player")
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        val displayBitmap = liveStreamBitmap
                        var forceCellularJpegFallback by remember { mutableStateOf(false) }

                        if (liveKitVideoTrack != null || displayBitmap != null) {
                            // Base layer: render JPEG stream bitmap whenever available (ensures video on SIM mobile networks)
                            if (displayBitmap != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = displayBitmap.asImageBitmap(),
                                    contentDescription = "Live Camera Feed",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }

                            // Overlay layer: LiveKit WebRTC Video Track (if active and not forced to SIM fallback)
                            if (liveKitVideoTrack != null && !forceCellularJpegFallback) {
                                if (!isFullScreen) {
                                    com.example.cctv.ui.components.LiveKitVideoRenderer(
                                        videoTrack = liveKitVideoTrack,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }

                            MotionHeatmapOverlay(
                                motionPoints = dailyMotionPoints,
                                modifier = Modifier.fillMaxSize(),
                                enabled = showHeatmap
                            )

                            Canvas(modifier = Modifier.fillMaxSize()) {
                                for (human in detectedHumans) {
                                    val left = human.boundingBox.left * size.width
                                    val top = human.boundingBox.top * size.height
                                    val right = human.boundingBox.right * size.width
                                    val bottom = human.boundingBox.bottom * size.height
                                    val rectWidth = right - left
                                    val rectHeight = bottom - top

                                    drawRect(
                                        color = NightVisionGreen,
                                        topLeft = Offset(left, top),
                                        size = androidx.compose.ui.geometry.Size(rectWidth, rectHeight),
                                        style = Stroke(width = 3.dp.toPx())
                                    )

                                    drawRect(
                                        color = NightVisionGreen.copy(alpha = 0.15f),
                                        topLeft = Offset(left, top),
                                        size = androidx.compose.ui.geometry.Size(rectWidth, rectHeight)
                                    )
                                }
                            }

                            if (activeCamera.nightVision) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(NightVisionGreen.copy(alpha = 0.15f))
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            when {
                                                liveKitReconnecting -> Color(0xFFFF3D00).copy(alpha = 0.9f)
                                                forceCellularJpegFallback -> Color(0xFFFF9100).copy(alpha = 0.9f)
                                                liveKitVideoTrack != null -> Color(0xFF00E5FF).copy(alpha = 0.9f)
                                                else -> Color(0xFFFF9100).copy(alpha = 0.85f)
                                            }
                                        )
                                        .clickable {
                                            if (liveKitVideoTrack != null) {
                                                forceCellularJpegFallback = !forceCellularJpegFallback
                                            } else {
                                                viewModel.forceReconnectLiveKit()
                                            }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = when {
                                            liveKitReconnecting -> "🔄 RECONNECTING (#$liveKitReconnectAttempt)... TAP TO RETRY"
                                            forceCellularJpegFallback -> "● SIM DATA FEED (JPEG) | TAP FOR WEBRTC"
                                            liveKitVideoTrack != null -> "● LIVEKIT WebRTC | TAP FOR SIM FEED"
                                            else -> "● CELLULAR FEED | $liveKitHealthStatus"
                                        },
                                        color = Color.Black,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }

                            IconButton(
                                onClick = { isFullScreen = true },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.6f))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Fullscreen,
                                    contentDescription = "Full Screen",
                                    tint = Color.White
                                )
                            }
                        } else {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VideocamOff,
                                    contentDescription = "No Feed",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Connecting to Live Stream...",
                                    color = TextSecondary,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }

                // Remote Controls Grid Panel
                RemoteControlPanel(
                    device = activeCamera,
                    onToggleTorch = { viewModel.toggleTorchRemote(activeCamera.id) },
                    onToggleSiren = { viewModel.toggleSirenRemote(activeCamera.id) },
                    onToggleLens = { viewModel.switchCameraRemote(activeCamera.id) },
                    onToggleNightVision = { viewModel.toggleNightVisionRemote(activeCamera.id) },
                    onToggleRecording = { viewModel.startRecordingRemote(activeCamera.id) }
                )

                // Real-Time Live AI Feeds & Threat Copilot Card
                RealTimeAiFeedsCard(
                    feeds = aiDetectionFeeds,
                    threatAssessment = aiThreatAssessment,
                    isAnalyzingGemini = isAnalyzingGemini,
                    onAnalyzeGemini = { viewModel.analyzeSceneWithGemini() },
                    onEvaluateThreat = { viewModel.evaluateThreatLevel() },
                    onOpenCopilot = { showAiCopilotDialog = true },
                    onSendQuickCommand = { cmd ->
                        when (cmd.lowercase()) {
                            "torch" -> viewModel.toggleTorchRemote(activeCamera.id)
                            "siren" -> viewModel.toggleSirenRemote(activeCamera.id)
                            "night" -> viewModel.toggleNightVisionRemote(activeCamera.id)
                        }
                    }
                )
            } else {
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 30.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "No Camera Paired Yet",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tap + or Scan QR to pair a phone as your live security camera.",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // QR Code Scanner Overlay Dialog
        if (showQrScanner) {
            Dialog(onDismissRequest = { showQrScanner = false }) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "SCAN CAMERA QR CODE",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Box(
                            modifier = Modifier
                                .size(200.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.Black)
                                .border(2.dp, NeonCyanPrimary, RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Camera QR Scanner", color = TextSecondary, fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { showQrScanner = false },
                            colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant)
                        ) {
                            Text("Cancel", color = Color.White)
                        }
                    }
                }
            }
        }

        // Full Size Live Camera View Dialog
        if (isFullScreen && activeCamera != null) {
            Dialog(
                onDismissRequest = { isFullScreen = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    val displayBitmap = liveStreamBitmap

                    if (liveKitVideoTrack != null || displayBitmap != null) {
                        if (displayBitmap != null) {
                            androidx.compose.foundation.Image(
                                bitmap = displayBitmap.asImageBitmap(),
                                contentDescription = "Full Size Live Stream",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }

                        if (liveKitVideoTrack != null) {
                            com.example.cctv.ui.components.LiveKitVideoRenderer(
                                videoTrack = liveKitVideoTrack,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        MotionHeatmapOverlay(
                            motionPoints = dailyMotionPoints,
                            modifier = Modifier.fillMaxSize(),
                            enabled = showHeatmap
                        )

                        Canvas(modifier = Modifier.fillMaxSize()) {
                            for (human in detectedHumans) {
                                val left = human.boundingBox.left * size.width
                                val top = human.boundingBox.top * size.height
                                val right = human.boundingBox.right * size.width
                                val bottom = human.boundingBox.bottom * size.height
                                val rectWidth = right - left
                                val rectHeight = bottom - top

                                drawRect(
                                    color = NightVisionGreen,
                                    topLeft = Offset(left, top),
                                    size = androidx.compose.ui.geometry.Size(rectWidth, rectHeight),
                                    style = Stroke(width = 3.dp.toPx())
                                )

                                drawRect(
                                    color = NightVisionGreen.copy(alpha = 0.15f),
                                    topLeft = Offset(left, top),
                                    size = androidx.compose.ui.geometry.Size(rectWidth, rectHeight)
                                )
                            }
                        }

                        if (activeCamera.nightVision) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(NightVisionGreen.copy(alpha = 0.15f))
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .align(Alignment.TopStart),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.7f))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "${activeCamera.name.uppercase()} • LIVE CCTV (FULL SCREEN)",
                                color = NeonCyanPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        IconButton(
                            onClick = { isFullScreen = false },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.7f))
                        ) {
                            Icon(Icons.Default.FullscreenExit, contentDescription = "Exit Fullscreen", tint = Color.White)
                        }
                    }
                }
            }
        }

        if (showAiCopilotDialog) {
            AiCopilotChatDialog(
                chatMessages = aiChatMessages,
                isThinking = isCopilotThinking,
                onSendMessage = { q -> viewModel.askAiCopilot(q) },
                onDismiss = { showAiCopilotDialog = false }
            )
        }
    }
}

@Composable
private fun RealTimeAiFeedsCard(
    feeds: List<com.example.cctv.ai.AiDetectionFeedItem>,
    threatAssessment: com.example.cctv.ai.AiThreatAssessment,
    isAnalyzingGemini: Boolean,
    onAnalyzeGemini: () -> Unit,
    onEvaluateThreat: () -> Unit,
    onOpenCopilot: () -> Unit,
    onSendQuickCommand: (String) -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("realtime_ai_feeds_card")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(NeonCyanPrimary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "AI Vision",
                        tint = NeonCyanPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "REAL-TIME AI THREAT GUARD",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                    Text(
                        text = "Gemini 3.5 Flash • On-Device ML Kit • Green Bounding Boxes",
                        color = SecurityGreen,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(NightVisionGreen.copy(alpha = 0.15f))
                        .border(1.dp, NightVisionGreen, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "LIVE FREE AI",
                        color = NightVisionGreen,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // AI Threat Score HUD Banner
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    when (threatAssessment.status) {
                        "THREAT DETECTED" -> LoveAlertRed
                        "SUSPICIOUS" -> Color(0xFFFF9100)
                        else -> SecurityGreen
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = "Threat Score",
                                tint = when (threatAssessment.status) {
                                    "THREAT DETECTED" -> LoveAlertRed
                                    "SUSPICIOUS" -> Color(0xFFFF9100)
                                    else -> SecurityGreen
                                },
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "THREAT LEVEL: ${threatAssessment.status}",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Text(
                            text = "${threatAssessment.score}%",
                            color = when (threatAssessment.status) {
                                "THREAT DETECTED" -> LoveAlertRed
                                "SUSPICIOUS" -> Color(0xFFFF9100)
                                else -> SecurityGreen
                            },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    LinearProgressIndicator(
                        progress = { (threatAssessment.score / 100f).coerceIn(0f, 1f) },
                        color = when (threatAssessment.status) {
                            "THREAT DETECTED" -> LoveAlertRed
                            "SUSPICIOUS" -> Color(0xFFFF9100)
                            else -> SecurityGreen
                        },
                        trackColor = Color.Black.copy(alpha = 0.5f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = threatAssessment.summary,
                        color = TextSecondary,
                        fontSize = 11.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Recommended Quick Action Badges
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        threatAssessment.recommendedActions.take(3).forEach { action ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black.copy(alpha = 0.4f))
                                    .border(1.dp, NeonCyanPrimary.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                    .clickable {
                                        if (action.contains("Torch", ignoreCase = true)) onSendQuickCommand("torch")
                                        if (action.contains("Siren", ignoreCase = true)) onSendQuickCommand("siren")
                                        if (action.contains("Night", ignoreCase = true)) onSendQuickCommand("night")
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "⚡ $action",
                                    color = NeonCyanPrimary,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Dual Action Buttons: "Analyze Live Scene" & "Ask AI Guard"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onAnalyzeGemini,
                    enabled = !isAnalyzingGemini,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyanPrimary),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("analyze_gemini_btn")
                ) {
                    if (isAnalyzingGemini) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.Black,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Analyzing...", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Analyze Scene", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Button(
                    onClick = onOpenCopilot,
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                    border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyanPrimary),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("open_ai_copilot_btn")
                ) {
                    Icon(Icons.Default.Chat, contentDescription = null, tint = NeonCyanPrimary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Ask AI Guard 💬", color = NeonCyanPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Activity Incident Feeds Stream List
            Text(
                text = "INCIDENT & DETECTION FEED STREAM (${feeds.size})",
                color = TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                feeds.take(8).forEach { item ->
                    val (icon, color) = when (item.category) {
                        "human" -> Icons.Default.PersonSearch to NightVisionGreen
                        "motion" -> Icons.Default.DirectionsRun to NeonCyanPrimary
                        "night" -> Icons.Default.Nightlight to NightVisionGreen
                        "siren" -> Icons.Default.VolumeUp to LoveAlertRed
                        "gemini" -> Icons.Default.AutoAwesome to Color(0xFFBB86FC)
                        else -> Icons.Default.Visibility to NeonCyanPrimary
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(DarkSurfaceVariant)
                            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(color.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = item.category,
                                tint = color,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = item.title,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = timeFormat.format(Date(item.timestamp)),
                                    color = TextSecondary,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = item.detail,
                                color = TextSecondary,
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
