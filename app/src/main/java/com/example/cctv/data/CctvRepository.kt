package com.example.cctv.data

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.cctv.supabase.SupabaseService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

class CctvRepository(context: android.content.Context? = null) {

    private val _deviceMode = MutableStateFlow(DeviceMode.CAMERA_SENDER)
    val deviceMode: StateFlow<DeviceMode> = _deviceMode.asStateFlow()

    private val _currentDevice = MutableStateFlow(
        run {
            var devId = "CAM-" + UUID.randomUUID().toString().take(6).uppercase()
            var pairCode = "LOVE-" + (1000..9999).random()
            if (context != null) {
                val prefs = context.getSharedPreferences("cctv_device_identity", android.content.Context.MODE_PRIVATE)
                val savedId = prefs.getString("device_id", null)
                val savedCode = prefs.getString("pairing_code", null)
                if (!savedId.isNullOrBlank() && !savedCode.isNullOrBlank()) {
                    devId = savedId
                    pairCode = savedCode
                } else {
                    prefs.edit()
                        .putString("device_id", devId)
                        .putString("pairing_code", pairCode)
                        .apply()
                }
            }
            CctvDevice(
                id = devId,
                name = "Love Cam (${android.os.Build.MODEL})",
                pairingCode = pairCode,
                ipAddress = getLocalIpAddress()
            )
        }
    )
    val currentDevice: StateFlow<CctvDevice> = _currentDevice.asStateFlow()

    // Start with empty list – devices come from Supabase only
    private val _pairedDevices = MutableStateFlow<List<CctvDevice>>(emptyList())
    val pairedDevices: StateFlow<List<CctvDevice>> = _pairedDevices.asStateFlow()

    private val _selectedCameraId = MutableStateFlow<String>("")
    val selectedCameraId: StateFlow<String> = _selectedCameraId.asStateFlow()

    private val _motionEvents = MutableStateFlow<List<MotionEvent>>(emptyList())
    val motionEvents: StateFlow<List<MotionEvent>> = _motionEvents.asStateFlow()

    // Cloud connection status
    private val _isCloudConnected = MutableStateFlow(false)
    val isCloudConnected: StateFlow<Boolean> = _isCloudConnected.asStateFlow()

    val supabaseService = SupabaseService()

    // Username for Supabase operations (to be set after authentication)
    var username: String? = null
        private set

    fun setDeviceMode(mode: DeviceMode) {
        _deviceMode.value = mode
    }

    fun updatePairedDevices(list: List<CctvDevice>) {
        _pairedDevices.value = list
        // Auto-select first device if none selected
        if (_selectedCameraId.value.isBlank() && list.isNotEmpty()) {
            _selectedCameraId.value = list.first().id
        }
    }

    fun selectCameraDevice(id: String) {
        _selectedCameraId.value = id
    }

    fun updateTorch(enabled: Boolean) {
        _currentDevice.value = _currentDevice.value.copy(torchOn = enabled)
        launchSyncToCloud()
    }

    fun updateLensFacing(isFront: Boolean) {
        _currentDevice.value = _currentDevice.value.copy(lensFacingFront = isFront)
        launchSyncToCloud()
    }

    fun updateMotionSensitivity(sensitivity: Float) {
        _currentDevice.value = _currentDevice.value.copy(motionSensitivity = sensitivity)
    }

    fun toggleMotionGuard(enabled: Boolean) {
        _currentDevice.value = _currentDevice.value.copy(motionGuardEnabled = enabled)
    }

    fun toggleStealthMode(enabled: Boolean) {
        _currentDevice.value = _currentDevice.value.copy(stealthMode = enabled)
    }

    fun toggleNightVision(enabled: Boolean) {
        _currentDevice.value = _currentDevice.value.copy(nightVision = enabled)
        launchSyncToCloud()
    }

    fun setSirenState(active: Boolean) {
        _currentDevice.value = _currentDevice.value.copy(sirenActive = active)
        launchSyncToCloud()
    }

    fun setRecordingState(active: Boolean) {
        _currentDevice.value = _currentDevice.value.copy(isRecording = active)
        launchSyncToCloud()
    }

    fun updateBatteryStatus(level: Int, charging: Boolean, tempCelsius: Float = 28f) {
        val current = _currentDevice.value
        val shouldLowPower = if (current.smartBatterySaverEnabled && !charging) {
            level <= current.lowPowerThreshold
        } else if (charging) {
            false
        } else {
            current.isLowPowerMode
        }
        _currentDevice.value = current.copy(
            batteryLevel = level.coerceIn(0, 100),
            isCharging = charging,
            batteryTempCelsius = tempCelsius,
            isLowPowerMode = shouldLowPower
        )
    }

    fun setLowPowerModeManually(isLowPower: Boolean) {
        _currentDevice.value = _currentDevice.value.copy(isLowPowerMode = isLowPower)
    }

    fun updateSmartBatterySaver(enabled: Boolean, threshold: Int = 40) {
        val current = _currentDevice.value
        val shouldLowPower = if (enabled && !current.isCharging) {
            current.batteryLevel <= threshold
        } else {
            false
        }
        _currentDevice.value = current.copy(
            smartBatterySaverEnabled = enabled,
            lowPowerThreshold = threshold,
            isLowPowerMode = shouldLowPower
        )
    }

    fun setCloudConnected(connected: Boolean) {
        _isCloudConnected.value = connected
    }

    fun updateNotificationPrefs(vibration: Boolean, sound: Boolean) {
        _currentDevice.value = _currentDevice.value.copy(
            notificationVibration = vibration,
            notificationSound = sound
        )
        launchSyncToCloud()
    }

    // ── Remote Control via Cloud ───────────────────────────────────────────

    fun remoteToggleTorch(deviceId: String) {
        val updated = _pairedDevices.value.map { dev ->
            if (dev.id == deviceId) dev.copy(torchOn = !dev.torchOn) else dev
        }
        _pairedDevices.value = updated
        val newState = updated.find { it.id == deviceId }?.torchOn ?: false
        remoteCommandCloud(deviceId, if (newState) "turn_torch_on" else "turn_torch_off")
        CoroutineScope(Dispatchers.IO).launch {
            val user = username ?: return@launch
            supabaseService.updateRemoteControl(user, torchOn = newState)
        }
    }

    fun remoteToggleSiren(deviceId: String) {
        val updated = _pairedDevices.value.map { dev ->
            if (dev.id == deviceId) dev.copy(sirenActive = !dev.sirenActive) else dev
        }
        _pairedDevices.value = updated
        val newState = updated.find { it.id == deviceId }?.sirenActive ?: false
        remoteCommandCloud(deviceId, if (newState) "turn_siren_on" else "turn_siren_off")
    }

    fun remoteToggleLens(deviceId: String) {
        val updated = _pairedDevices.value.map { dev ->
            if (dev.id == deviceId) dev.copy(lensFacingFront = !dev.lensFacingFront) else dev
        }
        _pairedDevices.value = updated
        remoteCommandCloud(deviceId, "toggle_lens")
    }

    fun remoteToggleNightVision(deviceId: String) {
        val updated = _pairedDevices.value.map { dev ->
            if (dev.id == deviceId) dev.copy(nightVision = !dev.nightVision) else dev
        }
        _pairedDevices.value = updated
        val newState = updated.find { it.id == deviceId }?.nightVision ?: false
        remoteCommandCloud(deviceId, if (newState) "turn_night_vision_on" else "turn_night_vision_off")
    }

    fun remoteToggleRecording(deviceId: String) {
        val updated = _pairedDevices.value.map { dev ->
            if (dev.id == deviceId) dev.copy(isRecording = !dev.isRecording) else dev
        }
        _pairedDevices.value = updated
        val newState = updated.find { it.id == deviceId }?.isRecording ?: false
        remoteCommandCloud(deviceId, if (newState) "start_recording" else "stop_recording")
        // Also update stream table for immediate viewer feedback
        CoroutineScope(Dispatchers.IO).launch {
            val user = username ?: return@launch
            supabaseService.updateRemoteControl(user, recordingActive = newState)
        }
    }

    // ── User & Pairing ────────────────────────────────────────────────────

    fun setUsername(user: String) {
        username = user
        // Register this device in Supabase and reset stream controls to OFF state
        val dev = _currentDevice.value
        CoroutineScope(Dispatchers.IO).launch {
            supabaseService.createPairedDevice(
                username = user,
                pairingCode = dev.pairingCode,
                deviceName = dev.name,
                ipAddress = dev.ipAddress
            )
            // Reset remote stream controls on new session start
            supabaseService.publishStream(
                username = user,
                isLive = true,
                torchOn = false,
                lensFacingFront = false,
                sirenActive = false,
                nightVision = false,
                batteryLevel = dev.batteryLevel,
                recordingActive = false
            )
            Log.d("CctvRepository", "Device registered and stream reset for user: $user")
            // Fetch existing paired devices for this user
            refreshPairedDevices()
        }
    }

    fun pairNewDeviceByCode(code: String): Boolean {
        val cleanCode = code.trim().uppercase()
        if (cleanCode.isBlank()) return false
        val user = username ?: return false

        CoroutineScope(Dispatchers.IO).launch {
            // First check if device with this code exists
            val existing = supabaseService.getPairedDeviceByCode(cleanCode)
            if (existing != null) {
                // Device found – add it under our user as well
                supabaseService.createPairedDevice(
                    username = user,
                    pairingCode = cleanCode,
                    deviceName = existing.name,
                    ipAddress = existing.ipAddress
                )
            } else {
                // Register new unknown device
                supabaseService.createPairedDevice(
                    username = user,
                    pairingCode = cleanCode,
                    deviceName = "Paired Camera $cleanCode",
                    ipAddress = ""
                )
            }
            refreshPairedDevices()
        }
        return true
    }

    fun clearPairedDevicesForUser(username: String) {
        CoroutineScope(Dispatchers.IO).launch {
            supabaseService.clearAllPairedDevices(username)
            refreshPairedDevices()
        }
    }

    suspend fun refreshPairedDevices() {
        val user = username ?: return
        val devices = supabaseService.listDevicesForUser(user)
        // Exclude self and deduplicate by pairing code
        val selfCode = _currentDevice.value.pairingCode
        val filtered = devices.filter { it.pairingCode != selfCode }.distinctBy { it.pairingCode }
        Log.d("CctvRepository", "Found ${filtered.size} unique paired devices for user: $user")
        _pairedDevices.value = filtered
        if (_selectedCameraId.value.isBlank() && filtered.isNotEmpty()) {
            _selectedCameraId.value = filtered.first().id
        }
    }

    // ── Motion Events & Heatmap ───────────────────────────────────────────

    private val _dailyMotionPoints = MutableStateFlow<List<MotionPoint>>(emptyList())
    val dailyMotionPoints: StateFlow<List<MotionPoint>> = _dailyMotionPoints.asStateFlow()

    fun logMotionEvent(intensity: Float, snapshot: Bitmap?, xRatio: Float = 0.5f, yRatio: Float = 0.5f) {
        val severity = when {
            intensity > 0.8f -> MotionSeverity.CRITICAL
            intensity > 0.5f -> MotionSeverity.HIGH
            intensity > 0.25f -> MotionSeverity.MEDIUM
            else -> MotionSeverity.LOW
        }

        val base64 = snapshot?.let { bitmapToBase64(it) }
        val dev = _currentDevice.value

        val event = MotionEvent(
            id = UUID.randomUUID().toString(),
            deviceId = dev.id,
            deviceName = dev.name,
            timestamp = System.currentTimeMillis(),
            intensity = intensity,
            severity = severity,
            snapshotBase64 = base64,
            description = "Motion detected (${(intensity * 100).toInt()}% activity) near camera lens.",
            xRatio = xRatio,
            yRatio = yRatio
        )

        _motionEvents.value = listOf(event) + _motionEvents.value
        _currentDevice.value = dev.copy(
            lastMotionTime = event.timestamp,
            lastMotionIntensity = intensity
        )

        val newPoint = MotionPoint(x = xRatio, y = yRatio, intensity = intensity)
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        _dailyMotionPoints.value = (_dailyMotionPoints.value.filter { it.timestamp > cutoff } + newPoint)

        // Upload motion event to Supabase
        CoroutineScope(Dispatchers.IO).launch {
            supabaseService.logMotionAlert(
                username = username ?: return@launch,
                deviceId = dev.id,
                deviceName = dev.name,
                intensity = intensity,
                severity = severity.name,
                description = event.description,
                snapshotBase64 = base64
            )
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 60, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    private fun launchSyncToCloud() {
        CoroutineScope(Dispatchers.IO).launch {
            syncDeviceStateToCloud()
        }
    }

    suspend fun syncDeviceStateToCloud() {
        val dev = _currentDevice.value
        val user = username ?: return
        supabaseService.publishStream(
            username = user,
            isLive = true,
            torchOn = dev.torchOn,
            lensFacingFront = dev.lensFacingFront,
            sirenActive = dev.sirenActive,
            nightVision = dev.nightVision,
            batteryLevel = dev.batteryLevel,
            frameBase64 = null,
            recordingActive = dev.isRecording
        )
    }

    private fun remoteCommandCloud(deviceId: String, action: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val user = username ?: return@launch
            supabaseService.insertCommand(
                username = user,
                deviceId = deviceId,
                action = action
            )
        }
    }

    companion object {
        fun getLocalIpAddress(): String {
            return try {
                NetworkInterface.getNetworkInterfaces()?.toList()
                    ?.flatMap { it.inetAddresses.toList() }
                    ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                    ?.hostAddress ?: "192.168.1.100"
            } catch (e: Exception) {
                "192.168.1.100"
            }
        }
    }
}
