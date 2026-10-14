package com.example.cctv.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.os.BatteryManager
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.example.cctv.camera.CameraXManager
import com.example.cctv.camera.FrameCaptureAnalyzer
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
    val isCloudConnected = repository.isCloudConnected

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

    // ── Remote Stream Frame for Viewer ─────────────────────────────────────
    private val _remoteFrameBase64 = MutableStateFlow<String?>(null)
    val remoteFrameBase64: StateFlow<String?> = _remoteFrameBase64.asStateFlow()

    private val _remoteStreamLive = MutableStateFlow(false)
    val remoteStreamLive: StateFlow<Boolean> = _remoteStreamLive.asStateFlow()

    // Latest captured frame for cloud streaming (camera side)
    private val _latestFrameBase64 = MutableStateFlow<String?>(null)
    val latestFrameBase64: StateFlow<String?> = _latestFrameBase64.asStateFlow()

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

    fun autoStartRecording(context: Context) {
        if (deviceMode.value == DeviceMode.CAMERA_SENDER
            && !_isRecording.value
            && videoRecordingManager.isReady()
        ) {
            startVideoRecording(context)
        }
    }

    fun setSupabaseUser(user: SupabaseUser) {
        _supabaseUser.value = user
        repository.setUsername(user.username)
    }

    fun setDeviceMode(mode: DeviceMode) {
        repository.setDeviceMode(mode)
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
            useFrontLens = dev.lensFacingFront,
            frameAnalyzer = createFrameAnalyzer()
        )
    }

    /**
     * Creates a FrameCaptureAnalyzer that captures JPEG frames
     * and stores them for cloud publishing.
     */
    private fun createFrameAnalyzer(): FrameCaptureAnalyzer {
        return FrameCaptureAnalyzer(
            onFrameCaptured = { base64 ->
                _latestFrameBase64.value = base64
            },
            intervalMs = 1500L,
            jpegQuality = 35
        )
    }

    /** Called to update the frame for cloud streaming. */
    fun updateLatestFrame(base64: String) {
        _latestFrameBase64.value = base64
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
                repository.setRecordingState(true)
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
                repository.setRecordingState(false)
            },
            onError = { err ->
                _isRecording.value = false
                stopRecordingTimer()
                _recordingMessage.value = "Error: $err"
                repository.setRecordingState(false)
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

    // ── Camera Controls ───────────────────────────────────────────────────────

    fun toggleTorch() {
        val newState = !currentDevice.value.torchOn
        cameraManager.toggleTorch(newState)
        repository.updateTorch(newState)
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
    }

    fun dismissRecentMotionAlert() {
        _recentMotionAlert.value = null
    }

    // ── Foreground Service ────────────────────────────────────────────────────

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

    // ── Pairing ───────────────────────────────────────────────────────────────

    fun pairNewDevice(code: String): Boolean {
        return repository.pairNewDeviceByCode(code)
    }

    fun selectViewerCamera(id: String) {
        repository.selectCameraDevice(id)
    }

    // ── Remote Control from Viewer ────────────────────────────────────────────

    fun remoteToggleTorch(deviceId: String) {
        repository.remoteToggleTorch(deviceId)
    }

    fun remoteToggleSiren(deviceId: String) {
        repository.remoteToggleSiren(deviceId)
    }

    fun remoteToggleLens(deviceId: String) {
        repository.remoteToggleLens(deviceId)
    }

    fun remoteToggleNightVision(deviceId: String) {
        repository.remoteToggleNightVision(deviceId)
    }

    fun remoteToggleRecording(deviceId: String) {
        repository.remoteToggleRecording(deviceId)
    }

    // ── Cloud Sync Loop ───────────────────────────────────────────────────────

    private fun startSupabaseRealtimeLoop() {
        viewModelScope.launch {
            while (true) {
                try {
                    val user = _supabaseUser.value
                    if (user != null) {
                        if (deviceMode.value == DeviceMode.CAMERA_SENDER) {
                            // Camera mode: publish stream + poll for commands
                            val dev = currentDevice.value
                            val publishOk = repository.supabaseService.publishStream(
                                username = user.username,
                                isLive = true,
                                torchOn = dev.torchOn,
                                lensFacingFront = dev.lensFacingFront,
                                sirenActive = dev.sirenActive,
                                nightVision = dev.nightVision,
                                batteryLevel = dev.batteryLevel,
                                frameBase64 = _latestFrameBase64.value,
                                recordingActive = _isRecording.value
                            )
                            repository.setCloudConnected(publishOk)

                            // Poll for remote commands from viewer
                            val commands = repository.supabaseService.fetchPendingCommands(dev.id)
                            for (cmd in commands) {
                                handleRemoteCommand(cmd.action)
                                repository.supabaseService.markCommandConsumed(cmd.id)
                            }

                            // Also check if viewer set recording_active via stream
                            val stream = repository.supabaseService.fetchStream(user.username)
                            if (stream != null) {
                                val remoteWantsRecording = stream.recordingActive
                                val currentlyRecording = _isRecording.value
                                if (remoteWantsRecording && !currentlyRecording) {
                                    repository.setRecordingState(true)
                                    _pendingAutoRecord.value = true
                                } else if (!remoteWantsRecording && currentlyRecording) {
                                    stopVideoRecording()
                                    repository.setRecordingState(false)
                                }
                            }

                        } else if (deviceMode.value == DeviceMode.VIEWER_MONITOR) {
                            // Viewer mode: fetch stream from camera user
                            val stream = repository.supabaseService.fetchStream(user.username)
                            if (stream != null) {
                                repository.setCloudConnected(stream.isLive)
                                _remoteStreamLive.value = stream.isLive
                                _remoteFrameBase64.value = stream.frameBase64

                                // Sync remote camera state to paired devices
                                val updatedList = pairedDevices.value.map { d ->
                                    d.copy(
                                        isOnline = stream.isLive,
                                        isStreaming = stream.isLive,
                                        torchOn = stream.torchOn,
                                        lensFacingFront = stream.lensFacingFront,
                                        sirenActive = stream.sirenActive,
                                        nightVision = stream.nightVision,
                                        batteryLevel = stream.batteryLevel,
                                        isRecording = stream.recordingActive
                                    )
                                }
                                repository.updatePairedDevices(updatedList)
                            } else {
                                repository.setCloudConnected(false)
                                _remoteStreamLive.value = false
                            }

                            // Also fetch unread motion alerts
                            val alerts = repository.supabaseService.fetchUnreadMotionAlerts(user.username)
                            if (alerts.isNotEmpty()) {
                                val latest = alerts.first()
                                _recentMotionAlert.value = MotionEvent(
                                    id = latest.id,
                                    deviceId = latest.deviceId,
                                    deviceName = latest.deviceName,
                                    timestamp = latest.timestamp,
                                    intensity = latest.intensity,
                                    severity = try {
                                        com.example.cctv.data.MotionSeverity.valueOf(latest.severity)
                                    } catch (e: Exception) {
                                        com.example.cctv.data.MotionSeverity.LOW
                                    },
                                    snapshotBase64 = latest.snapshotBase64,
                                    description = latest.description
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CctvViewModel", "Sync loop error", e)
                }
                delay(1500) // Poll/Sync interval
            }
        }
    }

    /**
     * Handle incoming remote commands on the camera side.
     */
    private fun handleRemoteCommand(action: String) {
        when (action) {
            "toggle_torch" -> {
                val newState = !currentDevice.value.torchOn
                cameraManager.toggleTorch(newState)
                repository.updateTorch(newState)
            }
            "toggle_siren" -> {
                toggleSirenAlarm()
            }
            "toggle_lens" -> {
                val newFront = !currentDevice.value.lensFacingFront
                repository.updateLensFacing(newFront)
            }
            "toggle_night_vision" -> {
                val newState = !currentDevice.value.nightVision
                repository.toggleNightVision(newState)
            }
            "start_recording" -> {
                repository.setRecordingState(true)
                _pendingAutoRecord.value = true
            }
            "stop_recording" -> {
                stopVideoRecording()
                repository.setRecordingState(false)
            }
        }
    }

    // ── Motion Detection ──────────────────────────────────────────────────────

    private fun onLocalMotionDetected(intensity: Float, snapshot: Bitmap?) {
        viewModelScope.launch {
            repository.logMotionEvent(intensity, snapshot)
            val latest = repository.motionEvents.value.firstOrNull()
            _recentMotionAlert.value = latest

            val user = _supabaseUser.value
            if (user != null && latest != null) {
                if (intensity > 0.7f && !currentDevice.value.torchOn) {
                    triggerFlashOnMotion()
                }

                if (intensity > 0.7f && currentDevice.value.sirenActive) {
                    sirenPlayer.startSiren(viewModelScope)
                    _isSirenRinging.value = true
                }
            }
        }
    }

    // ── Flash (Torch) on Motion Detection ───────────────────────────────────
    private var flashJob: Job? = null

    private fun triggerFlashOnMotion(durationMs: Long = 5_000L) {
        if (currentDevice.value.torchOn) {
            flashJob?.cancel()
            flashJob = viewModelScope.launch {
                delay(durationMs)
                toggleTorch()
            }
            return
        }
        toggleTorch()
        flashJob?.cancel()
        flashJob = viewModelScope.launch {
            delay(durationMs)
            toggleTorch()
        }
    }

    // ── Battery Monitoring ────────────────────────────────────────────────────

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
