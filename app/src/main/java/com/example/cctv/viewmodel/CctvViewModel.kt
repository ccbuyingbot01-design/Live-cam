package com.example.cctv.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.BatteryManager
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.example.cctv.camera.CameraXManager
import com.example.cctv.camera.MotionDetector
import com.example.cctv.camera.SirenPlayer
import com.example.cctv.data.CctvDevice
import com.example.cctv.data.CctvRepository
import com.example.cctv.data.DeviceMode
import com.example.cctv.data.MotionEvent
import com.example.cctv.service.CctvForegroundService
import com.example.cctv.supabase.SupabaseService
import com.example.cctv.supabase.SupabaseUser
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

    fun setSupabaseUser(user: SupabaseUser) {
        _supabaseUser.value = user
    }

    fun setDeviceMode(mode: DeviceMode) {
        repository.setDeviceMode(mode)
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

    private fun syncControlToSupabase() {
        val user = _supabaseUser.value ?: return
        val dev = currentDevice.value
        viewModelScope.launch {
            supabaseService.updateRemoteControl(
                username = user.username,
                torchOn = dev.torchOn,
                sirenActive = dev.sirenActive,
                lensFacingFront = dev.lensFacingFront,
                nightVision = dev.nightVision
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
                            supabaseService.publishStream(
                                username = user.username,
                                isLive = true,
                                torchOn = dev.torchOn,
                                lensFacingFront = dev.lensFacingFront,
                                sirenActive = dev.sirenActive,
                                nightVision = dev.nightVision,
                                batteryLevel = dev.batteryLevel,
                                frameBase64 = null
                            )
                        } else if (deviceMode.value == DeviceMode.VIEWER_MONITOR) {
                            val stream = supabaseService.fetchStream(user.username)
                            if (stream != null) {
                                // Sync remote camera state
                                val updatedList = pairedDevices.value.map { d ->
                                    if (d.name.contains(user.username, ignoreCase = true) || d.pairingCode.contains(user.username, ignoreCase = true)) {
                                        d.copy(
                                            isOnline = stream.isLive,
                                            torchOn = stream.torchOn,
                                            lensFacingFront = stream.lensFacingFront,
                                            sirenActive = stream.sirenActive,
                                            nightVision = stream.nightVision,
                                            batteryLevel = stream.batteryLevel
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
                supabaseService.logMotionAlert(
                    username = user.username,
                    deviceId = currentDevice.value.id,
                    severity = latest.severity.name,
                    description = latest.description,
                    snapshotBase64 = latest.snapshotBase64
                )
            }

            if (intensity > 0.7f && currentDevice.value.sirenActive) {
                sirenPlayer.startSiren(viewModelScope)
                _isSirenRinging.value = true
            }
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
    }
}
