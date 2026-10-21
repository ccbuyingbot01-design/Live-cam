package com.example.cctv.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

class CameraXManager {

    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    var isTorchEnabled: Boolean = false
        private set

    var isFrontLens: Boolean = false
        private set

    fun startCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        motionAnalyzer: ImageAnalysis.Analyzer?,
        useFrontLens: Boolean = false,
        onCameraReady: ((Boolean) -> Unit)? = null
    ) {
        isFrontLens = useFrontLens
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder()
                    .setTargetResolution(android.util.Size(640, 360))
                    .build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val cameraSelector = if (useFrontLens) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                provider.unbindAll()

                val useCases = mutableListOf<androidx.camera.core.UseCase>(preview)

                if (motionAnalyzer != null) {
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetResolution(android.util.Size(640, 360))
                        .build()

                    imageAnalysis.setAnalyzer(analysisExecutor, motionAnalyzer)
                    useCases.add(imageAnalysis)
                }

                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    *useCases.toTypedArray()
                )

                onCameraReady?.invoke(true)
            } catch (e: Exception) {
                Log.e("CameraXManager", "Failed to bind camera use cases", e)
                onCameraReady?.invoke(false)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun toggleTorch(enable: Boolean) {
        camera?.let {
            if (it.cameraInfo.hasFlashUnit()) {
                it.cameraControl.enableTorch(enable)
                isTorchEnabled = enable
            }
        }
    }

    fun switchCameraLens(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        motionAnalyzer: ImageAnalysis.Analyzer?
    ) {
        startCamera(
            context = context,
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            motionAnalyzer = motionAnalyzer,
            useFrontLens = !isFrontLens
        )
    }

    fun unbind() {
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
