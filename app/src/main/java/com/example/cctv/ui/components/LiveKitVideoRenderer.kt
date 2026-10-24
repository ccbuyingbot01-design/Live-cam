package com.example.cctv.ui.components

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.livekit.android.LiveKit
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack
import livekit.org.webrtc.RendererCommon

private const val TAG = "LiveKitVideoRenderer"

/**
 * LiveKitVideoRenderer
 *
 * Renders remote LiveKit WebRTC VideoTrack onto a native TextureViewRenderer.
 * Initializes with LiveKit's shared EGL Context to prevent blank black screen rendering errors.
 */
@Composable
fun LiveKitVideoRenderer(
    videoTrack: VideoTrack?,
    room: Room? = null,
    modifier: Modifier = Modifier
) {
    if (videoTrack == null) return

    val context = LocalContext.current

    val renderer = remember(videoTrack) {
        TextureViewRenderer(context).apply {
            try {
                if (room != null) {
                    try {
                        room.initVideoRenderer(this)
                    } catch (e: Throwable) {
                        val egl = livekit.org.webrtc.EglBase.create()
                        init(egl.eglBaseContext, null)
                    }
                } else {
                    val egl = livekit.org.webrtc.EglBase.create()
                    init(egl.eglBaseContext, null)
                }
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing TextureViewRenderer with LiveKit EGL context", e)
            }
        }
    }

    DisposableEffect(videoTrack) {
        try {
            videoTrack.addRenderer(renderer)
            Log.i(TAG, "Successfully attached VideoTrack renderer to TextureViewRenderer!")
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
        }
    }

    AndroidView(
        factory = { renderer },
        modifier = modifier.fillMaxSize()
    )
}
