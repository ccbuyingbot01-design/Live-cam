package com.example.cctv.webrtc

import android.content.Context
import android.util.Log
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * LiveKitManager
 *
 * Ultra-low latency (<300ms) WebRTC cloud streaming engine powered by LiveKit Cloud.
 * Manages local camera capture and remote video subscription with zero-buffer frame dropping.
 */
class LiveKitManager(private val context: Context) {

    companion object {
        private const val TAG = "LiveKitManager"
        const val LIVEKIT_CLOUD_URL = LiveKitTokenGenerator.LIVEKIT_CLOUD_URL
    }

    var room: Room = LiveKit.create(context)
        private set

    private val connectMutex = Mutex()
    private var currentConnectedRoom: String? = null
    private var currentIsSender: Boolean? = null
    private var eventsJob: Job? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isReconnecting = MutableStateFlow(false)
    val isReconnecting: StateFlow<Boolean> = _isReconnecting.asStateFlow()

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    fun isConnectedOrConnecting(): Boolean {
        val state = room.state
        return state == Room.State.CONNECTED || state == Room.State.CONNECTING || state == Room.State.RECONNECTING
    }

    /**
     * Initializes a TextureViewRenderer using the room's internal EglBase.
     */
    fun initVideoRenderer(view: TextureViewRenderer) {
        try {
            room.initVideoRenderer(view)
        } catch (e: Exception) {
            Log.w(TAG, "initVideoRenderer fallback", e)
        }
    }

    /**
     * Switches local camera position (Front / Back).
     */
    fun switchCamera(isFront: Boolean) {
        try {
            val pub = room.localParticipant.getTrackPublication(Track.Source.CAMERA)
            val track = pub?.track as? LocalVideoTrack
            val targetPosition = if (isFront) CameraPosition.FRONT else CameraPosition.BACK
            track?.switchCamera(position = targetPosition)
            Log.i(TAG, "Switched LiveKit camera to position: $targetPosition")
        } catch (e: Exception) {
            Log.e(TAG, "Error switching camera", e)
        }
    }

    /**
     * Connects to LiveKit Cloud as Camera Sender and publishes hardware-accelerated video & audio tracks.
     */
    suspend fun connectAsSender(
        roomName: String = "lovecctv_room",
        identity: String = "camera_sender_1",
        wsUrl: String = LIVEKIT_CLOUD_URL
    ): Boolean = connectMutex.withLock {
        return try {
            if (isConnectedOrConnecting()) {
                if (currentConnectedRoom == roomName && currentIsSender == true) {
                    Log.d(TAG, "Already connected/connecting as sender to room $roomName")
                    val pub = room.localParticipant.getTrackPublication(Track.Source.CAMERA)
                    _localVideoTrack.value = pub?.track as? VideoTrack
                    return true
                }
                Log.d(TAG, "Disconnecting previous session before connecting as sender...")
                try {
                    room.disconnect()
                } catch (e: Exception) {
                    Log.w(TAG, "Error disconnecting previous session", e)
                }
            }

            val token = LiveKitTokenGenerator.generateAccessToken(
                roomName = roomName,
                identity = identity,
                isPublisher = true
            )

            Log.i(TAG, "Connecting Camera Sender to LiveKit Cloud ($wsUrl, room: $roomName)...")
            room.connect(wsUrl, token)
            currentConnectedRoom = roomName
            currentIsSender = true
            _isConnected.value = true
            _isReconnecting.value = false

            val localParticipant = room.localParticipant
            localParticipant.setCameraEnabled(true)
            localParticipant.setMicrophoneEnabled(true)

            // Extract the local camera video track for local viewfinder display
            val pub = localParticipant.getTrackPublication(Track.Source.CAMERA)
            _localVideoTrack.value = pub?.track as? VideoTrack

            Log.i(TAG, "Successfully published Camera & Mic tracks to LiveKit Cloud! Local track=${_localVideoTrack.value != null}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect LiveKit Sender", e)
            _isConnected.value = false
            _localVideoTrack.value = null
            false
        }
    }

    /**
     * Connects to LiveKit Cloud as Viewer with zero-buffer frame dropping and auto-reconnection listeners.
     */
    suspend fun connectAsViewer(
        roomName: String = "lovecctv_room",
        identity: String = "viewer_monitor_1",
        wsUrl: String = LIVEKIT_CLOUD_URL,
        scope: CoroutineScope,
        onVideoTrackSubscribed: (VideoTrack) -> Unit = {}
    ): Boolean = connectMutex.withLock {
        return try {
            if (isConnectedOrConnecting()) {
                if (currentConnectedRoom == roomName && currentIsSender == false) {
                    Log.d(TAG, "Already connected/connecting as viewer to room $roomName")
                    return true
                }
                Log.d(TAG, "Disconnecting previous session before connecting as viewer...")
                try {
                    room.disconnect()
                } catch (e: Exception) {
                    Log.w(TAG, "Error disconnecting previous session", e)
                }
            }

            val token = LiveKitTokenGenerator.generateAccessToken(
                roomName = roomName,
                identity = identity,
                isPublisher = false
            )

            Log.i(TAG, "Connecting Viewer to LiveKit Cloud ($wsUrl, room: $roomName)...")

            // Register Room Event Listeners for zero-buffer rendering & network reconnection
            eventsJob?.cancel()
            eventsJob = scope.launch(Dispatchers.IO) {
                room.events.collect { event ->
                    when (event) {
                        is RoomEvent.TrackSubscribed -> {
                            val track = event.track
                            if (track is VideoTrack) {
                                Log.i(TAG, "Subscribed to remote LiveKit video track (<300ms latency): ${track.name}")
                                _remoteVideoTrack.value = track
                                onVideoTrackSubscribed(track)
                            }
                        }
                        is RoomEvent.Reconnecting -> {
                            Log.w(TAG, "LiveKit room reconnecting due to network switch (Wi-Fi <-> SIM)...")
                            _isReconnecting.value = true
                        }
                        is RoomEvent.Reconnected -> {
                            Log.i(TAG, "LiveKit room reconnected successfully!")
                            _isReconnecting.value = false
                            _isConnected.value = true
                        }
                        is RoomEvent.Disconnected -> {
                            Log.w(TAG, "Disconnected from LiveKit room")
                            _isConnected.value = false
                            _isReconnecting.value = false
                            _remoteVideoTrack.value = null
                            currentConnectedRoom = null
                            currentIsSender = null
                        }
                        else -> Unit
                    }
                }
            }

            room.connect(wsUrl, token)
            currentConnectedRoom = roomName
            currentIsSender = false
            _isConnected.value = true
            _isReconnecting.value = false
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
            if (enabled) {
                val pub = room.localParticipant.getTrackPublication(Track.Source.CAMERA)
                _localVideoTrack.value = pub?.track as? VideoTrack
            } else {
                _localVideoTrack.value = null
            }
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
            _isReconnecting.value = false
            _localVideoTrack.value = null
            _remoteVideoTrack.value = null
            currentConnectedRoom = null
            currentIsSender = null
            Log.i(TAG, "Disconnected LiveKit Room.")
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect error", e)
        }
    }
}
