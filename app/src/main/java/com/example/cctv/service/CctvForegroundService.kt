package com.example.cctv.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * CctvForegroundService
 *
 * Foreground service ensuring the camera streaming process, WebRTC, and motion surveillance
 * do not get throttled or disconnected when the device screen is locked or in a low-power state.
 *
 * Features:
 *  - PowerManager.PARTIAL_WAKE_LOCK with 5-minute heartbeat renewal.
 *  - WifiLock (WIFI_MODE_FULL_LOW_LATENCY / WIFI_MODE_FULL_HIGH_PERF) to prevent radio throttling.
 *  - Screen state and battery state receiver for real-time notification updates.
 *  - AI Low-Power Saver indicator when battery drops under 40%.
 */
class CctvForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var heartbeatJob: Job? = null

    private var currentBatteryPct = 85
    private var isCurrentlyCharging = false
    private var isSystemPowerSave = false

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen OFF detected: Reaffirming WakeLock & WifiLock to prevent stream throttling...")
                    reaffirmLocks()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "Screen ON detected.")
                }
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                    isSystemPowerSave = pm?.isPowerSaveMode == true
                    Log.d(TAG, "System Power Save Mode changed: isPowerSave=$isSystemPowerSave")
                    reaffirmLocks()
                    updateNotification()
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level >= 0 && scale > 0) {
                        currentBatteryPct = (level * 100 / scale.toFloat()).toInt()
                    }
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    isCurrentlyCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL
                    updateNotification()
                }
            }
        }
    }

    companion object {
        private const val TAG = "CctvForegroundService"
        const val CHANNEL_ID = "cctv_surveillance_channel"
        const val NOTIFICATION_ID = 8801
        const val ACTION_START = "com.example.cctv.START_SERVICE"
        const val ACTION_STOP = "com.example.cctv.STOP_SERVICE"
        var isServiceRunning = false
            private set

        fun isBatteryOptimizationIgnored(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                return pm?.isIgnoringBatteryOptimizations(context.packageName) == true
            }
            return true
        }

        fun requestIgnoreBatteryOptimization(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to launch battery optimization intent", e)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerPowerReceivers()
        startHeartbeat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSurveillanceService()
                return START_NOT_STICKY
            }
            else -> {
                startSurveillanceService()
            }
        }
        return START_STICKY
    }

    private fun registerPowerReceivers() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            }
        }
        registerReceiver(powerReceiver, filter)
    }

    private fun startSurveillanceService() {
        isServiceRunning = true
        acquireWakeLock()
        acquireWifiLock()
        try {
            StorageCompressorService.startService(this)
        } catch (_: Exception) {}

        val notification = buildForegroundNotification()

        // Mandatory startForeground call within 5 seconds on Android 8+
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val hasCameraPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                val hasMicPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

                var fgsType = 0
                if (hasCameraPerm) {
                    fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
                if (hasMicPerm) {
                    fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }

                if (fgsType != 0) {
                    startForeground(NOTIFICATION_ID, notification, fgsType)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.i(TAG, "Foreground surveillance service started successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground error", e)
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (inner: Exception) {
                Log.e(TAG, "Fallback startForeground failed", inner)
            }
        }
    }

    private fun stopSurveillanceService() {
        isServiceRunning = false
        heartbeatJob?.cancel()
        releaseWakeLock()
        releaseWifiLock()
        try {
            unregisterReceiver(powerReceiver)
        } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Heartbeat watchdog running every 5 minutes to renew WakeLock and WifiLock,
     * ensuring streaming stays resilient across vendor task killer cycles.
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                delay(5 * 60 * 1000L) // 5 minutes
                if (isServiceRunning) {
                    reaffirmLocks()
                }
            }
        }
    }

    private fun reaffirmLocks() {
        try {
            if (wakeLock?.isHeld != true) {
                acquireWakeLock()
            }
            if (wifiLock?.isHeld != true) {
                acquireWifiLock()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error reaffirming locks", e)
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "LoveCCTV::CameraStreamingWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.let {
                if (!it.isHeld) {
                    it.acquire(24 * 60 * 60 * 1000L) // 24-hour guard window
                    Log.d(TAG, "PARTIAL_WAKE_LOCK acquired successfully (screen lock protected).")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire PARTIAL_WAKE_LOCK", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock release error", e)
        }
        wakeLock = null
    }

    private fun acquireWifiLock() {
        try {
            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val lockType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wifiManager?.createWifiLock(lockType, "LoveCCTV::StreamingWifiLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            wifiLock?.let {
                if (!it.isHeld) {
                    it.acquire()
                    Log.d(TAG, "WifiLock acquired successfully (prevents Wi-Fi throttling when screen is locked).")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WifiLock", e)
        }
    }

    private fun releaseWifiLock() {
        try {
            wifiLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "WifiLock release error", e)
        }
        wifiLock = null
    }

    private fun updateNotification() {
        if (!isServiceRunning) return
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(NOTIFICATION_ID, buildForegroundNotification())
    }

    private fun buildForegroundNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopServiceIntent = Intent(this, CctvForegroundService::class.java).apply {
            setAction(ACTION_STOP)
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopServiceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isLowPower = (currentBatteryPct <= 40 && !isCurrentlyCharging)
        val statusText = when {
            isCurrentlyCharging -> "Charging ($currentBatteryPct%) • Live Stream Active"
            isLowPower -> "⚡ Battery $currentBatteryPct% [AI Eco Mode Active • 5 FPS low drain]"
            else -> "Battery $currentBatteryPct% • Continuous Stream Guard Active"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CCTV Camera Surveillance Active")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Surveillance", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CCTV Surveillance Active",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background persistence for camera streaming, WebRTC, and AI battery saver."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isServiceRunning = false
        heartbeatJob?.cancel()
        releaseWakeLock()
        releaseWifiLock()
        try {
            unregisterReceiver(powerReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
