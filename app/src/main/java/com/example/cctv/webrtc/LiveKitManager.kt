package com.example.cctv.webrtc

import android.content.Context
import android.util.Log
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * LiveKitManager
 *
 * Ultra-low latency WebRTC cloud streaming engine powered by LiveKit Cloud.
 * Handles direct peer-to-peer and SFU media relay over mobile SIM networks worldwide.
 */
class LiveKitManager(private val context: Context) {

    companion object {
        private const val TAG = "LiveKitManager"
        const val DEFAULT_LIVEKIT_URL = "wss://lovecctv-demo.livekit.cloud"
    }

    var room: Room = LiveKit.create(context)
        private set

    private var eventsJob: Job? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    /**
     * Connects to LiveKit Cloud room as Camera Sender and publishes camera & microphone tracks.
     */
    suspend fun connectAsSender(
        wsUrl: String = DEFAULT_LIVEKIT_URL,
        token: String
    ): Boolean {
        return try {
            Log.i(TAG, "Connecting to LiveKit Cloud room as Camera Sender ($wsUrl)...")
            room.connect(wsUrl, token)
            _isConnected.value = true

            val localParticipant = room.localParticipant
            localParticipant.setCameraEnabled(true)
            localParticipant.setMicrophoneEnabled(true)

            Log.i(TAG, "Successfully published Camera & Mic tracks to LiveKit Cloud!")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect LiveKit Sender", e)
            _isConnected.value = false
            false
        }
    }

    /**
     * Connects to LiveKit Cloud room as Viewer and subscribes to remote camera video tracks.
     */
    suspend fun connectAsViewer(
        wsUrl: String = DEFAULT_LIVEKIT_URL,
        token: String,
        scope: CoroutineScope,
        onVideoTrackSubscribed: (VideoTrack) -> Unit = {}
    ): Boolean {
        return try {
            Log.i(TAG, "Connecting to LiveKit Cloud room as Viewer ($wsUrl)...")

            // Listen for room events (TrackSubscribed) for instant sub-second WebRTC video rendering
            eventsJob?.cancel()
            eventsJob = scope.launch(Dispatchers.IO) {
                room.events.collect { event ->
                    when (event) {
                        is RoomEvent.TrackSubscribed -> {
                            val track = event.track
                            if (track is VideoTrack) {
                                Log.i(TAG, "Subscribed to remote LiveKit video track: ${track.name}")
                                _remoteVideoTrack.value = track
                                onVideoTrackSubscribed(track)
                            }
                        }
                        is RoomEvent.Disconnected -> {
                            Log.w(TAG, "Disconnected from LiveKit room")
                            _isConnected.value = false
                            _remoteVideoTrack.value = null
                        }
                        else -> Unit
                    }
                }
            }

            room.connect(wsUrl, token)
            _isConnected.value = true
            Log.i(TAG, "Connected to LiveKit Cloud room as Viewer.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect LiveKit Viewer", e)
            _isConnected.value = false
            false
        }
    }

    /**
     * Toggles local camera enabled state.
     */
    suspend fun setCameraEnabled(enabled: Boolean) {
        if (!_isConnected.value) return
        try {
            room.localParticipant.setCameraEnabled(enabled)
        } catch (e: Exception) {
            Log.e(TAG, "Error changing camera state", e)
        }
    }

    /**
     * Cleanly disconnects and releases LiveKit Cloud resources.
     */
    fun disconnect() {
        try {
            eventsJob?.cancel()
            room.disconnect()
            _isConnected.value = false
            _remoteVideoTrack.value = null
            Log.i(TAG, "Disconnected LiveKit Room.")
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect error", e)
        }
    }
}
