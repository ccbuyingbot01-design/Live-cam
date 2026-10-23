package com.example.cctv.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cctv.util.DebugLogEntry
import com.example.cctv.util.DebugLogger
import com.example.cctv.util.LogLevel
import com.example.ui.theme.LoveAlertRed
import com.example.ui.theme.NeonCyanPrimary

@Composable
fun DebugConsoleOverlay(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val logs by DebugLogger.logs.collectAsState()
    val hasErrors by DebugLogger.hasErrors.collectAsState()
    val latestCrash by DebugLogger.latestCrash.collectAsState()

    var isExpanded by remember { mutableStateOf(false) }
    var filterOnlyErrors by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val errorCount = logs.count { it.level == LogLevel.ERROR || it.level == LogLevel.CRASH }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("debug_console_overlay_container")
    ) {
        // Floating Debug Trigger Pill (Always visible in top right)
        if (!isExpanded) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 40.dp, end = 12.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (hasErrors) LoveAlertRed.copy(alpha = 0.95f) else Color(0xDD121B2D))
                    .border(
                        width = 1.5.dp,
                        color = if (hasErrors) Color.White else NeonCyanPrimary,
                        shape = RoundedCornerShape(20.dp)
                    )
                    .clickable { isExpanded = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("debug_overlay_trigger_btn")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (hasErrors) Icons.Default.Warning else Icons.Default.BugReport,
                        contentDescription = "Debug",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (errorCount > 0) "DEBUG ($errorCount ERR)" else "DEBUG LOGS",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Full Interactive Debug Console Drawer Modal
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .padding(12.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF090D16)),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, if (hasErrors) LoveAlertRed else NeonCyanPrimary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.90f)
                        .align(Alignment.Center)
                        .testTag("debug_console_card")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                    ) {
                        // Title Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(if (hasErrors) LoveAlertRed else NeonCyanPrimary.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.BugReport,
                                        contentDescription = null,
                                        tint = if (hasErrors) Color.White else NeonCyanPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "LIVE DEBUG ERROR CONSOLE",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Captured Logs: ${logs.size} | Errors: $errorCount",
                                        color = if (hasErrors) LoveAlertRed else NeonCyanPrimary,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }

                            IconButton(
                                onClick = { isExpanded = false },
                                modifier = Modifier.testTag("debug_console_close_btn")
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Controls: Copy, Clear, Filter
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    val formatted = DebugLogger.getFormattedLogs()
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("LoveCCTV_DebugLogs", formatted)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Copied debug logs to Clipboard! 📋", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = NeonCyanPrimary),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("debug_copy_logs_btn")
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("COPY LOGS", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = { filterOnlyErrors = !filterOnlyErrors },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (filterOnlyErrors) LoveAlertRed else Color(0xFF1C273C)
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("debug_filter_errors_btn")
                            ) {
                                Icon(Icons.Default.FilterList, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (filterOnlyErrors) "ERRORS ONLY" else "ALL LOGS", color = Color.White, fontSize = 11.sp)
                            }

                            Button(
                                onClick = { DebugLogger.clear() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A1519)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("debug_clear_logs_btn")
                            ) {
                                Icon(Icons.Default.Clear, contentDescription = null, tint = LoveAlertRed, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("CLEAR", color = LoveAlertRed, fontSize = 11.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Search Input
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Filter logs by tag or error text...", color = Color.Gray, fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonCyanPrimary,
                                unfocusedBorderColor = Color.DarkGray,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Filtered Logs List
                        val filteredLogs = logs.filter { entry ->
                            val matchesError = !filterOnlyErrors || entry.level == LogLevel.ERROR || entry.level == LogLevel.CRASH
                            val matchesSearch = searchQuery.isBlank() ||
                                    entry.tag.contains(searchQuery, ignoreCase = true) ||
                                    entry.message.contains(searchQuery, ignoreCase = true) ||
                                    (entry.stackTrace?.contains(searchQuery, ignoreCase = true) == true)
                            matchesError && matchesSearch
                        }.reversed()

                        if (filteredLogs.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (logs.isEmpty()) "No log entries captured yet." else "No matching log entries.",
                                    color = Color.Gray,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(filteredLogs, key = { it.id }) { item ->
                                    LogEntryCard(item)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogEntryCard(entry: DebugLogEntry) {
    var expanded by remember { mutableStateOf(entry.level == LogLevel.CRASH || entry.level == LogLevel.ERROR) }

    val levelColor = when (entry.level) {
        LogLevel.INFO -> NeonCyanPrimary
        LogLevel.WARN -> Color.Yellow
        LogLevel.ERROR -> LoveAlertRed
        LogLevel.CRASH -> Color(0xFFFF0055)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                when (entry.level) {
                    LogLevel.CRASH -> Color(0xFF2E0911)
                    LogLevel.ERROR -> Color(0xFF220C11)
                    LogLevel.WARN -> Color(0xFF1E1C0B)
                    LogLevel.INFO -> Color(0xFF0D1624)
                }
            )
            .border(1.dp, levelColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded }
            .padding(10.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(levelColor.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = entry.level.name,
                            color = levelColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = entry.tag,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.timestamp,
                        color = Color.Gray,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    if (!entry.stackTrace.isNullOrBlank()) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = "Expand",
                            tint = Color.Gray,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = entry.message,
                color = Color.White,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )

            if (expanded && !entry.stackTrace.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.8f))
                        .padding(8.dp)
                ) {
                    Text(
                        text = entry.stackTrace,
                        color = Color(0xFFFF88A0),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 12.sp
                    )
                }
            }
        }
    }
}
