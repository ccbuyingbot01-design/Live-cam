package com.example.cctv.data

import android.graphics.Bitmap
import android.util.Base64
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.UUID

class CctvRepository {

    private val _deviceMode = MutableStateFlow(DeviceMode.CAMERA_SENDER)
    val deviceMode: StateFlow<DeviceMode> = _deviceMode.asStateFlow()

    private val _currentDevice = MutableStateFlow(
        CctvDevice(
            id = "CAM-" + UUID.randomUUID().toString().take(6).uppercase(),
            name = "Love Cam #1 (This Device)",
            pairingCode = "LOVE-" + (1000..9999).random()
        )
    )
    val currentDevice: StateFlow<CctvDevice> = _currentDevice.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<CctvDevice>>(
        listOf(
            CctvDevice(
                id = "CAM-89321A",
                name = "Living Room Security Cam",
                pairingCode = "LOVE-8932",
                isOnline = true,
                isStreaming = true,
                batteryLevel = 92,
                isCharging = true,
                motionGuardEnabled = true
            ),
            CctvDevice(
                id = "CAM-44210B",
                name = "Front Door Love Cam",
                pairingCode = "LOVE-4421",
                isOnline = true,
                isStreaming = true,
                batteryLevel = 68,
                isCharging = false,
                motionGuardEnabled = true
            ),
            CctvDevice(
                id = "CAM-11099C",
                name = "Baby / Pet Cam",
                pairingCode = "LOVE-1109",
                isOnline = false,
                isStreaming = false,
                batteryLevel = 15,
                isCharging = false,
                motionGuardEnabled = false
            )
        )
    )
    val pairedDevices: StateFlow<List<CctvDevice>> = _pairedDevices.asStateFlow()

    private val _selectedCameraId = MutableStateFlow<String>("CAM-89321A")
    val selectedCameraId: StateFlow<String> = _selectedCameraId.asStateFlow()

    private val _motionEvents = MutableStateFlow<List<MotionEvent>>(emptyList())
    val motionEvents: StateFlow<List<MotionEvent>> = _motionEvents.asStateFlow()

    private var firestore: FirebaseFirestore? = null

    init {
        try {
            firestore = FirebaseFirestore.getInstance()
            listenToFirestoreUpdates()
        } catch (e: Exception) {
            // Safe fallback if Firebase is not yet provisioned
            firestore = null
        }
        seedInitialMotionEvents()
    }

    fun setDeviceMode(mode: DeviceMode) {
        _deviceMode.value = mode
    }

    fun updatePairedDevices(list: List<CctvDevice>) {
        _pairedDevices.value = list
    }

    fun selectCameraDevice(id: String) {
        _selectedCameraId.value = id
    }

    fun updateTorch(enabled: Boolean) {
        _currentDevice.value = _currentDevice.value.copy(torchOn = enabled)
        syncDeviceStateToCloud()
    }

    fun updateLensFacing(isFront: Boolean) {
        _currentDevice.value = _currentDevice.value.copy(lensFacingFront = isFront)
        syncDeviceStateToCloud()
    }

    fun updateMotionSensitivity(sensitivity: Float) {
        _currentDevice.value = _currentDevice.value.copy(motionSensitivity = sensitivity)
        syncDeviceStateToCloud()
    }

    fun toggleMotionGuard(enabled: Boolean) {
        _currentDevice.value = _currentDevice.value.copy(motionGuardEnabled = enabled)
        syncDeviceStateToCloud()
    }

    fun toggleStealthMode(enabled: Boolean) {
        _currentDevice.value = _currentDevice.value.copy(stealthMode = enabled)
        syncDeviceStateToCloud()
    }

    fun toggleNightVision(enabled: Boolean) {
        _currentDevice.value = _currentDevice.value.copy(nightVision = enabled)
        syncDeviceStateToCloud()
    }

    fun setSirenState(active: Boolean) {
        _currentDevice.value = _currentDevice.value.copy(sirenActive = active)
        syncDeviceStateToCloud()
    }

    fun setRecordingState(active: Boolean) {
        _currentDevice.value = _currentDevice.value.copy(isRecording = active)
        syncDeviceStateToCloud()
    }

    fun updateBatteryStatus(level: Int, charging: Boolean) {
        _currentDevice.value = _currentDevice.value.copy(
            batteryLevel = level.coerceIn(0, 100),
            isCharging = charging
        )
        syncDeviceStateToCloud()
    }

    fun remoteToggleTorch(deviceId: String) {
        val updated = _pairedDevices.value.map { dev ->
            if (dev.id == deviceId) dev.copy(torchOn = !dev.torchOn) else dev
        }
        _pairedDevices.value = updated
        remoteCommandCloud(deviceId, "toggle_torch")
    }

    fun remoteToggleSiren(deviceId: String) {
        val updated = _pairedDevices.value.map { dev ->
            if (dev.id == deviceId) dev.copy(sirenActive = !dev.sirenActive) else dev
        }
        _pairedDevices.value = updated
        remoteCommandCloud(deviceId, "toggle_siren")
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
        remoteCommandCloud(deviceId, "toggle_night_vision")
    }

    fun remoteToggleRecording(deviceId: String) {
        val updated = _pairedDevices.value.map { dev ->
            if (dev.id == deviceId) dev.copy(isRecording = !dev.isRecording) else dev
        }
        _pairedDevices.value = updated
        val newState = updated.find { it.id == deviceId }?.isRecording ?: false
        remoteCommandCloud(deviceId, if (newState) "start_recording" else "stop_recording")
    }

    fun pairNewDeviceByCode(code: String): Boolean {
        val cleanCode = code.trim().uppercase()
        if (cleanCode.isBlank()) return false

        val newCam = CctvDevice(
            id = "CAM-" + (1000..9999).random(),
            name = "Paired Camera ($cleanCode)",
            pairingCode = cleanCode,
            isOnline = true,
            isStreaming = true,
            batteryLevel = 90
        )
        _pairedDevices.value = _pairedDevices.value + newCam
        _selectedCameraId.value = newCam.id
        return true
    }

    fun logMotionEvent(intensity: Float, snapshot: Bitmap?) {
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
            description = "Motion detected (${(intensity * 100).toInt()}% activity) near camera lens."
        )

        _motionEvents.value = listOf(event) + _motionEvents.value
        _currentDevice.value = dev.copy(
            lastMotionTime = event.timestamp,
            lastMotionIntensity = intensity
        )

        // Upload to Firestore if available
        firestore?.collection("cctv_motion_events")
            ?.document(event.id)
            ?.set(
                mapOf(
                    "id" to event.id,
                    "deviceId" to event.deviceId,
                    "deviceName" to event.deviceName,
                    "timestamp" to event.timestamp,
                    "intensity" to event.intensity,
                    "severity" to event.severity.name,
                    "description" to event.description
                )
            )
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 60, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    private fun syncDeviceStateToCloud() {
        val dev = _currentDevice.value
        firestore?.collection("cctv_devices")
            ?.document(dev.id)
            ?.set(dev)
    }

    private fun remoteCommandCloud(deviceId: String, action: String) {
        firestore?.collection("cctv_commands")
            ?.add(
                mapOf(
                    "deviceId" to deviceId,
                    "action" to action,
                    "timestamp" to System.currentTimeMillis()
                )
            )
    }

    private fun listenToFirestoreUpdates() {
        firestore?.collection("cctv_devices")
            ?.addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                val devices = snapshot.documents.mapNotNull { doc ->
                    try {
                        doc.toObject(CctvDevice::class.java)
                    } catch (e: Exception) {
                        null
                    }
                }
                if (devices.isNotEmpty()) {
                    _pairedDevices.value = devices
                }
            }
    }

    private fun seedInitialMotionEvents() {
        val now = System.currentTimeMillis()
        val seeded = listOf(
            MotionEvent(
                id = "evt_1",
                deviceId = "CAM-89321A",
                deviceName = "Living Room Security Cam",
                timestamp = now - (3 * 60 * 1000),
                intensity = 0.85f,
                severity = MotionSeverity.HIGH,
                description = "Significant movement detected in central lounge area."
            ),
            MotionEvent(
                id = "evt_2",
                deviceId = "CAM-44210B",
                deviceName = "Front Door Love Cam",
                timestamp = now - (25 * 60 * 1000),
                intensity = 0.45f,
                severity = MotionSeverity.MEDIUM,
                description = "Motion activity detected near entrance doorway."
            ),
            MotionEvent(
                id = "evt_3",
                deviceId = "CAM-89321A",
                deviceName = "Living Room Security Cam",
                timestamp = now - (90 * 60 * 1000),
                intensity = 0.20f,
                severity = MotionSeverity.LOW,
                description = "Minor lighting shift / subtle motion registered."
            )
        )
        _motionEvents.value = seeded
    }
}
