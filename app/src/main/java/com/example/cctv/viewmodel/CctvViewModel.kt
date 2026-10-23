package com.example.cctv.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import com.example.cctv.service.AiBatteryOptimizer
import com.example.cctv.service.BatteryReport
import com.example.cctv.service.StreamingPerformanceProfile
import com.example.cctv.webrtc.LiveKitManager
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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
import com.example.cctv.data.SessionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class CctvViewModel(application: Application) : AndroidViewModel(application) {

    val repository = CctvRepository(application)
    val cameraManager = CameraXManager()
    val sirenPlayer = SirenPlayer()
    val supabaseService = SupabaseService()
    val videoRecordingManager = VideoRecordingManager()
    val sessionManager = SessionManager(application)
    val udpStreamManager = UdpStreamManager()
    val hardwareEncoder = HardwareMediaCodecEncoder(width = 640, height = 480, frameRate = 10)
    val aiBatteryOptimizer = AiBatteryOptimizer(application)

    private val _batteryReport = MutableStateFlow<BatteryReport?>(null)
    val batteryReport: StateFlow<BatteryReport?> = _batteryReport.asStateFlow()

    private var activeFrameAnalyzer: FrameCaptureAnalyzer? = null
    private var batteryReceiver: BroadcastReceiver? = null

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

    val dailyMotionPoints: StateFlow<List<com.example.cctv.data.MotionPoint>> = repository.dailyMotionPoints

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

    fun consumePendingAutoRecord() {
        _pendingAutoRecord.value = false
    }

    // ── AI Human Detection Results ──────────────────────────────────────────
    private val _detectedHumans = MutableStateFlow<List<com.example.cctv.camera.HumanDetectorAnalyzer.HumanDetectionResult>>(emptyList())
    val detectedHumans: StateFlow<List<com.example.cctv.camera.HumanDetectorAnalyzer.HumanDetectionResult>> = _detectedHumans.asStateFlow()

    fun updateDetectedHumans(results: List<com.example.cctv.camera.HumanDetectorAnalyzer.HumanDetectionResult>) {
        _detectedHumans.value = results
    }

    // ── Remote Stream Frame for Viewer ─────────────────────────────────────
    private val _remoteFrameBase64 = MutableStateFlow<String?>(null)
    val remoteFrameBase64: StateFlow<String?> = _remoteFrameBase64.asStateFlow()

    private val _remoteStreamLive = MutableStateFlow(false)
    val remoteStreamLive: StateFlow<Boolean> = _remoteStreamLive.asStateFlow()

    // ── Ultra-Low-Latency P2P/UDP Stream Frame for Viewer (Zero-Buffer-Delay) ──
    private val _liveStreamBitmap = MutableStateFlow<Bitmap?>(null)
    val liveStreamBitmap: StateFlow<Bitmap?> = _liveStreamBitmap.asStateFlow()

    private val _isHardwareEncoding = MutableStateFlow(true)
    val isHardwareEncoding: StateFlow<Boolean> = _isHardwareEncoding.asStateFlow()

    // LiveKit Cloud WebRTC Ultra-Low Latency Engine
    val liveKitManager = LiveKitManager(getApplication())
    val remoteLiveKitTrack: StateFlow<io.livekit.android.room.track.VideoTrack?> = liveKitManager.remoteVideoTrack

    // Latest captured frame for cloud streaming (camera side)
    private val _latestFrameBase64 = MutableStateFlow<String?>(null)
    val latestFrameBase64: StateFlow<String?> = _latestFrameBase64.asStateFlow()

    private var recordingTimerJob: Job? = null

    // Deduplication set to ensure each remote command ID executes EXACTLY ONCE
    private val processedCommandIds = java.util.Collections.synchronizedSet(HashSet<Long>())

    private val motionDetector = MotionDetector(
        sensitivity = currentDevice.value.motionSensitivity,
        onMotionDetected = { intensity, snapshot, x, y ->
            onLocalMotionDetected(intensity, snapshot, x, y)
        }
    )

    init {
        monitorBatteryState()
        initUdpStreaming()
        startSupabaseRealtimeLoop()
        loadSavedSession()
        observeConnectionForAutoRecord()
    }

    private fun initUdpStreaming() {
        udpStreamManager.onNewLiveFrame = { bitmap: Bitmap ->
            _liveStreamBitmap.value = bitmap
            _remoteStreamLive.value = true
        }
        if (deviceMode.value == DeviceMode.VIEWER_MONITOR) {
            udpStreamManager.startReceiver()
        } else {
            udpStreamManager.startSender()
        }
    }

    private fun observeConnectionForAutoRecord() {
        viewModelScope.launch {
            repository.isCloudConnected.collect { connected ->
                if (connected && deviceMode.value == DeviceMode.CAMERA_SENDER) {
                    if (!_isRecording.value) {
                        autoStartRecording(getApplication())
                    }
                }
            }
        }
    }

    private fun loadSavedSession() {
        viewModelScope.launch {
            val username = sessionManager.username.first()
            if (!username.isNullOrBlank()) {
                setSupabaseUser(SupabaseUser(id = "auto_$username", username = username, accessToken = "auto"))
            }
        }
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
        viewModelScope.launch {
            sessionManager.saveUsername(user.username)
        }
        val room = "lovecctv_" + user.username
        if (deviceMode.value == DeviceMode.VIEWER_MONITOR) {
            startLiveKitViewer(room)
        } else {
            startLiveKitSender(room)
        }
    }

    fun setDeviceMode(mode: DeviceMode) {
        repository.setDeviceMode(mode)
        val user = _supabaseUser.value?.username ?: "camera"
        val room = "lovecctv_$user"
        if (mode == DeviceMode.VIEWER_MONITOR) {
            udpStreamManager.stopSender()
            udpStreamManager.startReceiver()
            startLiveKitViewer(room)
        } else {
            udpStreamManager.stopReceiver()
            udpStreamManager.startSender()
            startLiveKitSender(room)
            if (_isHardwareEncoding.value) {
                hardwareEncoder.start()
            }
        }
        if (mode != DeviceMode.CAMERA_SENDER && _isRecording.value) {
            stopVideoRecording()
        }
    }

    fun getRememberedMode(): DeviceMode? {
        val saved = SessionManager.getSavedModeSync(getApplication()) ?: return null
        return try {
            DeviceMode.valueOf(saved)
        } catch (_: Exception) {
            null
        }
    }

    fun getRememberChoice(): Boolean {
        return SessionManager.getRememberChoiceSync(getApplication())
    }

    fun setDeviceModeAndRemember(mode: DeviceMode, remember: Boolean) {
        setDeviceMode(mode)
        SessionManager.saveModeSync(getApplication(), mode.name, remember)
        viewModelScope.launch {
            sessionManager.saveDeviceMode(mode.name, remember)
            val username = _supabaseUser.value?.username
            val deviceId = currentDevice.value.id
            if (!username.isNullOrBlank()) {
                supabaseService.saveDevicePreference(
                    deviceId = deviceId,
                    username = username,
                    selectedMode = mode.name,
                    rememberChoice = remember,
                    autoStartOnBoot = (mode == DeviceMode.CAMERA_SENDER)
                )
            }
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
        udpStreamManager.startSender()
        if (_isHardwareEncoding.value) {
            val started = hardwareEncoder.start()
            if (!started) {
                _isHardwareEncoding.value = false
            }
        }
        videoRecordingManager.startCameraWithRecorder(
            context = context,
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            useFrontLens = dev.lensFacingFront,
            frameAnalyzer = createFrameAnalyzer()
        )
    }

    /**
     * Creates a FrameCaptureAnalyzer strictly locked to 15 FPS (66ms) at 480p/360p.
     * Offloads raw NV21 frames to native MediaCodec hardware encoder and
     * streams lightweight compressed frames directly peer-to-peer over UDP.
     */
    private fun createFrameAnalyzer(): FrameCaptureAnalyzer {
        val initialProfile = aiBatteryOptimizer.getStreamingProfile(
            isLowPower = currentDevice.value.isLowPowerMode,
            hasRecentMotion = false
        )
        val analyzer = FrameCaptureAnalyzer(
            onFrameCaptured = { base64 ->
                _latestFrameBase64.value = base64
            },
            onCompressedBytesCaptured = { bytes ->
                udpStreamManager.sendFrame(bytes, "255.255.255.255", UdpStreamManager.DEFAULT_PORT)
                val devIp = currentDevice.value.ipAddress
                if (devIp.isNotBlank() && devIp != "0.0.0.0") {
                    udpStreamManager.sendFrame(bytes, devIp, UdpStreamManager.DEFAULT_PORT)
                }
            },
            onRawNv21Captured = { nv21, _, _ ->
                if (_isHardwareEncoding.value) {
                    hardwareEncoder.encodeFrame(nv21)
                }
            },
            onBitmapCaptured = { bmp ->
                liveKitManager.pushBitmapFrame(bmp)
            },
            onHumanDetected = { results ->
                _detectedHumans.value = results
            },
            intervalMs = (1000L / initialProfile.targetFps).coerceAtLeast(33L),
            jpegQuality = initialProfile.jpegQuality,
            maxDimension = initialProfile.maxDimension
        )
        activeFrameAnalyzer = analyzer
        return analyzer
    }

    fun applyAdaptiveStreamingProfile() {
        val dev = currentDevice.value
        val hasRecentMotion = (System.currentTimeMillis() - dev.lastMotionTime) < 15000L
        val profile = aiBatteryOptimizer.getStreamingProfile(dev.isLowPowerMode, hasRecentMotion)
        activeFrameAnalyzer?.updatePerformanceProfile(
            targetFps = profile.targetFps,
            targetQuality = profile.jpegQuality,
            targetMaxDim = profile.maxDimension
        )
    }

    fun updateLatestFrame(base64: String) {
        _latestFrameBase64.value = base64
    }

    fun startLiveKitSender(roomName: String = "lovecctv_" + (_supabaseUser.value?.username ?: "camera")) {
        viewModelScope.launch {
            liveKitManager.connectAsSender(
                roomName = roomName,
                identity = "camera_" + currentDevice.value.id
            )
        }
    }

    fun startLiveKitViewer(roomName: String = "lovecctv_" + (_supabaseUser.value?.username ?: "camera")) {
        viewModelScope.launch {
            liveKitManager.connectAsViewer(
                roomName = roomName,
                identity = "viewer_mobile_app",
                scope = viewModelScope
            )
        }
    }

    fun clearPairedDevicesForUser(username: String) {
        repository.clearPairedDevicesForUser(username)
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

    fun toggleTorch(context: Context? = null) {
        val newState = !currentDevice.value.torchOn
        val appCtx = context ?: getApplication()
        repository.updateTorch(newState)

        // 1. Primary: toggle on active videoRecordingManager camera
        var handled = videoRecordingManager.toggleTorch(newState, appCtx)

        // 2. Secondary: cameraManager
        if (!handled) {
            cameraManager.toggleTorch(newState, appCtx)
        }

        // 3. Fallback: system CameraManager
        try {
            val sysCameraManager = appCtx.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
            val cameraId = sysCameraManager?.cameraIdList?.firstOrNull { id ->
                val chars = sysCameraManager.getCameraCharacteristics(id)
                chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: "0"
            if (sysCameraManager != null) {
                sysCameraManager.setTorchMode(cameraId, newState)
            }
        } catch (e: Exception) {
            Log.d("CctvViewModel", "System CameraManager fallback error", e)
        }
    }

    fun toggleCameraLens() {
        val newLensIsFront = !currentDevice.value.lensFacingFront
        repository.updateLensFacing(newLensIsFront)
    }

    fun switchCameraLens(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView? = null
    ) {
        val newLensIsFront = !currentDevice.value.lensFacingFront
        repository.updateLensFacing(newLensIsFront)
        if (previewView != null) {
            cameraManager.switchCameraLens(
                context = context,
                lifecycleOwner = lifecycleOwner,
                previewView = previewView,
                motionAnalyzer = if (currentDevice.value.motionGuardEnabled) motionDetector else null
            )
        }
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

    fun toggleSirenAlarm(context: Context? = null) {
        val appCtx = context ?: getApplication()
        if (_isSirenRinging.value) {
            sirenPlayer.stopSiren()
            _isSirenRinging.value = false
            repository.setSirenState(false)
        } else {
            sirenPlayer.startSiren(viewModelScope, appCtx)
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
            var lastCloudPublishTime = 0L
            while (true) {
                try {
                    val user = _supabaseUser.value
                    if (user != null) {
                        val now = System.currentTimeMillis()
                        if (deviceMode.value == DeviceMode.CAMERA_SENDER) {
                            val dev = currentDevice.value

                            // Ensure LiveKit Cloud WebRTC Sender connection is active
                            if (!liveKitManager.isConnectedOrConnecting()) {
                                startLiveKitSender("lovecctv_${user.username}")
                            }

                            // Publish fast lightweight metadata to Supabase (isLive, torch, siren, battery)
                            val syncInterval = if (dev.isLowPowerMode) 3500L else 1500L
                            if (now - lastCloudPublishTime >= syncInterval) {
                                lastCloudPublishTime = now
                                val publishOk = repository.supabaseService.publishStream(
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
                                repository.setCloudConnected(publishOk)
                            }

                            // Rapidly poll for remote commands from viewer (200ms response time)
                            val commands = repository.supabaseService.fetchPendingCommands(user.username, dev.id)
                            for (cmd in commands) {
                                if (processedCommandIds.add(cmd.id)) {
                                    Log.d("CctvViewModel", "Executing single remote command #${cmd.id}: ${cmd.action}")
                                    handleRemoteCommand(cmd.action)
                                    repository.supabaseService.markCommandConsumed(cmd.id)
                                }
                            }

                        } else if (deviceMode.value == DeviceMode.VIEWER_MONITOR) {
                            // Ensure LiveKit Cloud WebRTC Viewer connection is active
                            if (!liveKitManager.isConnectedOrConnecting()) {
                                startLiveKitViewer("lovecctv_${user.username}")
                            }

                            // Viewer mode: fetch stream metadata from camera user
                            val stream = repository.supabaseService.fetchStream(user.username)
                            if (stream != null) {
                                repository.setCloudConnected(stream.isLive)
                                _remoteStreamLive.value = stream.isLive
                                if (_liveStreamBitmap.value == null) {
                                    // Fallback to cloud frame only if direct P2P UDP frame is not received yet
                                    _remoteFrameBase64.value = stream.frameBase64
                                }

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
                delay(100) // Poll/Sync interval
            }
        }
    }

    /**
     * Handle incoming remote commands on the camera side.
     */
    private fun handleRemoteCommand(action: String) {
        Log.d("CctvViewModel", "Handling remote command: $action")
        val appCtx = getApplication<Application>()
        when (action) {
            "turn_torch_on" -> {
                repository.updateTorch(true)
                cameraManager.toggleTorch(true, appCtx)
                videoRecordingManager.toggleTorch(true, appCtx)
            }
            "turn_torch_off" -> {
                repository.updateTorch(false)
                cameraManager.toggleTorch(false, appCtx)
                videoRecordingManager.toggleTorch(false, appCtx)
            }
            "toggle_torch" -> {
                val newState = !currentDevice.value.torchOn
                repository.updateTorch(newState)
                cameraManager.toggleTorch(newState, appCtx)
                videoRecordingManager.toggleTorch(newState, appCtx)
            }
            "turn_siren_on" -> {
                sirenPlayer.startSiren(viewModelScope, appCtx)
                _isSirenRinging.value = true
                repository.setSirenState(true)
            }
            "turn_siren_off" -> {
                sirenPlayer.stopSiren()
                _isSirenRinging.value = false
                repository.setSirenState(false)
            }
            "toggle_siren" -> {
                toggleSirenAlarm(appCtx)
            }
            "toggle_lens" -> {
                val newFront = !currentDevice.value.lensFacingFront
                repository.updateLensFacing(newFront)
            }
            "turn_night_vision_on" -> {
                repository.toggleNightVision(true)
            }
            "turn_night_vision_off" -> {
                repository.toggleNightVision(false)
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

    private fun onLocalMotionDetected(intensity: Float, snapshot: Bitmap?, xRatio: Float = 0.5f, yRatio: Float = 0.5f) {
        viewModelScope.launch {
            repository.logMotionEvent(intensity, snapshot, xRatio, yRatio)
            val latest = repository.motionEvents.value.firstOrNull()
            _recentMotionAlert.value = latest
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

    // ── AI Smart Battery Monitoring ──────────────────────────────────────────

    private fun monitorBatteryState() {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }

            batteryReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale.toFloat()).toInt() else 85

                        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                status == BatteryManager.BATTERY_STATUS_FULL

                        val tempRaw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 280)
                        val tempCelsius = tempRaw / 10.0f
                        val voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 3850)

                        handleBatteryUpdate(batteryPct, isCharging, tempCelsius, voltageMv)
                    }
                }
            }

            getApplication<Application>().registerReceiver(batteryReceiver, filter)
        } catch (e: Exception) {
            Log.e("CctvViewModel", "Error monitoring battery state", e)
        }
    }

    private fun handleBatteryUpdate(level: Int, charging: Boolean, tempCelsius: Float, voltageMv: Int) {
        val prevLowPower = currentDevice.value.isLowPowerMode
        repository.updateBatteryStatus(level, charging, tempCelsius)
        val dev = currentDevice.value

        val report = aiBatteryOptimizer.computeReport(
            batteryPct = level,
            isCharging = charging,
            smartSaverEnabled = dev.smartBatterySaverEnabled,
            lowPowerThreshold = dev.lowPowerThreshold,
            hasRecentMotion = (System.currentTimeMillis() - dev.lastMotionTime) < 15000L,
            tempCelsius = tempCelsius,
            voltageMv = voltageMv
        )
        _batteryReport.value = report

        if (dev.isLowPowerMode != prevLowPower) {
            Log.i("CctvViewModel", "AI Power Mode Transition: isLowPower=${dev.isLowPowerMode} (battery: $level%)")
            applyAdaptiveStreamingProfile()
            if (dev.isLowPowerMode && dev.torchOn) {
                toggleTorch() // Auto-shutoff torch on low battery to prevent thermal stress and rapid drain
            }
        }
    }

    fun toggleSmartBatterySaver(enabled: Boolean) {
        repository.updateSmartBatterySaver(enabled, currentDevice.value.lowPowerThreshold)
        applyAdaptiveStreamingProfile()
    }

    fun setLowPowerThreshold(threshold: Int) {
        repository.updateSmartBatterySaver(currentDevice.value.smartBatterySaverEnabled, threshold)
        applyAdaptiveStreamingProfile()
    }

    fun toggleManualLowPowerMode() {
        val newLowPower = !currentDevice.value.isLowPowerMode
        repository.setLowPowerModeManually(newLowPower)
        applyAdaptiveStreamingProfile()
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        return com.example.cctv.service.CctvForegroundService.isBatteryOptimizationIgnored(context)
    }

    fun requestIgnoreBatteryOptimization(context: Context) {
        com.example.cctv.service.CctvForegroundService.requestIgnoreBatteryOptimization(context)
    }

    override fun onCleared() {
        super.onCleared()
        try {
            batteryReceiver?.let { getApplication<Application>().unregisterReceiver(it) }
        } catch (_: Exception) {}
        sirenPlayer.stopSiren()
        cameraManager.unbind()
        videoRecordingManager.unbind()
        udpStreamManager.release()
        hardwareEncoder.stop()
    }
}

// ── UDP Stream Manager (Zero-Latency P2P Transport) ─────────────────────────
class UdpStreamManager {

    companion object {
        private const val TAG = "UdpStreamManager"
        const val DEFAULT_PORT = 5005
        private const val MAX_PACKET_SIZE = 1400
        private const val MAGIC_HEADER = 0x43435456 // "CCTV"
        private const val HEADER_SIZE = 20
        private const val PAYLOAD_SIZE = MAX_PACKET_SIZE - HEADER_SIZE
    }

    private var sendSocket: DatagramSocket? = null
    private var receiveSocket: DatagramSocket? = null
    private var receiverJob: Job? = null
    private var senderScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var frameCounter = 0
    private val latestReceivedTimestamp = AtomicLong(0L)
    private val latestLiveFrame = AtomicReference<Bitmap?>(null)
    private val assemblingFrames = ConcurrentHashMap<Int, FrameAssembly>()

    var onNewLiveFrame: ((Bitmap) -> Unit)? = null

    private data class FrameAssembly(
        val frameId: Int,
        val timestamp: Long,
        val totalChunks: Int,
        val chunks: MutableMap<Int, ByteArray> = ConcurrentHashMap()
    )

    fun startSender() {
        try {
            if (sendSocket == null || sendSocket?.isClosed == true) {
                sendSocket = DatagramSocket()
            }
            Log.i(TAG, "UDP Sender socket started.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting UDP sender", e)
        }
    }

    fun sendFrame(frameBytes: ByteArray, targetIp: String, targetPort: Int = DEFAULT_PORT) {
        if (sendSocket == null || targetIp.isBlank()) return

        senderScope.launch {
            try {
                val address = InetAddress.getByName(targetIp)
                val currentFrameId = ++frameCounter
                val timestamp = System.currentTimeMillis()
                val totalChunks = ((frameBytes.size + PAYLOAD_SIZE - 1) / PAYLOAD_SIZE)

                for (chunkIndex in 0 until totalChunks) {
                    val offset = chunkIndex * PAYLOAD_SIZE
                    val length = minOf(PAYLOAD_SIZE, frameBytes.size - offset)

                    val buffer = ByteBuffer.allocate(HEADER_SIZE + length)
                    buffer.putInt(MAGIC_HEADER)
                    buffer.putLong(timestamp)
                    buffer.putInt(currentFrameId)
                    buffer.putShort(totalChunks.toShort())
                    buffer.putShort(chunkIndex.toShort())
                    buffer.put(frameBytes, offset, length)

                    val packetData = buffer.array()
                    val packet = DatagramPacket(packetData, packetData.size, address, targetPort)
                    sendSocket?.send(packet)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send UDP frame", e)
            }
        }
    }

    fun startReceiver(listenPort: Int = DEFAULT_PORT) {
        stopReceiver()
        receiverJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                receiveSocket = DatagramSocket(listenPort)
                val buffer = ByteArray(MAX_PACKET_SIZE)
                Log.i(TAG, "UDP Receiver listening on port $listenPort with zero-latency buffer clearing.")

                while (isActive && receiveSocket?.isClosed == false) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    receiveSocket?.receive(packet)

                    if (packet.length < HEADER_SIZE) continue

                    val byteBuf = ByteBuffer.wrap(packet.data, 0, packet.length)
                    val magic = byteBuf.int
                    if (magic != MAGIC_HEADER) continue

                    val timestamp = byteBuf.long
                    val frameId = byteBuf.int
                    val totalChunks = byteBuf.short.toInt()
                    val chunkIndex = byteBuf.short.toInt()

                    val payload = ByteArray(packet.length - HEADER_SIZE)
                    byteBuf.get(payload)

                    val lastTs = latestReceivedTimestamp.get()
                    if (timestamp < lastTs) {
                        continue
                    }

                    if (timestamp > lastTs) {
                        latestReceivedTimestamp.set(timestamp)
                        // EXPLICIT BUFFER CLEARING: Discard any previous bitmap data immediately
                        latestLiveFrame.set(null)
                        assemblingFrames.keys.filter { it < frameId }.forEach {
                            assemblingFrames.remove(it)
                        }
                    }

                    val assembly = assemblingFrames.computeIfAbsent(frameId) {
                        FrameAssembly(frameId, timestamp, totalChunks)
                    }
                    assembly.chunks[chunkIndex] = payload

                    if (assembly.chunks.size == totalChunks) {
                        assemblingFrames.remove(frameId)
                        val fullFrameBytes = ByteArrayOutputStream()
                        for (i in 0 until totalChunks) {
                            val chunk = assembly.chunks[i]
                            if (chunk != null) fullFrameBytes.write(chunk)
                        }
                        val completeBytes = fullFrameBytes.toByteArray()
                        val bitmap = BitmapFactory.decodeByteArray(completeBytes, 0, completeBytes.size)
                        if (bitmap != null) {
                            latestLiveFrame.set(bitmap)
                            withContext(Dispatchers.Main) {
                                onNewLiveFrame?.invoke(bitmap)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "UDP Receiver error", e)
                }
            }
        }
    }

    fun stopReceiver() {
        receiverJob?.cancel()
        receiverJob = null
        try {
            receiveSocket?.close()
        } catch (_: Exception) {}
        receiveSocket = null
        assemblingFrames.clear()
    }

    fun stopSender() {
        try {
            sendSocket?.close()
        } catch (_: Exception) {}
        sendSocket = null
    }

    fun release() {
        stopSender()
        stopReceiver()
    }
}

// ── Hardware MediaCodec Encoder ──────────────────────────────────────────────
class HardwareMediaCodecEncoder(
    val width: Int = 640,
    val height: Int = 480,
    val frameRate: Int = 15,
    val bitRate: Int = 500_000
) {

    companion object {
        private const val TAG = "HardwareMediaCodec"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val I_FRAME_INTERVAL = 1
        private const val TIMEOUT_US = 10_000L
    }

    private var mediaCodec: MediaCodec? = null
    private var isEncoderRunning = false
    private var colorFormat: Int = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
    private var frameIndex = 0L

    var onEncodedPacket: ((ByteBuffer, MediaCodec.BufferInfo) -> Unit)? = null

    fun start(): Boolean {
        return try {
            val codecInfo = selectHardwareCodec(MIME_TYPE)
            val codecName = codecInfo?.name

            Log.i(TAG, "Selected H.264 Encoder: $codecName (hardware accelerated = ${codecInfo?.isHardwareAcceleratedCompatible()})")

            mediaCodec = if (codecName != null) {
                MediaCodec.createByCodecName(codecName)
            } else {
                MediaCodec.createEncoderByType(MIME_TYPE)
            }

            colorFormat = selectSupportedColorFormat(codecInfo)

            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
                try {
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    )
                } catch (_: Exception) {
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                    )
                }
            }

            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec?.start()
            isEncoderRunning = true
            Log.i(TAG, "Hardware MediaCodec successfully started at ${width}x${height} @ ${frameRate} FPS")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Hardware MediaCodec", e)
            stop()
            false
        }
    }

    fun encodeFrame(yuvData: ByteArray, presentationTimeUs: Long = computePresentationTimeUs()) {
        if (!isEncoderRunning || mediaCodec == null) return

        try {
            val inputBufferIndex = mediaCodec?.dequeueInputBuffer(TIMEOUT_US) ?: -1
            if (inputBufferIndex >= 0) {
                val inputBuffer = mediaCodec?.getInputBuffer(inputBufferIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    val bytesToCopy = minOf(yuvData.size, inputBuffer.remaining())
                    inputBuffer.put(yuvData, 0, bytesToCopy)
                    mediaCodec?.queueInputBuffer(
                        inputBufferIndex,
                        0,
                        bytesToCopy,
                        presentationTimeUs,
                        0
                    )
                }
            }

            drainEncoder()
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding frame with MediaCodec", e)
        }
    }

    private fun drainEncoder() {
        val codec = mediaCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        var loopCount = 0
        while (isEncoderRunning && loopCount < 5) {
            loopCount++
            try {
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break
                } else if (outputBufferIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        onEncodedPacket?.invoke(outputBuffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                } else {
                    break
                }
            } catch (e: Exception) {
                break
            }
        }
    }

    private fun computePresentationTimeUs(): Long {
        return (frameIndex++ * 1_000_000L) / frameRate
    }

    fun stop() {
        isEncoderRunning = false
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaCodec", e)
        } finally {
            mediaCodec = null
        }
    }

    private fun selectHardwareCodec(mimeType: String): MediaCodecInfo? {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        val codecs = codecList.codecInfos.filter { it.isEncoder }

        val hwCodec = codecs.find { info ->
            info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) } &&
                    info.isHardwareAcceleratedCompatible()
        }
        if (hwCodec != null) return hwCodec

        return codecs.find { info ->
            info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
        }
    }

    private fun MediaCodecInfo.isHardwareAcceleratedCompatible(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            this.isHardwareAccelerated
        } else {
            val lower = this.name.lowercase()
            !lower.startsWith("omx.google.") && !lower.startsWith("c2.android.")
        }
    }

    private fun selectSupportedColorFormat(codecInfo: MediaCodecInfo?): Int {
        if (codecInfo == null) return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        return try {
            val capabilities = codecInfo.getCapabilitiesForType(MIME_TYPE)
            val formats = capabilities.colorFormats
            when {
                formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                else -> formats.firstOrNull() ?: MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            }
        } catch (e: Exception) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        }
    }
}
