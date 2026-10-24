package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.cctv.data.DeviceMode
import com.example.cctv.supabase.SupabaseUser
import com.example.cctv.ui.screens.AuthScreen
import com.example.cctv.ui.screens.CameraModeScreen
import com.example.cctv.ui.screens.MotionLogsScreen
import com.example.cctv.ui.screens.PermissionsGuideScreen
import com.example.cctv.ui.screens.RoleChoiceScreen
import com.example.cctv.ui.screens.ViewerModeScreen
import com.example.cctv.viewmodel.CctvViewModel
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.LoveAlertRed
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.NeonCyanPrimary
import com.example.ui.theme.TextSecondary

import com.example.cctv.ui.components.DebugConsoleOverlay
import com.example.cctv.util.DebugLogger

enum class AppDestination {
    AUTH,
    ROLE_CHOICE,
    SURVEILLANCE_MAIN,
    MOTION_LOGS,
    PERMISSIONS_GUIDE
}

class MainActivity : ComponentActivity() {

    private val viewModel: CctvViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        if (cameraGranted) {
            try {
                viewModel.startForegroundCctvService(this)
            } catch (e: Throwable) {
                DebugLogger.w("MainActivity", "Failed to start foreground service on launcher result", e)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            enableEdgeToEdge()
        } catch (e: Throwable) {
            DebugLogger.w("MainActivity", "enableEdgeToEdge fallback", e)
        }

        // Initialize global DebugLogger to capture all errors & uncaught crashes
        try {
            DebugLogger.init(applicationContext)
            DebugLogger.i("MainActivity", "App started. Debug console active on all screens.")
        } catch (_: Throwable) {}

        requestInitialPermissions()

        setContent {
            MyApplicationTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    LoveCctvApp(
                        viewModel = viewModel,
                        onRequestPermissions = { requestInitialPermissions() }
                    )

                    // Always-on Debug Mode overlay box on top of EVERY page
                    DebugConsoleOverlay()
                }
            }
        }
    }

    private fun requestInitialPermissions() {
        val perms = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        // Android 13+ granular media permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
            perms.add(Manifest.permission.READ_MEDIA_VIDEO)
        }
        // Android 9 and below need WRITE_EXTERNAL_STORAGE to save videos to SD card
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}

@Composable
fun LoveCctvApp(
    viewModel: CctvViewModel,
    onRequestPermissions: () -> Unit
) {
    var currentDestination by remember { mutableStateOf(AppDestination.AUTH) }
    val supabaseUser by viewModel.supabaseUser.collectAsState()
    val deviceMode by viewModel.deviceMode.collectAsState()
    val motionEvents by viewModel.motionEvents.collectAsState()

    androidx.compose.runtime.LaunchedEffect(supabaseUser) {
        if (supabaseUser != null && currentDestination == AppDestination.AUTH) {
            val rememberedMode = viewModel.getRememberedMode()
            if (rememberedMode != null) {
                viewModel.setDeviceMode(rememberedMode)
                currentDestination = AppDestination.SURVEILLANCE_MAIN
            } else {
                currentDestination = AppDestination.ROLE_CHOICE
            }
        }
    }

    Scaffold(
        topBar = {
            if (currentDestination == AppDestination.SURVEILLANCE_MAIN) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0B1021))
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(LoveAlertRed),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "Love CCTV",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Text(
                            text = "LOVE CCTV",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.2.sp
                        )
                        Text(
                            text = "USER: ${supabaseUser?.username?.uppercase() ?: "GUEST"} | ${if (deviceMode == DeviceMode.CAMERA_SENDER) "LIVE CAM" else "VIEW CAM"}",
                            color = NeonCyanPrimary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Switch Account / Role
                    IconButton(
                        onClick = { currentDestination = AppDestination.ROLE_CHOICE },
                        modifier = Modifier.testTag("appbar_switch_role_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = "Role",
                            tint = NeonCyanPrimary
                        )
                    }

                    // Motion Log Icon Badge
                    IconButton(
                        onClick = { currentDestination = AppDestination.MOTION_LOGS },
                        modifier = Modifier.testTag("appbar_motion_logs_btn")
                    ) {
                        BadgedBox(
                            badge = {
                                if (motionEvents.isNotEmpty()) {
                                    Badge(containerColor = LoveAlertRed) {
                                        Text("${motionEvents.size}", color = Color.White)
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = "Logs",
                                tint = Color.White
                            )
                        }
                    }

                    // System Settings / Permissions Guide
                    IconButton(
                        onClick = { currentDestination = AppDestination.PERMISSIONS_GUIDE },
                        modifier = Modifier.testTag("appbar_settings_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = TextSecondary
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (currentDestination == AppDestination.SURVEILLANCE_MAIN) {
                NavigationBar(
                    containerColor = DarkSurface,
                    modifier = Modifier
                        .navigationBarsPadding()
                        .border(androidx.compose.foundation.BorderStroke(1.dp, DarkBorder))
                        .testTag("cctv_bottom_navigation")
                ) {
                    NavigationBarItem(
                        selected = deviceMode == DeviceMode.CAMERA_SENDER,
                        onClick = { viewModel.setDeviceModeAndRemember(DeviceMode.CAMERA_SENDER, viewModel.getRememberChoice()) },
                        icon = { Icon(Icons.Default.Videocam, contentDescription = "Live Cam") },
                        label = { Text("Live Cam") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.Black,
                            selectedTextColor = NeonCyanPrimary,
                            indicatorColor = NeonCyanPrimary,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary
                        ),
                        modifier = Modifier.testTag("tab_camera_mode")
                    )

                    NavigationBarItem(
                        selected = deviceMode == DeviceMode.VIEWER_MONITOR,
                        onClick = { viewModel.setDeviceModeAndRemember(DeviceMode.VIEWER_MONITOR, viewModel.getRememberChoice()) },
                        icon = { Icon(Icons.Default.Monitor, contentDescription = "View Cam") },
                        label = { Text("View Cam") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.Black,
                            selectedTextColor = NeonCyanPrimary,
                            indicatorColor = NeonCyanPrimary,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary
                        ),
                        modifier = Modifier.testTag("tab_viewer_mode")
                    )
                }
            }
        },
        containerColor = Color(0xFF0B1021)
    ) { innerPadding ->
        Crossfade(
            targetState = currentDestination,
            modifier = Modifier.padding(innerPadding),
            label = "screen_transition"
        ) { destination ->
            when (destination) {
                AppDestination.AUTH -> {
                    AuthScreen(
                        viewModel = viewModel,
                        onAuthSuccess = { currentDestination = AppDestination.ROLE_CHOICE }
                    )
                }
                AppDestination.ROLE_CHOICE -> {
                    supabaseUser?.let { user ->
                        RoleChoiceScreen(
                            user = user,
                            onSelectRole = { selectedMode ->
                                viewModel.setDeviceModeAndRemember(selectedMode, true)
                                currentDestination = AppDestination.SURVEILLANCE_MAIN
                            }
                        )
                    } ?: run {
                        currentDestination = AppDestination.AUTH
                    }
                }
                AppDestination.SURVEILLANCE_MAIN -> {
                    if (deviceMode == DeviceMode.CAMERA_SENDER) {
                        CameraModeScreen(viewModel = viewModel)
                    } else {
                        ViewerModeScreen(
                            viewModel = viewModel,
                            onNavigateToLogs = { currentDestination = AppDestination.MOTION_LOGS }
                        )
                    }
                }
                AppDestination.MOTION_LOGS -> {
                    MotionLogsScreen(
                        viewModel = viewModel,
                        onBack = { currentDestination = AppDestination.SURVEILLANCE_MAIN }
                    )
                }
                AppDestination.PERMISSIONS_GUIDE -> {
                    PermissionsGuideScreen(
                        onBack = { currentDestination = AppDestination.SURVEILLANCE_MAIN },
                        onRequestCameraPermissions = onRequestPermissions
                    )
                }
            }
        }
    }
}
