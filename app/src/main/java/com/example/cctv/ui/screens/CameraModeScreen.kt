package com.example.cctv.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    val batteryReport by viewModel.batteryReport.collectAsState()
    var showQrDialog by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var showBatteryDialog by remember { mutableStateOf(false) }
    var isServiceRunning by remember { mutableStateOf(CctvForegroundService.isServiceRunning) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) {
            try {
                viewModel.startForegroundCctvService(context)
            } catch (e: Exception) {
                Log.e("CameraModeScreen", "Foreground service start error", e)
            }
        }
    }

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

    // Auto-start LiveKit Cloud WebRTC sender on camera screen open
    LaunchedEffect(supabaseUser?.username) {
        val user = supabaseUser?.username ?: "camera"
        viewModel.startLiveKitSender("lovecctv_$user")
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("camera_mode_screen")
    ) {
        if (hasCameraPermission) {
            // Main Viewfinder Preview
            CameraPreviewOverlay(
                viewModel = viewModel,
                device = currentDevice,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Permission Grant Request UI
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Cameraswitch,
                    contentDescription = "Camera Permission",
                    tint = NeonCyanPrimary,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "CAMERA PERMISSION REQUIRED",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Love CCTV needs access to your device camera to stream live video to your monitor phone.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyanPrimary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Grant Camera Access", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }

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

                    // AI Low Power Mode Live Streaming Alert Banner
                    AnimatedVisibility(visible = currentDevice.isLowPowerMode) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(LoveAlertRed.copy(alpha = 0.2f))
                                .border(1.dp, LoveAlertRed, RoundedCornerShape(12.dp))
                                .clickable { showBatteryDialog = true }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = "AI Eco Saver",
                                tint = LoveAlertRed,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "AI ECO SAVER ACTIVE (<40% BATTERY)",
                                    color = LoveAlertRed,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "5 FPS • 240p low-power stream • Tap to view diagnostics",
                                    color = Color.White,
                                    fontSize = 10.sp
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Info",
                                tint = LoveAlertRed,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // Background Service & Battery Telemetry Banner
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(DarkSurface.copy(alpha = 0.9f))
                            .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showBatteryDialog = true }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                .testTag("battery_diagnostics_btn"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (currentDevice.isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
                                contentDescription = "Battery",
                                tint = when {
                                    currentDevice.isCharging -> SecurityGreen
                                    currentDevice.isLowPowerMode -> LoveAlertRed
                                    currentDevice.batteryLevel > 20 -> SecurityGreen
                                    else -> LoveAlertRed
                                },
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = "Battery ${currentDevice.batteryLevel}%" + if (currentDevice.isCharging) " (⚡)" else "",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (currentDevice.isLowPowerMode) "AI Eco 5 FPS" else "Normal 15 FPS",
                                    color = if (currentDevice.isLowPowerMode) LoveAlertRed else NeonCyanPrimary,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        Text(
                            text = "BG Guard",
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
                            onClick = { viewModel.toggleTorch(context) },
                            testTag = "btn_torch_toggle"
                        )

                        // Flip Camera Lens Button
                        ActionButton(
                            icon = Icons.Default.Cameraswitch,
                            label = if (currentDevice.lensFacingFront) "Front" else "Rear",
                            isActive = currentDevice.lensFacingFront,
                            activeColor = NeonCyanPrimary,
                            onClick = { viewModel.toggleCameraLens() },
                            testTag = "btn_lens_switch"
                        )

                        // Siren Emergency Alarm Button
                        ActionButton(
                            icon = Icons.Default.VolumeUp,
                            label = "Siren",
                            isActive = isSirenActive,
                            activeColor = LoveAlertRed,
                            onClick = { viewModel.toggleSirenAlarm(context) },
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

        // AI Battery Optimization Diagnostics Dialog Modal
        if (showBatteryDialog) {
            AiBatteryDiagnosticsDialog(
                device = currentDevice,
                batteryReport = batteryReport,
                onDismiss = { showBatteryDialog = false },
                onToggleSaver = { viewModel.toggleSmartBatterySaver(it) },
                onToggleManualLowPower = { viewModel.toggleManualLowPowerMode() },
                onIgnoreBatteryOptimization = { viewModel.requestIgnoreBatteryOptimization(context) }
            )
        }
    }
}

@Composable
private fun AiBatteryDiagnosticsDialog(
    device: com.example.cctv.data.CctvDevice,
    batteryReport: com.example.cctv.service.BatteryReport?,
    onDismiss: () -> Unit,
    onToggleSaver: (Boolean) -> Unit,
    onToggleManualLowPower: () -> Unit,
    onIgnoreBatteryOptimization: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, if (device.isLowPowerMode) LoveAlertRed else NeonCyanPrimary),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 20.dp)
                .testTag("ai_battery_diagnostics_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (device.isLowPowerMode) LoveAlertRed.copy(alpha = 0.2f) else NeonCyanPrimary.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = "Battery AI",
                            tint = if (device.isLowPowerMode) LoveAlertRed else NeonCyanPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AI SMART BATTERY SAVER",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = if (device.isLowPowerMode) "LOW POWER STREAMING ACTIVE (<40%)" else "ACTIVE INTELLIGENT MONITORING",
                            color = if (device.isLowPowerMode) LoveAlertRed else SecurityGreen,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Battery Progress Bar Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Battery Level: ${device.batteryLevel}%",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (device.isCharging) "⚡ Charging (AC/USB)" else "Discharging",
                                color = if (device.isCharging) SecurityGreen else TextSecondary,
                                fontSize = 11.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        LinearProgressIndicator(
                            progress = { (device.batteryLevel / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = when {
                                device.isCharging -> SecurityGreen
                                device.isLowPowerMode -> LoveAlertRed
                                device.batteryLevel > 20 -> NeonCyanPrimary
                                else -> LoveAlertRed
                            },
                            trackColor = Color.Black.copy(alpha = 0.5f)
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Temp: ${device.batteryTempCelsius}°C",
                                color = TextSecondary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "Est. Runtime: ~${String.format("%.1f", batteryReport?.estimatedHoursRemaining ?: 4f)}h",
                                color = NeonCyanPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Streaming Power State Telemetry Grid
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurfaceVariant)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TelemetryRow(
                        label = "Streaming Target",
                        value = if (device.isLowPowerMode) "5 FPS @ 240p (Low-Power)" else "15 FPS @ 320p (Normal)",
                        isHighlighted = device.isLowPowerMode
                    )
                    TelemetryRow(
                        label = "PowerManager WakeLock",
                        value = "ACTIVE (Prevents Screen Lock Sleep)",
                        isHighlighted = true
                    )
                    TelemetryRow(
                        label = "WifiLock Radio",
                        value = "ACTIVE (Zero-Throttle Mode)",
                        isHighlighted = true
                    )
                    TelemetryRow(
                        label = "Auto Low-Power Trigger",
                        value = "Activates when battery < ${device.lowPowerThreshold}%",
                        isHighlighted = false
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // AI Insight Callout
                batteryReport?.aiInsight?.let { insight ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF071927))
                            .border(1.dp, NeonCyanPrimary.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = insight,
                            color = NeonCyanPrimary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Toggle: AI Smart Battery Saver (<40% auto trigger)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AI Smart Battery Saver",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Auto-switches to 5 FPS @ 240p when under 40%",
                            color = TextSecondary,
                            fontSize = 10.sp
                        )
                    }
                    Switch(
                        checked = device.smartBatterySaverEnabled,
                        onCheckedChange = onToggleSaver,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = NeonCyanPrimary
                        )
                    )
                }

                // Toggle: Manual Low Power Mode
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Force Low Power Streaming",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Forces 5 FPS low-power mode regardless of battery %",
                            color = TextSecondary,
                            fontSize = 10.sp
                        )
                    }
                    Switch(
                        checked = device.isLowPowerMode,
                        onCheckedChange = { onToggleManualLowPower() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = LoveAlertRed
                        )
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Whitelist Battery Optimization Button
                Button(
                    onClick = onIgnoreBatteryOptimization,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyanPrimary.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = "Battery Whitelist",
                        tint = NeonCyanPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Whitelist App from Battery Optimization",
                        color = NeonCyanPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun TelemetryRow(
    label: String,
    value: String,
    isHighlighted: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 11.sp
        )
        Text(
            text = value,
            color = if (isHighlighted) SecurityGreen else Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
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
