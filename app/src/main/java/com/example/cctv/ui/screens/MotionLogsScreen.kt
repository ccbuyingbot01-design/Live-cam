package com.example.cctv.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.cctv.data.MotionEvent
import com.example.cctv.data.MotionSeverity
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.LoveAlertRed
import com.example.ui.theme.NeonCyanPrimary
import com.example.ui.theme.SecurityGreen
import com.example.ui.theme.SecurityYellow
import com.example.ui.theme.TextSecondary
import com.example.cctv.viewmodel.CctvViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MotionLogsScreen(
    viewModel: CctvViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }

    val motionEvents by viewModel.motionEvents.collectAsState()
    var selectedSeverityFilter by remember { mutableStateOf<MotionSeverity?>(null) }
    var activeSnapshotModal by remember { mutableStateOf<MotionEvent?>(null) }

    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy - HH:mm:ss", Locale.getDefault()) }

    val filteredEvents = remember(motionEvents, selectedSeverityFilter) {
        if (selectedSeverityFilter == null) {
            motionEvents
        } else {
            motionEvents.filter { it.severity == selectedSeverityFilter }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "MOTION INCIDENT LOGS",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("back_from_logs_btn")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = NeonCyanPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0B1021))
            )
        },
        containerColor = Color(0xFF0B1021),
        modifier = modifier.testTag("motion_logs_screen")
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            // Severity Filter Chips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 10.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedSeverityFilter == null,
                        onClick = { selectedSeverityFilter = null },
                        label = { Text("All (${motionEvents.size})") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = NeonCyanPrimary,
                            selectedLabelColor = Color.Black
                        ),
                        modifier = Modifier.testTag("filter_all")
                    )
                }

                items(MotionSeverity.values()) { severity ->
                    val count = motionEvents.count { it.severity == severity }
                    FilterChip(
                        selected = selectedSeverityFilter == severity,
                        onClick = { selectedSeverityFilter = severity },
                        label = { Text("${severity.name} ($count)") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = when (severity) {
                                MotionSeverity.CRITICAL, MotionSeverity.HIGH -> LoveAlertRed
                                MotionSeverity.MEDIUM -> SecurityYellow
                                MotionSeverity.LOW -> SecurityGreen
                            },
                            selectedLabelColor = Color.Black
                        ),
                        modifier = Modifier.testTag("filter_${severity.name}")
                    )
                }
            }

            if (filteredEvents.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.MotionPhotosOn,
                            contentDescription = "No Motion Events",
                            tint = TextSecondary,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No Motion Incidents Recorded",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "When camera detects movement above sensitivity threshold, snapshots will appear here.",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.SansSerif
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredEvents) { event ->
                        MotionItemCard(
                            event = event,
                            dateFormat = dateFormat,
                            onSnapshotClicked = { activeSnapshotModal = event }
                        )
                    }
                }
            }
        }

        // Snapshot Preview Dialog Modal
        if (activeSnapshotModal != null) {
            val evt = activeSnapshotModal!!
            Dialog(onDismissRequest = { activeSnapshotModal = null }) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = DarkSurface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                    modifier = Modifier.padding(16.dp).testTag("snapshot_modal")
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "SNAPSHOT PREVIEW",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(onClick = { activeSnapshotModal = null }) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        val bitmap = evt.snapshotBase64?.let { decodeBase64ToBitmap(it) }

                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Snapshot Frame",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(1.dp, NeonCyanPrimary, RoundedCornerShape(12.dp))
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(DarkSurfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Frame Snapshot Captured", color = TextSecondary, fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = evt.description,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = dateFormat.format(Date(evt.timestamp)),
                            color = NeonCyanPrimary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MotionItemCard(
    event: MotionEvent,
    dateFormat: SimpleDateFormat,
    onSnapshotClicked: () -> Unit
) {
    val severityColor = when (event.severity) {
        MotionSeverity.CRITICAL, MotionSeverity.HIGH -> LoveAlertRed
        MotionSeverity.MEDIUM -> SecurityYellow
        MotionSeverity.LOW -> SecurityGreen
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("motion_log_item_${event.id}")
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(severityColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MotionPhotosOn,
                    contentDescription = "Severity",
                    tint = severityColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = event.deviceName,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(severityColor.copy(alpha = 0.3f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = event.severity.name,
                            color = severityColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = event.description,
                    color = TextSecondary,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = dateFormat.format(Date(event.timestamp)),
                    color = NeonCyanPrimary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            if (event.snapshotBase64 != null) {
                IconButton(
                    onClick = onSnapshotClicked,
                    modifier = Modifier.testTag("view_snapshot_btn_${event.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = "View Frame",
                        tint = NeonCyanPrimary
                    )
                }
            }
        }
    }
}

private fun decodeBase64ToBitmap(base64Str: String): android.graphics.Bitmap? {
    return try {
        val decodedBytes = Base64.decode(base64Str, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
    } catch (e: Exception) {
        null
    }
}
