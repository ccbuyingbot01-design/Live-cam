package com.example.cctv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.cctv.ui.components.CameraPreviewOverlay
import com.example.cctv.ui.components.PairingQrDialog
import com.example.cctv.viewmodel.CctvViewModel
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.NeonCyanPrimary
import com.example.ui.theme.NightVisionGreen
import com.example.ui.theme.TextSecondary

@Composable
fun CameraModeScreen(viewModel: CctvViewModel) {
    val currentDevice by viewModel.currentDevice.collectAsState()
    val supabaseUser by viewModel.supabaseUser.collectAsState()
    val liveKitHealthStatus by viewModel.liveKitHealthStatus.collectAsState()
    val liveKitLogs by viewModel.liveKitConnectionLogs.collectAsState()
    val isMicrophoneMuted by viewModel.isMicrophoneMuted.collectAsState()

    var showQrDialog by remember { mutableStateOf(false) }
    var showLogsConsole by remember { mutableStateOf(false) }

    // Automatically ensure LiveKit Cloud Sender connects for this device & user room
    LaunchedEffect(supabaseUser?.username, currentDevice.pairingCode) {
        val targetKey = currentDevice.pairingCode.ifBlank { null }
            ?: supabaseUser?.username?.ifBlank { null }
            ?: "camera"
        val roomName = viewModel.getSanitizedRoomName(targetKey)
        viewModel.startLiveKitSender(roomName)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1021))
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Info Header Card
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface.copy(alpha = 0.95f)),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("camera_info_header")
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(NeonCyanPrimary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = "Device",
                        tint = NeonCyanPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Text(
                        text = currentDevice.name,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "CODE: ${currentDevice.pairingCode} | USER: ${supabaseUser?.username?.uppercase() ?: "GUEST"}",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // QR Pairing Icon Button
                IconButton(
                    onClick = { showQrDialog = true },
                    modifier = Modifier.testTag("open_qr_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCode,
                        contentDescription = "QR Code",
                        tint = NeonCyanPrimary
                    )
                }
            }
        }

        // Live Camera Preview Card (Main Viewfinder & LiveKit Stream Publisher)
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.Black),
            shape = RoundedCornerShape(18.dp),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, if (currentDevice.nightVision) NightVisionGreen else NeonCyanPrimary),
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp)
                .testTag("camera_preview_card")
        ) {
            CameraPreviewOverlay(
                viewModel = viewModel,
                device = currentDevice,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Live Cam Remote Controls & Microphones Action Bar
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "LIVE CAM CONTROLS & 2-WAY AUDIO",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Flash Torch Toggle
                    IconButton(
                        onClick = { viewModel.toggleTorchLocal() },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (currentDevice.torchOn) Color.Yellow else Color.White.copy(alpha = 0.1f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.FlashOn,
                            contentDescription = "Torch",
                            tint = if (currentDevice.torchOn) Color.Black else Color.White
                        )
                    }

                    // Night Vision Toggle
                    IconButton(
                        onClick = { viewModel.toggleNightVisionLocal() },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (currentDevice.nightVision) NightVisionGreen else Color.White.copy(alpha = 0.1f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Nightlight,
                            contentDescription = "Night Vision",
                            tint = if (currentDevice.nightVision) Color.Black else Color.White
                        )
                    }

                    // Lens Facing Toggle (Front/Back)
                    IconButton(
                        onClick = { viewModel.toggleLensFacing() },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.1f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = "Switch Lens",
                            tint = NeonCyanPrimary
                        )
                    }

                    // 2-Way Microphone Mute/Unmute
                    IconButton(
                        onClick = { viewModel.setMicrophoneEnabled(isMicrophoneMuted) },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (!isMicrophoneMuted) NeonCyanPrimary else Color.Red.copy(alpha = 0.2f))
                    ) {
                        Icon(
                            imageVector = if (!isMicrophoneMuted) Icons.Default.Mic else Icons.Default.MicOff,
                            contentDescription = "Microphone",
                            tint = if (!isMicrophoneMuted) Color.Black else Color.Red
                        )
                    }

                    // Emergency Siren Toggle
                    IconButton(
                        onClick = { viewModel.toggleSirenLocal() },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (currentDevice.sirenActive) Color.Red else Color.White.copy(alpha = 0.1f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.VolumeUp,
                            contentDescription = "Siren",
                            tint = if (currentDevice.sirenActive) Color.White else Color.Red
                        )
                    }
                }
            }
        }

        // LiveKit Connection Debug Console Expander
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF121829)),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyanPrimary.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showLogsConsole = !showLogsConsole },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "LiveKit Status",
                            tint = NeonCyanPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "LiveKit Stream Status ($liveKitHealthStatus)",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Text(
                        text = if (showLogsConsole) "▲ HIDE" else "▼ LOGS (${liveKitLogs.size})",
                        color = NeonCyanPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                if (showLogsConsole) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            liveKitLogs.forEach { logLine ->
                                Text(
                                    text = logLine,
                                    color = if (logLine.contains("ERROR", ignoreCase = true)) Color.Red else NightVisionGreen,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // QR Code Pairing Dialog
    if (showQrDialog) {
        PairingQrDialog(
            device = currentDevice,
            username = supabaseUser?.username ?: "",
            onDismiss = { showQrDialog = false }
        )
    }
}
