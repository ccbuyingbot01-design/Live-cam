package com.example.cctv.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.os.BatteryManager
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.example.cctv.camera.CameraXManager
import com.example.cctv.camera.MotionDetector
import com.example.cctv.camera.SirenPlayer
import com.example.cctv.camera.VideoRecordingManager
import com.example.cctv.data.CctvDevice
import com.example.cctv.data.CctvRepository
import com.example.cctv.data.DeviceMode
import com.example.cctv.data.MotionEvent
import com.example.cctv.service.CctvForegroundService
import com.example.cctv.supabase.SupabaseService
import com.example.cctv.supabase.SupabaseUser
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CctvViewModel(application: Application) : AndroidViewModel(application) {

    val repository = CctvRepository()
    val cameraManager = CameraXManager()
    val sirenPlayer = SirenPlayer()
    val supabaseService = SupabaseService()
    val videoRecordingManager = VideoRecordingManager()

    val deviceMode = repository.deviceMode
    val currentDevice = repository.currentDevice
    val pairedDevices = repository.pairedDevices
    val selectedCameraId = repository.selectedCameraId
    val motionEvents = repository.motionEvents

    private val _supabaseUser = MutableStateFlow<SupabaseUser?>(null)
    val supabaseUser: StateFlow<SupabaseUser?> = _supabaseUser.asStateFlow()

    private val _isSirenRinging = MutableStateFlow(false)
    val isSirenRinging: StateFlow<Boolean> = _isSirenRinging.asStateFlow()

    private val _recentMotionAlert = MutableStateFlow<MotionEvent?>(null)
    val recentMotionAlert: StateFlow<MotionEvent?> = _recentMotionAlert.asStateFlow()

    // ── Video Recording State ──────────────────────────────────────────────
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDurationSeconds = MutableStateFlow(0L)
    val recordingDurationSeconds: StateFlow<Long> = _recordingDurationSeconds.asStateFlow()

    private val _lastSavedVideoUri = MutableStateFlow<Uri?>(null)
    val lastSavedVideoUri: StateFlow<Uri?> = _lastSavedVideoUri.asStateFlow()

    private val _recordingMessage = MutableStateFlow<String?>(null)
    val recordingMessage: StateFlow<String?> = _recordingMessage.asStateFlow()

    // Triggered when a remote viewer sends a start-recording command
    private val _pendingAutoRecord = MutableStateFlow(false)
    val pendingAutoRecord: StateFlow<Boolean> = _pendingAutoRecord.asStateFlow()

    private var recordingTimerJob: Job? = null

    private val motionDetector = MotionDetector(
        sensitivity = currentDevice.value.motionSensitivity,
        onMotionDetected = { intensity, snapshot ->
            onLocalMotionDetected(intensity, snapshot)
        }
    )

    init {
        monitorBatteryState()
        startSupabaseRealtimeLoop()
    }

    // ── Auto-start support ────────────────────────────────────────────────────

    /**
     * Called by CameraPreviewOverlay once the camera is bound.
     * Automatically starts video recording if we're in Camera Sender mode
     * and not already recording.
     */
    fun autoStartRecording(context: Context) {
        if (deviceMode.value == DeviceMode.CAMERA_SENDER && !_isRecording.value) {
            startVideoRecording(context)
        }
    }

    fun setSupabaseUser(user: SupabaseUser) {
        _supabaseUser.value = user
    }

    fun setDeviceMode(mode: DeviceMode) {
        repository.setDeviceMode(mode)
        // Stop recording if switching away from Camera mode
        if (mode != DeviceMode.CAMERA_SENDER && _isRecording.value) {
            stopVideoRecording()
        }
    }

    fun bindCameraToPreview(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ) {
        val dev = currentDevice.value
        motionDetector.updateSensitivity(dev.motionSensitivity)

        cameraManager.startCamera(
            context = context,
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            motionAnalyzer = if (dev.motionGuardEnabled) motionDetector else null,
            useFrontLens = dev.lensFacingFront
        )
    }

    /**
     * Binds camera with VideoCapture recorder support.
     * Use this in CameraModeScreen when video recording is needed.
     */
    fun bindCameraWithRecorder(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ) {
        val dev = currentDevice.value
        videoRecordingManager.startCameraWithRecorder(
            context = context,
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            useFrontLens = dev.lensFacingFront
        )
    }

    // ── Video Recording Control ───────────────────────────────────────────────

    fun startVideoRecording(context: Context) {
        if (_isRecording.value) return
        videoRecordingManager.startRecording(
            context = context,
            onRecordingStarted = {
                _isRecording.value = true
                _recordingMessage.value = null
                startRecordingTimer()
            },
            onRecordingFinished = { uri ->
                _isRecording.value = false
                stopRecordingTimer()
                _lastSavedVideoUri.value = uri
                _recordingMessage.value = if (uri != null) {
                    "Video saved to Movies/LoveCCTV 🎬"
                } else {
                    "Recording failed."
                }
            },
            onError = { err ->
                _isRecording.value = false
                stopRecordingTimer()
                _recordingMessage.value = "Error: $err"
            }
        )
    }

    fun stopVideoRecording() {
        if (!_isRecording.value) return
        videoRecordingManager.stopRecording()
    }

    fun dismissRecordingMessage() {
        _recordingMessage.value = null
    }

    private fun startRecordingTimer() {
        _recordingDurationSeconds.value = 0L
        recordingTimerJob?.cancel()
        recordingTimerJob = viewModelScope.launch {
            while (_isRecording.value) {
                delay(1000L)
                _recordingDurationSeconds.value++
            }
        }
    }

    private fun stopRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        _recordingDurationSeconds.value = 0L
    }

    fun toggleTorch() {
        val newState = !currentDevice.value.torchOn
        cameraManager.toggleTorch(newState)
        repository.updateTorch(newState)
        syncControlToSupabase()
    }

    fun switchCameraLens(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ) {
        val newLensIsFront = !currentDevice.value.lensFacingFront
        repository.updateLensFacing(newLensIsFront)
        cameraManager.switchCameraLens(
            context = context,
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            motionAnalyzer = if (currentDevice.value.motionGuardEnabled) motionDetector else null
        )
        syncControlToSupabase()
    }

    fun toggleMotionGuard(enabled: Boolean) {
        repository.toggleMotionGuard(enabled)
    }

    fun setMotionSensitivity(sensitivity: Float) {
        repository.updateMotionSensitivity(sensitivity)
        motionDetector.updateSensitivity(sensitivity)
    }

    fun toggleStealthMode() {
        val newState = !currentDevice.value.stealthMode
        repository.toggleStealthMode(newState)
    }

    fun toggleNightVision() {
        val newState = !currentDevice.value.nightVision
        repository.toggleNightVision(newState)
        syncControlToSupabase()
    }

    fun toggleSirenAlarm() {
        if (_isSirenRinging.value) {
            sirenPlayer.stopSiren()
            _isSirenRinging.value = false
            repository.setSirenState(false)
        } else {
            sirenPlayer.startSiren(viewModelScope)
            _isSirenRinging.value = true
            repository.setSirenState(true)
        }
        syncControlToSupabase()
    }

    fun dismissRecentMotionAlert() {
        _recentMotionAlert.value = null
    }

    fun startForegroundCctvService(context: Context) {
        val serviceIntent = Intent(context, CctvForegroundService::class.java).apply {
            action = CctvForegroundService.ACTION_START
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    fun stopForegroundCctvService(context: Context) {
        val serviceIntent = Intent(context, CctvForegroundService::class.java).apply {
            action = CctvForegroundService.ACTION_STOP
        }
        context.startService(serviceIntent)
    }

    fun pairNewDevice(code: String): Boolean {
        return repository.pairNewDeviceByCode(code)
    }

    fun selectViewerCamera(id: String) {
        repository.selectCameraDevice(id)
    }

    fun remoteToggleTorch(deviceId: String) {
        repository.remoteToggleTorch(deviceId)
        val user = _supabaseUser.value ?: return
        viewModelScope.launch {
            val dev = pairedDevices.value.find { it.id == deviceId }
            supabaseService.updateRemoteControl(user.username, torchOn = dev?.torchOn)
        }
    }

    fun remoteToggleSiren(deviceId: String) {
        repository.remoteToggleSiren(deviceId)
        val user = _supabaseUser.value ?: return
        viewModelScope.launch {
            val dev = pairedDevices.value.find { it.id == deviceId }
            supabaseService.updateRemoteControl(user.username, sirenActive = dev?.sirenActive)
        }
    }

    fun remoteToggleLens(deviceId: String) {
        repository.remoteToggleLens(deviceId)
        val user = _supabaseUser.value ?: return
        viewModelScope.launch {
            val dev = pairedDevices.value.find { it.id == deviceId }
            supabaseService.updateRemoteControl(user.username, lensFacingFront = dev?.lensFacingFront)
        }
    }

    fun remoteToggleNightVision(deviceId: String) {
        repository.remoteToggleNightVision(deviceId)
        val user = _supabaseUser.value ?: return
        viewModelScope.launch {
            val dev = pairedDevices.value.find { it.id == deviceId }
            supabaseService.updateRemoteControl(user.username, nightVision = dev?.nightVision)
        }
    }

    fun remoteToggleRecording(deviceId: String) {
        repository.remoteToggleRecording(deviceId)
        val user = _supabaseUser.value ?: return
        viewModelScope.launch {
            val dev = pairedDevices.value.find { it.id == deviceId }
            supabaseService.updateRemoteControl(
                username = user.username,
                recordingActive = dev?.isRecording
            )
        }
    }

    private fun syncControlToSupabase() {
        val user = _supabaseUser.value ?: return
        val dev = currentDevice.value
        viewModelScope.launch {
            supabaseService.updateRemoteControl(
                username = user.username,
                torchOn = dev.torchOn,
                sirenActive = dev.sirenActive,
                lensFacingFront = dev.lensFacingFront,
                nightVision = dev.nightVision,
                recordingActive = dev.isRecording
            )
        }
    }

    private fun startSupabaseRealtimeLoop() {
        viewModelScope.launch {
            while (true) {
                try {
                    val user = _supabaseUser.value
                    if (user != null) {
                        if (deviceMode.value == DeviceMode.CAMERA_SENDER) {
                            val dev = currentDevice.value
                            // Publish camera state (including recording flag) to Supabase
                            supabaseService.publishStream(
                                username = user.username,
                                isLive = true,
                                torchOn = dev.torchOn,
                                lensFacingFront = dev.lensFacingFront,
                                sirenActive = dev.sirenActive,
                                nightVision = dev.nightVision,
                                batteryLevel = dev.batteryLevel,
                                frameBase64 = null,
                                recordingActive = _isRecording.value
                            )

                            // Poll for remote recording command from Viewer
                            val stream = supabaseService.fetchStream(user.username)
                            if (stream != null) {
                                val remoteWantsRecording = stream.recordingActive
                                val currentlyRecording = _isRecording.value
                                if (remoteWantsRecording && !currentlyRecording) {
                                    // Viewer commanded START recording — but we need context.
                                    // Mark flag in device; CameraPreviewOverlay will call autoStartRecording.
                                    repository.setRecordingState(true)
                                    _pendingAutoRecord.value = true
                                } else if (!remoteWantsRecording && currentlyRecording) {
                                    // Viewer commanded STOP recording
                                    stopVideoRecording()
                                    repository.setRecordingState(false)
                                }
                            }

                        } else if (deviceMode.value == DeviceMode.VIEWER_MONITOR) {
                            val stream = supabaseService.fetchStream(user.username)
                            if (stream != null) {
                                // Sync remote camera state (including recording flag)
                                val updatedList = pairedDevices.value.map { d ->
                                    if (d.name.contains(user.username, ignoreCase = true) || d.pairingCode.contains(user.username, ignoreCase = true)) {
                                        d.copy(
                                            isOnline = stream.isLive,
                                            torchOn = stream.torchOn,
                                            lensFacingFront = stream.lensFacingFront,
                                            sirenActive = stream.sirenActive,
                                            nightVision = stream.nightVision,
                                            batteryLevel = stream.batteryLevel,
                                            isRecording = stream.recordingActive
                                        )
                                    } else d
                                }
                                repository.updatePairedDevices(updatedList)
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(1200) // Poll/Sync interval
            }
        }
    }

    private fun onLocalMotionDetected(intensity: Float, snapshot: Bitmap?) {
        viewModelScope.launch {
            repository.logMotionEvent(intensity, snapshot)
            val latest = repository.motionEvents.value.firstOrNull()
            _recentMotionAlert.value = latest

            val user = _supabaseUser.value
            if (user != null && latest != null) {
    // In motion detection flow, trigger flash when high intensity
    if (intensity > 0.7f && !currentDevice.value.torchOn) {
        // Activate torch for alert
        triggerFlashOnMotion()
        // Log alert with torchTriggered = true
        supabaseService.logMotionAlert(
            username = user.username,
            deviceId = currentDevice.value.id,
            deviceName = currentDevice.value.name,
            severity = latest.severity.name,
            intensity = intensity,
            description = latest.description,
            snapshotBase64 = latest.snapshotBase64,
            torchTriggered = true
        )
    } else {
        // Regular motion alert without flash
        supabaseService.logMotionAlert(
            username = user.username,
            deviceId = currentDevice.value.id,
            deviceName = currentDevice.value.name,
            severity = latest.severity.name,
            intensity = intensity,
            description = latest.description,
            snapshotBase64 = latest.snapshotBase64
        )
    }
            }

            if (intensity > 0.7f && currentDevice.value.sirenActive) {
                sirenPlayer.startSiren(viewModelScope)
                _isSirenRinging.value = true
            }
        }
    }

    // ── Flash (Torch) on Motion Detection ───────────────────────────────────────
    private var flashJob: Job? = null

    /**
     * Turns on the device torch for a short duration (default 5s) when motion is detected.
     * This is independent of user‑controlled torch state.
     */
    private fun triggerFlashOnMotion(durationMs: Long = 5_000L) {
        // If torch is already on, just reset the timer
        if (currentDevice.value.torchOn) {
            flashJob?.cancel()
            flashJob = viewModelScope.launch {
                delay(durationMs)
                toggleTorch() // turn off
            }
            return
        }
        // Turn on torch
        toggleTorch()
        flashJob?.cancel()
        flashJob = viewModelScope.launch {
            delay(durationMs)
            toggleTorch() // turn off after delay
        }
    }
            private fun monitorBatteryState() {
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus: Intent? = getApplication<Application>().registerReceiver(null, filter)

            batteryStatus?.let { intent ->
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale.toFloat()).toInt() else 85

                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

                repository.updateBatteryStatus(batteryPct, isCharging)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    override fun onCleared() {
        super.onCleared()
        sirenPlayer.stopSiren()
        cameraManager.unbind()
        videoRecordingManager.unbind()
    }
}
