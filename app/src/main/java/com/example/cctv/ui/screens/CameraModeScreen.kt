package com.example.cctv.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.cctv.service.CctvForegroundService
import com.example.cctv.ui.components.CameraPreviewOverlay
import com.example.cctv.ui.components.MotionMeterBar
import com.example.cctv.ui.components.PairingQrDialog
import com.example.cctv.ui.components.StealthBlackOverlay
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.LoveAlertRed
import com.example.ui.theme.NeonCyanPrimary
import com.example.ui.theme.NightVisionGreen
import com.example.ui.theme.SecurityGreen
import com.example.ui.theme.TextSecondary
import com.example.cctv.viewmodel.CctvViewModel

@Composable
fun CameraModeScreen(
    viewModel: CctvViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentDevice by viewModel.currentDevice.collectAsState()
    val isSirenActive by viewModel.isSirenRinging.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingMessage by viewModel.recordingMessage.collectAsState()
    val supabaseUser by viewModel.supabaseUser.collectAsState()
    var showQrDialog by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var isServiceRunning by remember { mutableStateOf(CctvForegroundService.isServiceRunning) }

    // ── Storage permission launcher ────────────────────────────────────
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            viewModel.startVideoRecording(context)
        } else {
            Toast.makeText(
                context,
                "Storage permission is required to save videos to SD card.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Helper: check + request permissions before starting recording
    fun requestStorageAndRecord() {
        val needed = viewModel.videoRecordingManager.requiredPermissions().filter { perm ->
            ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            viewModel.startVideoRecording(context)
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    // Observe pending auto-record command from viewer phone
    val pendingAutoRecord by viewModel.pendingAutoRecord.collectAsState()
    LaunchedEffect(pendingAutoRecord) {
        if (pendingAutoRecord) {
            viewModel.consumePendingAutoRecord()
            requestStorageAndRecord()
        }
    }

    // Show toast when recording finishes or errors
    LaunchedEffect(recordingMessage) {
        recordingMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            viewModel.dismissRecordingMessage()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("camera_mode_screen")
    ) {
        // Main Viewfinder Preview
        CameraPreviewOverlay(
            viewModel = viewModel,
            device = currentDevice,
            modifier = Modifier.fillMaxSize()
        )

        // Overlay Stealth Black Screen if enabled
        AnimatedVisibility(
            visible = currentDevice.stealthMode,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            StealthBlackOverlay(
                onUnlockClicked = { viewModel.toggleStealthMode() }
            )
        }

        // Camera Control Overlay (visible when stealth is OFF)
        if (!currentDevice.stealthMode) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Header Info Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkSurface.copy(alpha = 0.88f)),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                    modifier = Modifier.fillMaxWidth().testTag("camera_info_header")
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
                            Icon(Icons.Default.Shield, contentDescription = "Device", tint = NeonCyanPrimary, modifier = Modifier.size(22.dp))
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
                                text = "Code: ${currentDevice.pairingCode} | IP: ${currentDevice.ipAddress}",
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
                            Icon(Icons.Default.QrCode, contentDescription = "QR Code", tint = NeonCyanPrimary)
                        }
                    }
                }

                // Bottom Dashboard Controls & Motion Meter
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Motion Meter Bar
                    MotionMeterBar(
                        intensity = currentDevice.lastMotionIntensity,
                        isGuardActive = currentDevice.motionGuardEnabled
                    )

                    // Background Service Persistence Banner
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(DarkSurface.copy(alpha = 0.9f))
                            .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (currentDevice.isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
                            contentDescription = "Battery",
                            tint = if (currentDevice.batteryLevel > 20) SecurityGreen else LoveAlertRed,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Battery ${currentDevice.batteryLevel}%",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Spacer(modifier = Modifier.weight(1f))

                        Text(
                            text = "BG Service",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(end = 6.dp)
                        )

                        Switch(
                            checked = isServiceRunning,
                            onCheckedChange = { active ->
                                isServiceRunning = active
                                if (active) {
                                    viewModel.startForegroundCctvService(context)
                                } else {
                                    viewModel.stopForegroundCctvService(context)
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = NeonCyanPrimary
                            ),
                            modifier = Modifier.testTag("bg_service_switch")
                        )
                    }

                    // Main Camera Action Buttons Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(DarkSurface.copy(alpha = 0.95f))
                            .border(1.dp, DarkBorder, RoundedCornerShape(20.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Record / Stop Recording Button
                        ActionButton(
                            icon = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                            label = if (isRecording) "Stop" else "Record",
                            isActive = isRecording,
                            activeColor = LoveAlertRed,
                            onClick = {
                                if (isRecording) {
                                    viewModel.stopVideoRecording()
                                } else {
                                    requestStorageAndRecord()
                                }
                            },
                            testTag = "btn_video_record"
                        )

                        // Torch Toggle Button
                        ActionButton(
                            icon = Icons.Default.FlashOn,
                            label = "Torch",
                            isActive = currentDevice.torchOn,
                            activeColor = Color.Yellow,
                            onClick = { viewModel.toggleTorch() },
                            testTag = "btn_torch_toggle"
                        )

                        // Flip Camera Lens Button
                        ActionButton(
                            icon = Icons.Default.Cameraswitch,
                            label = if (currentDevice.lensFacingFront) "Front" else "Rear",
                            isActive = currentDevice.lensFacingFront,
                            activeColor = NeonCyanPrimary,
                            onClick = {
                                viewModel.switchCameraLens(
                                    context = context,
                                    lifecycleOwner = (context as? androidx.lifecycle.LifecycleOwner)
                                        ?: return@ActionButton,
                                    previewView = androidx.camera.view.PreviewView(context)
                                )
                            },
                            testTag = "btn_lens_switch"
                        )

                        // Siren Emergency Alarm Button
                        ActionButton(
                            icon = Icons.Default.VolumeUp,
                            label = "Siren",
                            isActive = isSirenActive,
                            activeColor = LoveAlertRed,
                            onClick = { viewModel.toggleSirenAlarm() },
                            testTag = "btn_siren_alarm"
                        )

                        // Night Vision Filter
                        ActionButton(
                            icon = Icons.Default.Nightlight,
                            label = "Night",
                            isActive = currentDevice.nightVision,
                            activeColor = NightVisionGreen,
                            onClick = { viewModel.toggleNightVision() },
                            testTag = "btn_night_vision"
                        )

                        // Stealth Black Screen Mode
                        ActionButton(
                            icon = Icons.Default.VisibilityOff,
                            label = "Stealth",
                            isActive = currentDevice.stealthMode,
                            activeColor = Color.White,
                            onClick = { viewModel.toggleStealthMode() },
                            testTag = "btn_stealth_mode"
                        )
                    }
                }
            }
        }

        // QR Code Dialog Modal
        if (showQrDialog) {
            PairingQrDialog(
                device = currentDevice,
                username = supabaseUser?.username ?: "",
                onDismiss = { showQrDialog = false },
                onScanQr = {
                    showQrDialog = false
                    showQrScanner = true
                },
                onPairCode = { code ->
                    viewModel.pairNewDevice(code)
                }
            )
        }

        // QR Scanner Full-Screen Overlay
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
    }
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    testTag: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable { onClick() }
            .testTag(testTag)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (isActive) activeColor.copy(alpha = 0.25f) else DarkSurfaceVariant)
                .border(
                    width = 1.dp,
                    color = if (isActive) activeColor else DarkBorder,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive) activeColor else Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = if (isActive) activeColor else TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
