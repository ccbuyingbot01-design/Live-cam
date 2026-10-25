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

class CctvRepository {

    private val _deviceMode = MutableStateFlow(DeviceMode.CAMERA_SENDER)
    val deviceMode: StateFlow<DeviceMode> = _deviceMode.asStateFlow()

    private val _currentDevice = MutableStateFlow(
        CctvDevice(
            id = "CAM-" + UUID.randomUUID().toString().take(6).uppercase(),
            name = "Love Cam #1 (This Device)",
            pairingCode = "LOVE-" + (1000..9999).random(),
            ipAddress = getLocalIpAddress()
        )
    )
    val currentDevice: StateFlow<CctvDevice> = _currentDevice.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<CctvDevice>>(emptyList())
    val pairedDevices: StateFlow<List<CctvDevice>> = _pairedDevices.asStateFlow()

    private val _selectedCameraId = MutableStateFlow<String>("")
    val selectedCameraId: StateFlow<String> = _selectedCameraId.asStateFlow()

    private val _motionEvents = MutableStateFlow<List<MotionEvent>>(emptyList())
    val motionEvents: StateFlow<List<MotionEvent>> = _motionEvents.asStateFlow()

    private val _dailyMotionPoints = MutableStateFlow<List<MotionPoint>>(emptyList())
    val dailyMotionPoints: StateFlow<List<MotionPoint>> = _dailyMotionPoints.asStateFlow()

    private val _isCloudConnected = MutableStateFlow(false)
    val isCloudConnected: StateFlow<Boolean> = _isCloudConnected.asStateFlow()

    val supabaseService = SupabaseService()
    var username: String? = null
        private set

    fun setDeviceMode(mode: DeviceMode) { _deviceMode.value = mode }

    fun updatePairedDevices(list: List<CctvDevice>) {
        _pairedDevices.value = list
        if (_selectedCameraId.value.isBlank() && list.isNotEmpty()) {
            _selectedCameraId.value = list.first().id
        }
    }

    fun selectCameraDevice(id: String) { _selectedCameraId.value = id }

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
        _currentDevice.value = current.copy(
            batteryLevel = level.coerceIn(0, 100),
            isCharging = charging,
            batteryTempCelsius = tempCelsius
        )
    }

    // ── Remote Control ───────────────────────────────────────────

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
        CoroutineScope(Dispatchers.IO).launch {
            val user = username ?: return@launch
            supabaseService.updateRemoteControl(user, recordingActive = newState)
        }
    }

    // ── User & Pairing ────────────────────────────────────────────────────

    private fun generateHashedCode(user: String, deviceId: String): String {
        return try {
            val input = "${user.lowercase().trim()}_${deviceId.trim()}"
            val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            val hex = bytes.joinToString("") { "%02X".format(it) }
            "HASH-${hex.take(8)}"
        } catch (e: Exception) { "HASH-" + (100000..999999).random() }
    }

    fun setUsername(user: String) {
        username = user
        val currentDev = _currentDevice.value
        val hashedCode = generateHashedCode(user, currentDev.id)
        _currentDevice.value = currentDev.copy(pairingCode = hashedCode)
        CoroutineScope(Dispatchers.IO).launch {
            supabaseService.cleanUpStalePairedDevices(user, currentDev.name, hashedCode)
            supabaseService.createPairedDevice(user, hashedCode, currentDev.name, currentDev.ipAddress)
            supabaseService.publishStream(user, true, false, false, false, false, currentDev.batteryLevel, false)
            refreshPairedDevices()
        }
    }

    fun pairNewDeviceByCode(code: String): Boolean {
        val cleanCode = code.trim().uppercase()
        val user = username ?: return false
        CoroutineScope(Dispatchers.IO).launch {
            val existing = supabaseService.getPairedDeviceByCode(cleanCode)
            supabaseService.createPairedDevice(user, cleanCode, existing?.name ?: "Paired Camera $cleanCode", existing?.ipAddress ?: "")
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
        val selfCode = _currentDevice.value.pairingCode
        val filtered = devices.filter { it.pairingCode != selfCode }.distinctBy { it.pairingCode }
        _pairedDevices.value = filtered
        if (_selectedCameraId.value.isBlank() && filtered.isNotEmpty()) {
            _selectedCameraId.value = filtered.first().id
        }
    }

    // ── Motion Events & Heatmap ───────────────────────────────────────────

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
            description = "Motion detected (${(intensity * 100).toInt()}% activity) near camera lens.",
            intensity = intensity,
            severity = severity,
            snapshotBase64 = base64,
            xRatio = xRatio,
            yRatio = yRatio
        )
        _motionEvents.value = listOf(event) + _motionEvents.value
        _currentDevice.value = dev.copy(lastMotionTime = event.timestamp, lastMotionIntensity = intensity)
        val newPoint = MotionPoint(xRatio = xRatio, yRatio = yRatio, intensity = intensity)
        _dailyMotionPoints.value = (_dailyMotionPoints.value.filter { it.timestamp > System.currentTimeMillis() - 86400000L } + newPoint)
        CoroutineScope(Dispatchers.IO).launch {
            supabaseService.logMotionAlert(username ?: return@launch, dev.id, dev.name, intensity, severity.name, event.description, base64)
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 60, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    private fun launchSyncToCloud() { CoroutineScope(Dispatchers.IO).launch { syncDeviceStateToCloud() } }

    suspend fun syncDeviceStateToCloud() {
        val dev = _currentDevice.value
        val user = username ?: return
        supabaseService.publishStream(user, true, dev.torchOn, dev.lensFacingFront, dev.sirenActive, dev.nightVision, dev.batteryLevel, dev.isRecording)
    }

    private fun remoteCommandCloud(deviceId: String, action: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val user = username ?: return@launch
            supabaseService.insertCommand(user, deviceId, action)
        }
    }

    companion object {
        fun getLocalIpAddress(): String {
            return try {
                NetworkInterface.getNetworkInterfaces()?.toList()
                    ?.flatMap { it.inetAddresses.toList() }
                    ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                    ?.hostAddress ?: "192.168.1.100"
            } catch (e: Exception) { "192.168.1.100" }
        }
    }
}
