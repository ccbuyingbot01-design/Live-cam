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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cctv.service.StorageWatcher
import com.example.cctv.viewmodel.CctvViewModel
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.NeonCyanPrimary
import com.example.ui.theme.NightVisionGreen
import com.example.ui.theme.TextSecondary

@Composable
fun SettingsScreen(viewModel: CctvViewModel) {
    val context = LocalContext.current
    val device by viewModel.currentDevice.collectAsState()

    val storageOptions = remember { StorageWatcher.getAvailableStorageOptions(context) }
    var selectedPath by remember { mutableStateOf(storageOptions.firstOrNull()?.path?.absolutePath ?: "") }
    var autoCompressEnabled by remember { mutableStateOf(true) }
    var autoSwitchStorage by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D111D))
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("settings_screen")
    ) {
        Text(
            text = "APP & CCTV SETTINGS",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Section 1: Storage Path Selection (Internal vs SD Card)
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Storage,
                        contentDescription = "Storage",
                        tint = NeonCyanPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "RECORDING STORAGE DIRECTORY",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                storageOptions.forEach { option ->
                    val isSelected = selectedPath == option.path.absolutePath
                    val freeGb = (option.freeSpaceBytes / (1024 * 1024 * 1024.0)).let { "%.1f".format(it) }
                    val totalGb = (option.totalSpaceBytes / (1024 * 1024 * 1024.0)).let { "%.1f".format(it) }
                    val freeRatio = if (option.totalSpaceBytes > 0) 1f - (option.freeSpaceBytes.toFloat() / option.totalSpaceBytes) else 0f

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .border(
                                width = 1.dp,
                                color = if (isSelected) NeonCyanPrimary else DarkBorder,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .background(
                                color = if (isSelected) NeonCyanPrimary.copy(alpha = 0.08f) else Color.Transparent,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { selectedPath = option.path.absolutePath }
                            .padding(12.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { selectedPath = option.path.absolutePath },
                                    colors = RadioButtonDefaults.colors(selectedColor = NeonCyanPrimary)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = if (option.isSdCard) Icons.Default.SdCard else Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = if (option.isSdCard) NightVisionGreen else Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = option.name,
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${freeGb} GB free of ${totalGb} GB",
                                        color = TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { freeRatio.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp),
                                color = if (freeRatio > 0.9f) Color.Red else NeonCyanPrimary,
                                trackColor = DarkBorder,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Auto-Switch Storage Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Smart Storage Auto-Switch", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("Switches path if target drive is full (<500MB)", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = autoSwitchStorage,
                        onCheckedChange = { autoSwitchStorage = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = NeonCyanPrimary)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Auto Compression Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("5-Day Smart Low-MB Video Compress", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("Saves compressed videos to fit 5+ days on 32GB SD", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = autoCompressEnabled,
                        onCheckedChange = { autoCompressEnabled = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = NightVisionGreen)
                    )
                }
            }
        }

        // Section 2: Motion Detection & AI Sensitivity Settings
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "AI Motion",
                        tint = NightVisionGreen,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "AI MOTION & HUMAN DETECTION",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Motion Sensitivity: ${(device.motionSensitivity * 100).toInt()}%",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Slider(
                    value = device.motionSensitivity,
                    onValueChange = { viewModel.repository.updateMotionSensitivity(it) },
                    valueRange = 0.1f..1.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = NeonCyanPrimary,
                        activeTrackColor = NeonCyanPrimary
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Vibration Alerts", color = Color.White, fontSize = 13.sp)
                    Switch(
                        checked = device.notificationVibration,
                        onCheckedChange = { viewModel.repository.updateNotificationPrefs(it, device.notificationSound) },
                        colors = SwitchDefaults.colors(checkedTrackColor = NeonCyanPrimary)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Sound Alerts", color = Color.White, fontSize = 13.sp)
                    Switch(
                        checked = device.notificationSound,
                        onCheckedChange = { viewModel.repository.updateNotificationPrefs(device.notificationVibration, it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = NeonCyanPrimary)
                    )
                }
            }
        }

        // Section 3: Paired Devices DB Table Cleanup
        val supabaseUser by viewModel.supabaseUser.collectAsState()
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = "Database",
                        tint = NeonCyanPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ACCOUNT & PAIRED DEVICES DATABASE",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Current Account: ${supabaseUser?.username ?: "Guest"}",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Hashed Pairing Code: ${device.pairingCode}",
                    color = NeonCyanPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        supabaseUser?.username?.let { u -> viewModel.clearPairedDevicesForUser(u) }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = com.example.ui.theme.LoveAlertRed.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Clear Stale Paired Devices in Database",
                        color = com.example.ui.theme.LoveAlertRed,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
