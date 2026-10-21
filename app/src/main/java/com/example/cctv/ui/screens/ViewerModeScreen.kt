package com.example.cctv.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cctv.data.CctvDevice
import com.example.cctv.ui.components.RemoteControlPanel
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.LoveAlertRed
import com.example.ui.theme.NeonCyanPrimary
import com.example.ui.theme.NightVisionGreen
import com.example.ui.theme.SecurityGreen
import com.example.ui.theme.TextSecondary
import com.example.cctv.viewmodel.CctvViewModel
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

    // Direct UDP / Hardware-accelerated live stream frame (zero-latency buffer clearing)
    val liveStreamBitmap by viewModel.liveStreamBitmap.collectAsState()
    var isFullScreen by remember { mutableStateOf(false) }

    // Cloud stream data
    val remoteFrame by viewModel.remoteFrameBase64.collectAsState()
    val remoteStreamLive by viewModel.remoteStreamLive.collectAsState()

    val scrollState = rememberScrollState()
    val timeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B1021))
            .padding(16.dp)
            .verticalScroll(scrollState)
            .testTag("viewer_mode_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Paired Camera Selector Carousel
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PAIRED CAMERAS (${pairedDevices.size})",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                Spacer(modifier = Modifier.weight(1f))

                IconButton(
                    onClick = { showPairInput = !showPairInput },
                    modifier = Modifier.testTag("toggle_add_pair_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Pair Camera",
                        tint = NeonCyanPrimary
                    )
                }

                // QR Scanner button
                IconButton(
                    onClick = { showQrScanner = true },
                    modifier = Modifier.testTag("viewer_scan_qr_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = "Scan QR",
                        tint = NeonCyanPrimary
                    )
                }

                IconButton(
                    onClick = onNavigateToLogs,
                    modifier = Modifier.testTag("go_to_logs_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = "Motion Logs",
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Camera Device Tabs
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(pairedDevices) { dev ->
                    val isSelected = dev.id == selectedCameraId
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) NeonCyanPrimary.copy(alpha = 0.18f) else DarkSurface
                        ),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) NeonCyanPrimary else DarkBorder
                        ),
                        modifier = Modifier
                            .clickable { viewModel.selectViewerCamera(dev.id) }
                            .testTag("cam_tab_${dev.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (dev.isOnline) SecurityGreen else Color.Gray)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = dev.name,
                                color = if (isSelected) NeonCyanPrimary else Color.White,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            // Quick Pair Code Input Expandable
            AnimatedVisibility(visible = showPairInput) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .testTag("pair_code_input_card")
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

        // Live Feed Screen Display Canvas (Tap for Full Screen)
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
                    val displayBitmap = liveStreamBitmap ?: remember(remoteFrame) {
                        if (remoteStreamLive && remoteFrame != null) {
                            try {
                                val bytes = Base64.decode(remoteFrame, Base64.DEFAULT)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            } catch (e: Exception) { null }
                        } else null
                    }

                    if (displayBitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = displayBitmap.asImageBitmap(),
                            contentDescription = "Live Camera Feed",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )

                        // Night vision overlay
                        if (activeCamera.nightVision) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(NightVisionGreen.copy(alpha = 0.15f))
                            )
                        }
                    } else {
                        // No stream yet — show waiting placeholder
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF080D1A)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.VideocamOff,
                                    contentDescription = "Offline",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(48.dp)
                                )
                                Text(
                                    text = if (activeCamera.isOnline) "Waiting for live stream..." else "Camera Offline",
                                    color = TextSecondary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Tap screen to expand full size • 480p @ 15 FPS Direct P2P",
                                    color = TextSecondary.copy(alpha = 0.6f),
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    // Feed HUD Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                            .align(Alignment.TopStart),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.7f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (remoteStreamLive) SecurityGreen else Color.Gray)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = activeCamera.name.uppercase(),
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.7f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = timeFormat.format(Date()),
                                color = NeonCyanPrimary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // Feed HUD Footer
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                            .align(Alignment.BottomStart),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.7f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.SignalCellularAlt,
                                    contentDescription = "Signal",
                                    tint = if (remoteStreamLive) SecurityGreen else Color.Gray,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (liveStreamBitmap != null) "DIRECT P2P LIVE (15 FPS)" else if (remoteStreamLive) "CLOUD STREAM ACTIVE" else "NO SIGNAL",
                                    color = if (liveStreamBitmap != null) SecurityGreen else Color.White,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.BatteryFull, contentDescription = "Battery", tint = SecurityGreen, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${activeCamera.batteryLevel}%",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // Remote Control Action Grid
            RemoteControlPanel(
                device = activeCamera,
                onToggleTorch = { viewModel.remoteToggleTorch(activeCamera.id) },
                onToggleSiren = { viewModel.remoteToggleSiren(activeCamera.id) },
                onToggleLens = { viewModel.remoteToggleLens(activeCamera.id) },
                onToggleNightVision = { viewModel.remoteToggleNightVision(activeCamera.id) },
                onToggleRecording = { viewModel.remoteToggleRecording(activeCamera.id) }
            )
        }

        // Live Motion Alert Banner
        if (recentAlert != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = LoveAlertRed.copy(alpha = 0.2f)),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, LoveAlertRed),
                modifier = Modifier.fillMaxWidth().testTag("motion_alert_banner")
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(LoveAlertRed),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = "Alert",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "MOTION ALERT DETECTED!",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = recentAlert?.description ?: "Activity detected on camera",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 11.sp
                        )
                    }

                    IconButton(onClick = { viewModel.dismissRecentMotionAlert() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Dismiss",
                            tint = Color.White
                        )
                    }
                }
            }
        }

        // QR Scanner overlay (accessible from viewer too)
        if (showQrScanner) {
            QrScannerScreen(
                onCodeScanned = { code ->
                    viewModel.pairNewDevice(code)
                    showQrScanner = false
                },
                onDismiss = {
                    showQrScanner = false
                }
            )
        }

        // Full Size Live Camera View Dialog (Tap to expand full screen)
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
                    val displayBitmap = liveStreamBitmap ?: remember(remoteFrame) {
                        if (remoteStreamLive && remoteFrame != null) {
                            try {
                                val bytes = Base64.decode(remoteFrame, Base64.DEFAULT)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            } catch (e: Exception) { null }
                        } else null
                    }

                    if (displayBitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = displayBitmap.asImageBitmap(),
                            contentDescription = "Full Size Live Stream",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )

                        if (activeCamera.nightVision) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(NightVisionGreen.copy(alpha = 0.15f))
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Waiting for live feed...", color = Color.White)
                        }
                    }

                    // Top Bar with Close Fullscreen button
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
                                text = "${activeCamera.name.uppercase()} • 480p @ 15 FPS (ZERO DELAY)",
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
    }
}
