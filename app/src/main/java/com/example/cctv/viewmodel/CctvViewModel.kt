package com.example.cctv.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.example.cctv.data.AiChatMessage
import com.example.cctv.data.AiDetectionFeedItem
import com.example.cctv.data.AiThreatAssessment
import com.example.cctv.data.CctvRepository
import com.example.cctv.data.DetectedHuman
import com.example.cctv.data.DeviceMode
import com.example.cctv.data.MotionEvent
import com.example.cctv.data.MotionPoint
import com.example.cctv.supabase.SupabaseService
import com.example.cctv.supabase.SupabaseUser
import com.example.cctv.webrtc.LiveKitManager
import com.example.cctv.webrtc.LiveKitTokenGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class CctvViewModel(application: Application) : AndroidViewModel(application) {

    val repository = CctvRepository()
    val supabaseService = SupabaseService()

    val deviceMode = repository.deviceMode
    val currentDevice = repository.currentDevice
    val pairedDevices = repository.pairedDevices
    val selectedCameraId = repository.selectedCameraId
    val isCloudConnected = repository.isCloudConnected

    private val _supabaseUser = MutableStateFlow<SupabaseUser?>(null)
    val supabaseUser: StateFlow<SupabaseUser?> = _supabaseUser.asStateFlow()

    val liveKitManager = LiveKitManager(getApplication())
    val remoteLiveKitTrack: StateFlow<io.livekit.android.room.track.VideoTrack?> = liveKitManager.remoteVideoTrack
    val liveKitReconnecting: StateFlow<Boolean> = liveKitManager.isReconnecting
    val liveKitHealthStatus: StateFlow<String> = liveKitManager.streamHealthStatus
    val liveKitReconnectAttempt: StateFlow<Int> = liveKitManager.reconnectAttempt
    val liveKitConnectionLogs: StateFlow<List<String>> = liveKitManager.connectionLogs
    val isMicrophoneMuted: StateFlow<Boolean> = liveKitManager.isMicrophoneMuted

    // State Flows for UI
    private val _liveStreamBitmap = MutableStateFlow<Bitmap?>(null)
    val liveStreamBitmap: StateFlow<Bitmap?> = _liveStreamBitmap.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDurationSeconds = MutableStateFlow(0)
    val recordingDurationSeconds: StateFlow<Int> = _recordingDurationSeconds.asStateFlow()

    private val _pendingAutoRecord = MutableStateFlow(false)
    val pendingAutoRecord: StateFlow<Boolean> = _pendingAutoRecord.asStateFlow()

    private val _detectedHumans = MutableStateFlow<List<DetectedHuman>>(emptyList())
    val detectedHumans: StateFlow<List<DetectedHuman>> = _detectedHumans.asStateFlow()

    private val _dailyMotionPoints = MutableStateFlow<List<MotionPoint>>(emptyList())
    val dailyMotionPoints: StateFlow<List<MotionPoint>> = _dailyMotionPoints.asStateFlow()

    private val _motionEvents = MutableStateFlow<List<MotionEvent>>(emptyList())
    val motionEvents: StateFlow<List<MotionEvent>> = _motionEvents.asStateFlow()

    private val _recentMotionAlert = MutableStateFlow<String?>(null)
    val recentMotionAlert: StateFlow<String?> = _recentMotionAlert.asStateFlow()

    private val _aiDetectionFeeds = MutableStateFlow<List<AiDetectionFeedItem>>(
        listOf(
            AiDetectionFeedItem(title = "AI Vision", detail = "Engine Active", category = "human"),
            AiDetectionFeedItem(title = "LiveKit Cloud", detail = "2-Way WebRTC Stream Connected", category = "motion")
        )
    )
    val aiDetectionFeeds: StateFlow<List<AiDetectionFeedItem>> = _aiDetectionFeeds.asStateFlow()

    private val _aiThreatAssessment = MutableStateFlow<AiThreatAssessment>(AiThreatAssessment())
    val aiThreatAssessment: StateFlow<AiThreatAssessment> = _aiThreatAssessment.asStateFlow()

    private val _aiChatMessages = MutableStateFlow<List<AiChatMessage>>(emptyList())
    val aiChatMessages: StateFlow<List<AiChatMessage>> = _aiChatMessages.asStateFlow()

    private val _isCopilotThinking = MutableStateFlow(false)
    val isCopilotThinking: StateFlow<Boolean> = _isCopilotThinking.asStateFlow()

    private val _isAnalyzingGemini = MutableStateFlow(false)
    val isAnalyzingGemini: StateFlow<Boolean> = _isAnalyzingGemini.asStateFlow()

    // Pure internet network indicator
    private val _isWifiNetwork = MutableStateFlow(true)
    val isWifiNetwork: StateFlow<Boolean> = _isWifiNetwork.asStateFlow()

    private val _networkType = MutableStateFlow("Internet Online")
    val networkType: StateFlow<String> = _networkType.asStateFlow()

    fun setSupabaseUser(user: SupabaseUser) {
        _supabaseUser.value = user
        repository.setUsername(user.username)
        val roomName = getSanitizedRoomName(user.username)
        if (deviceMode.value == DeviceMode.VIEWER_MONITOR) {
            startLiveKitViewer(roomName)
        } else {
            startLiveKitSender(roomName)
        }
    }

    fun setDeviceMode(mode: DeviceMode) {
        repository.setDeviceMode(mode)
        val user = _supabaseUser.value?.username ?: "camera"
        val roomName = getSanitizedRoomName(user)
        if (mode == DeviceMode.VIEWER_MONITOR) {
            startLiveKitViewer(roomName)
        } else {
            startLiveKitSender(roomName)
        }
    }

    fun setDeviceModeAndRemember(mode: DeviceMode, remember: Boolean) {
        setDeviceMode(mode)
    }

    fun getRememberChoice(): Boolean = false

    fun selectCamera(id: String) {
        repository.selectCameraDevice(id)
    }

    fun pairNewDevice(code: String): Boolean {
        return repository.pairNewDeviceByCode(code)
    }

    fun getSanitizedRoomName(username: String? = null): String {
        val u = username ?: _supabaseUser.value?.username ?: "camera"
        val clean = u.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
        val finalUser = if (clean.isBlank()) "camera" else clean
        return "lovecctv_$finalUser"
    }

    fun startLiveKitSender(roomName: String) {
        viewModelScope.launch {
            val room = LiveKitTokenGenerator.toLiveKitRoomName(roomName)
            val identity = LiveKitTokenGenerator.makeParticipantIdentity("camera_${currentDevice.value.id}")
            liveKitManager.connectAsSender(
                roomName = room,
                identity = identity,
                scope = viewModelScope
            )
        }
    }

    fun startLiveKitViewer(roomName: String) {
        viewModelScope.launch {
            val room = LiveKitTokenGenerator.toLiveKitRoomName(roomName)
            val identity = LiveKitTokenGenerator.makeParticipantIdentity("viewer")
            liveKitManager.connectAsViewer(
                roomName = room,
                identity = identity,
                scope = viewModelScope
            )
        }
    }

    fun forceReconnectLiveKit() {
        liveKitManager.forceReconnectNow(viewModelScope)
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        liveKitManager.setMicrophoneEnabled(enabled)
    }

    // ── Local & Remote Camera Controls ────────────────────────────────────

    fun toggleTorchLocal() {
        val current = currentDevice.value
        repository.updateTorch(!current.torchOn)
    }

    fun toggleNightVisionLocal() {
        val current = currentDevice.value
        repository.toggleNightVision(!current.nightVision)
    }

    fun toggleLensFacing() {
        val current = currentDevice.value
        repository.updateLensFacing(!current.lensFacingFront)
    }

    fun toggleSirenLocal() {
        val current = currentDevice.value
        repository.setSirenState(!current.sirenActive)
    }

    fun toggleTorchRemote(deviceId: String) {
        repository.remoteToggleTorch(deviceId)
    }

    fun toggleSirenRemote(deviceId: String) {
        repository.remoteToggleSiren(deviceId)
    }

    fun switchCameraRemote(deviceId: String) {
        repository.remoteToggleLens(deviceId)
    }

    fun toggleNightVisionRemote(deviceId: String) {
        repository.remoteToggleNightVision(deviceId)
    }

    fun startRecordingRemote(deviceId: String) {
        repository.remoteToggleRecording(deviceId)
    }

    fun autoStartRecording(context: Context) {
        _isRecording.value = true
        _pendingAutoRecord.value = false
    }

    fun setMotionSensitivity(sensitivity: Float) {
        repository.updateMotionSensitivity(sensitivity)
    }

    fun clearPairedDevicesForUser(user: String) {
        repository.clearPairedDevicesForUser(user)
    }

    fun analyzeSceneWithGemini() {
        _isAnalyzingGemini.value = true
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(2000L)
            _isAnalyzingGemini.value = false
        }
    }

    fun evaluateThreatLevel() {
        _aiThreatAssessment.value = AiThreatAssessment()
    }

    fun askAiCopilot(prompt: String) {
        if (prompt.isBlank()) return
        val current = _aiChatMessages.value.toMutableList()
        current.add(AiChatMessage(sender = "User", message = prompt))
        _aiChatMessages.value = current
        _isCopilotThinking.value = true

        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(1200L)
            val updated = _aiChatMessages.value.toMutableList()
            updated.add(AiChatMessage(sender = "AI Guard", message = "AI Copilot: LiveKit WebRTC 2-way stream active."))
            _aiChatMessages.value = updated
            _isCopilotThinking.value = false
        }
    }

    // ── CameraX Pipeline Binding ──────────────────────────────────────────

    fun bindCameraWithRecorder(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()

                val lensFacing = if (currentDevice.value.lensFacingFront) {
                    CameraSelector.LENS_FACING_FRONT
                } else {
                    CameraSelector.LENS_FACING_BACK
                }
                val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                    try {
                        val bitmap = imageProxy.toBitmap()
                        if (bitmap != null) {
                            liveKitManager.pushBitmapFrame(bitmap, imageProxy.imageInfo.rotationDegrees)
                        }
                    } catch (e: Exception) {
                        Log.w("CctvViewModel", "ImageAnalysis error: ${e.message}")
                    } finally {
                        imageProxy.close()
                    }
                }

                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
                Log.d("CctvViewModel", "CameraX successfully bound to preview and LiveKit analyzer.")
            } catch (e: Exception) {
                Log.e("CctvViewModel", "Error binding CameraX to lifecycle: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }
}
