package com.example.cctv.webrtc

import android.content.Context
import android.graphics.Bitmap
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
import io.livekit.android.room.track.video.BitmapFrameCapturer
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
 * Uses BitmapFrameCapturer to stream directly from CameraX frames with zero camera device conflict.
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
    private var bitmapCapturer: BitmapFrameCapturer? = null
    private var customVideoTrack: LocalVideoTrack? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isReconnecting = MutableStateFlow(false)
    val isReconnecting: StateFlow<Boolean> = _isReconnecting.asStateFlow()

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
     * Feeds CameraX real-time bitmaps directly into LiveKit's WebRTC hardware encoder pipeline.
     */
    fun pushBitmapFrame(bitmap: Bitmap, rotation: Int = 0) {
        if (!_isConnected.value) return
        try {
            bitmapCapturer?.pushBitmap(bitmap, rotation)
        } catch (e: Exception) {
            // Buffer full or drop frame for zero latency
        }
    }

    /**
     * Connects to LiveKit Cloud as Camera Sender using BitmapFrameCapturer.
     * Prevents hardware collision with CameraX, ensuring the local viewfinder never goes black.
     */
    suspend fun connectAsSender(
        roomName: String = "lovecctv_room",
        identity: String = "camera_sender_1",
        wsUrl: String = LIVEKIT_CLOUD_URL
    ): Boolean = connectMutex.withLock {
        return try {
            if (isConnectedOrConnecting()) {
                if (currentConnectedRoom == roomName && currentIsSender == true) {
                    Log.d(TAG, "Already connected as sender to room $roomName")
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
            localParticipant.setMicrophoneEnabled(true)

            // Create and publish custom BitmapFrameCapturer track from CameraX frames
            val capturer = BitmapFrameCapturer()
            bitmapCapturer = capturer
            val videoTrack = localParticipant.createVideoTrack(
                name = "camera",
                capturer = capturer
            )
            customVideoTrack = videoTrack
            localParticipant.publishVideoTrack(videoTrack)

            Log.i(TAG, "Successfully published CameraX LiveKit WebRTC video track to LiveKit Cloud!")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect LiveKit Sender", e)
            _isConnected.value = false
            bitmapCapturer = null
            customVideoTrack = null
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
                    Log.d(TAG, "Already connected as viewer to room $roomName")
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
     * Cleanly disconnects and releases LiveKit Cloud resources.
     */
    fun disconnect() {
        try {
            eventsJob?.cancel()
            bitmapCapturer = null
            customVideoTrack = null
            room.disconnect()
            _isConnected.value = false
            _isReconnecting.value = false
            _remoteVideoTrack.value = null
            currentConnectedRoom = null
            currentIsSender = null
            Log.i(TAG, "Disconnected LiveKit Room.")
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect error", e)
        }
    }
}
