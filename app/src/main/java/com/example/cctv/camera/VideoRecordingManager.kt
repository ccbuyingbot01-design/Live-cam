package com.example.cctv.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * VideoRecordingManager
 *
 * Manages CameraX VideoCapture use case.
 * Saves recordings to MediaStore → Movies/LoveCCTV (visible on SD card / internal storage).
 * Works on API 24+ (Android 7+).
 */
class VideoRecordingManager {

    companion object {
        private const val TAG = "VideoRecordingManager"
        private const val VIDEO_SUBDIRECTORY = "LoveCCTV"
    }

    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var cameraProvider: ProcessCameraProvider? = null
    var camera: androidx.camera.core.Camera? = null
        private set
    private val recordingExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // ── State ────────────────────────────────────────────────────────────────

    var isRecording: Boolean = false
        private set

    var currentRecordingUri: android.net.Uri? = null
        private set

    /** Returns true when camera + recorder are bound and ready to record. */
    fun isReady(): Boolean = videoCapture != null && cameraProvider != null

    fun toggleTorch(enable: Boolean, context: Context): Boolean {
        return try {
            val cam = camera
            if (cam != null && cam.cameraInfo.hasFlashUnit()) {
                cam.cameraControl.enableTorch(enable)
                Log.d(TAG, "CameraX VideoCapture torch set to $enable")
                true
            } else {
                val sysCameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
                val cameraId = sysCameraManager?.cameraIdList?.firstOrNull { id ->
                    val chars = sysCameraManager.getCameraCharacteristics(id)
                    chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
                if (sysCameraManager != null && cameraId != null) {
                    sysCameraManager.setTorchMode(cameraId, enable)
                    Log.d(TAG, "System CameraManager torch set to $enable on $cameraId")
                    true
                } else {
                    Log.w(TAG, "No flash unit found on this device")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle torch in VideoRecordingManager", e)
            false
        }
    }

    // ── Camera Binding ────────────────────────────────────────────────────────

    /**
     * Binds [Preview] + [VideoCapture] to the lifecycle.
     * Call this instead of CameraXManager.startCamera() when video recording is needed.
     */
    fun startCameraWithRecorder(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        useFrontLens: Boolean = false,
        frameAnalyzer: ImageAnalysis.Analyzer? = null,
        onReady: ((Boolean) -> Unit)? = null
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val cameraSelector = if (useFrontLens) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                provider.unbindAll()

                val useCases = mutableListOf<UseCase>(preview)

                // Safely inspect hardware encoder profiles to avoid "No supported EncoderProfiles" error
                try {
                    val cameraInfo = provider.getCameraInfo(cameraSelector)
                    val videoCapabilities = Recorder.getVideoCapabilities(cameraInfo)
                    val supportedQualities = videoCapabilities.getSupportedQualities(DynamicRange.SDR)

                    if (supportedQualities.isNotEmpty()) {
                        // Prioritize SD (480p) or LOWEST for 15 FPS low-power streaming (Vivo Y91i & emulators)
                        val preferredQualities = listOf(Quality.SD, Quality.LOWEST, Quality.HD)
                            .filter { supportedQualities.contains(it) }
                            .ifEmpty { supportedQualities }

                        val qualitySelector = QualitySelector.fromOrderedList(
                            preferredQualities,
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.LOWEST)
                        )

                        val recorder = Recorder.Builder()
                            .setQualitySelector(qualitySelector)
                            .build()

                        val capture = VideoCapture.withOutput(recorder)
                        videoCapture = capture
                        useCases.add(capture)
                    } else {
                        Log.w(TAG, "No supported video qualities found on this camera. VideoCapture omitted.")
                        videoCapture = null
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "VideoCapture configuration skipped due to lack of encoder profile support: ${e.message}")
                    videoCapture = null
                }

                if (frameAnalyzer != null) {
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetResolution(android.util.Size(640, 360))
                        .build()
                    imageAnalysis.setAnalyzer(analysisExecutor, frameAnalyzer)
                    useCases.add(imageAnalysis)
                }

                try {
                    camera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, *useCases.toTypedArray())
                    Log.d(TAG, "Camera use cases bound successfully (useCases=${useCases.size}).")
                    onReady?.invoke(true)
                } catch (bindEx: Exception) {
                    // Fallback: If device cannot support concurrent Preview + VideoCapture + ImageAnalysis
                    Log.w(TAG, "Failed binding with VideoCapture, falling back to Preview + ImageAnalysis", bindEx)
                    provider.unbindAll()
                    videoCapture = null
                    val fallbackCases = mutableListOf<UseCase>(preview)
                    if (frameAnalyzer != null) {
                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setTargetResolution(android.util.Size(640, 360))
                            .build()
                        imageAnalysis.setAnalyzer(analysisExecutor, frameAnalyzer)
                        fallbackCases.add(imageAnalysis)
                    }
                    camera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, *fallbackCases.toTypedArray())
                    onReady?.invoke(true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind camera + recorder", e)
                onReady?.invoke(false)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // ── Recording Control ─────────────────────────────────────────────────────

    /**
     * Starts recording video and saves it to MediaStore under Movies/LoveCCTV.
     *
     * @param context              Application context
     * @param onRecordingStarted   Called when recording is active
     * @param onRecordingFinished  Called with the saved file URI (or null on error)
     * @param onError              Called with error message on failure
     */
    fun startRecording(
        context: Context,
        onRecordingStarted: () -> Unit = {},
        onRecordingFinished: (uri: android.net.Uri?) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val capture = videoCapture ?: run {
            onError("Camera not ready for recording.")
            return
        }

        if (isRecording) {
            onError("Already recording.")
            return
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "LOVECCTV_$timestamp.mp4"

        // Build MediaStore ContentValues
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ → relative path; IS_PENDING while writing
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$VIDEO_SUBDIRECTORY")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val mediaStoreUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val outputOptions = MediaStoreOutputOptions.Builder(context.contentResolver, mediaStoreUri)
            .setContentValues(contentValues)
            .build()

        try {
            activeRecording = capture.output
                .prepareRecording(context, outputOptions)
                .apply {
                    // Include audio if RECORD_AUDIO permission is granted
                    if (ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        withAudioEnabled()
                    }
                }
                .start(recordingExecutor) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            isRecording = true
                            Log.d(TAG, "Recording started → $fileName")
                            ContextCompat.getMainExecutor(context).execute { onRecordingStarted() }
                        }

                        is VideoRecordEvent.Finalize -> {
                            isRecording = false
                            if (!event.hasError()) {
                                val savedUri = event.outputResults.outputUri
                                currentRecordingUri = savedUri
                                // Mark IS_PENDING = 0 on Android 10+
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    val update = ContentValues().apply {
                                        put(MediaStore.Video.Media.IS_PENDING, 0)
                                    }
                                    context.contentResolver.update(savedUri, update, null, null)
                                }
                                Log.d(TAG, "Recording saved: $savedUri")
                                ContextCompat.getMainExecutor(context).execute {
                                    onRecordingFinished(savedUri)
                                }
                            } else {
                                val errMsg = "Recording error code ${event.error}: ${event.cause?.message}"
                                Log.e(TAG, errMsg)
                                ContextCompat.getMainExecutor(context).execute {
                                    onError(errMsg)
                                    onRecordingFinished(null)
                                }
                            }
                            activeRecording = null
                        }

                        else -> Unit
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            onError("Failed to start recording: ${e.message}")
        }
    }

    /**
     * Stops the current recording. [onRecordingFinished] from [startRecording] will be called
     * once the file is fully written.
     */
    fun stopRecording() {
        if (!isRecording) return
        activeRecording?.stop()
        Log.d(TAG, "Stop recording requested.")
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    fun unbind() {
        if (isRecording) stopRecording()
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ── Permission Helpers ────────────────────────────────────────────────────

    /**
     * Returns the list of permissions required for video recording on this device.
     */
    fun requiredPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            // Android 9 and below need WRITE_EXTERNAL_STORAGE to save to SD card
            list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        return list.toTypedArray()
    }

    /**
     * Returns true only if all required permissions are granted.
     */
    fun hasRequiredPermissions(context: Context): Boolean {
        return requiredPermissions().all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }
}
