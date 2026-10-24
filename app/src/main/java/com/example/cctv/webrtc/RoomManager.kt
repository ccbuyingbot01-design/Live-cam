package com.example.cctv.webrtc

import android.content.Context
import android.util.Log
import io.livekit.android.LiveKit
import io.livekit.android.room.Room

/**
 * RoomManager
 *
 * Centralized singleton for managing LiveKit Room instances.
 * Uses provided credentials for secure cloud streaming initialization.
 */
object RoomManager {
    private const val TAG = "RoomManager"
    
    // LiveKit Cloud Credentials
    const val URL = "wss://live-cam-g2een99y.livekit.cloud"
    const val API_KEY = "APIoUizemAhL4XR"
    const val API_SECRET = "OmqL8hsDwstYmL4s4ObjoEs0ox32DsoSx910q24wyCM"

    private var _room: Room? = null

    /**
     * Initializes or retrieves the shared LiveKit Room instance.
     */
    fun getRoom(context: Context): Room {
        if (_room == null) {
            _room = LiveKit.create(context)
            Log.d(TAG, "LiveKit Room initialized with URL: $URL")
        }
        return _room!!
    }

    /**
     * Cleans up the room instance.
     */
    fun releaseRoom() {
        _room?.disconnect()
        _room = null
        Log.d(TAG, "LiveKit Room released.")
    }
}
