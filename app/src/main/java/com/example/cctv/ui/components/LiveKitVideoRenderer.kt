package com.example.cctv.ui.components

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.track.VideoTrack
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.RendererCommon

private const val TAG = "LiveKitVideoRenderer"

/**
 * LiveKitVideoRenderer
 *
 * Renders remote LiveKit WebRTC VideoTrack directly onto a native TextureViewRenderer.
 * Uses safe CONFIG_PLAIN EGL initialization to avoid emulator EGL_BAD_ATTRIBUTE errors.
 */
@Composable
fun LiveKitVideoRenderer(
    videoTrack: VideoTrack?,
    modifier: Modifier = Modifier
) {
    if (videoTrack == null) return

    val context = LocalContext.current

    val eglBase = remember(videoTrack) {
        try {
            EglBase.create(null, EglBase.CONFIG_PLAIN)
        } catch (e: Exception) {
            Log.w(TAG, "Fallback to default EglBase.create()", e)
            EglBase.create()
        }
    }

    val renderer = remember(videoTrack) {
        TextureViewRenderer(context).apply {
            try {
                init(eglBase.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing TextureViewRenderer", e)
            }
        }
    }

    DisposableEffect(videoTrack) {
        try {
            videoTrack.addRenderer(renderer)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding renderer to videoTrack", e)
        }

        onDispose {
            try {
                videoTrack.removeRenderer(renderer)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing renderer from videoTrack", e)
            }
            try {
                renderer.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing renderer", e)
            }
            try {
                eglBase.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing eglBase", e)
            }
        }
    }

    AndroidView(
        factory = { renderer },
        modifier = modifier.fillMaxSize()
    )
}

