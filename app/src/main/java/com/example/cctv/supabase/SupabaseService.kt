package com.example.cctv.supabase

import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class SupabaseUser(
    val id: String,
    val username: String,
    val accessToken: String
)

data class StreamData(
    val username: String,
    val isLive: Boolean,
    val torchOn: Boolean,
    val lensFacingFront: Boolean,
    val sirenActive: Boolean,
    val nightVision: Boolean,
    val batteryLevel: Int,
    val frameBase64: String?,
    val updatedAt: Long,
    val recordingActive: Boolean = false
)

data class RemoteMotionAlert(
    val id: String,
    val username: String,
    val deviceId: String,
    val deviceName: String,
    val severity: String,
    val intensity: Float,
    val description: String,
    val snapshotBase64: String?,
    val torchTriggered: Boolean,
    val timestamp: Long
)

data class RemoteCommand(
    val id: Long,
    val deviceId: String,
    val username: String,
    val action: String,
    val consumed: Boolean,
    val timestamp: Long
)

class SupabaseService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val baseUrl: String
        get() {
            val url = BuildConfig.SUPABASE_URL
            return if (url.isNull_or_blank()) "https://jakefqooxfsqvvhvmwxc.supabase.co" else url
        }

    private val anonKey: String
        get() {
            val key = BuildConfig.SUPABASE_ANON_KEY
            return if (key.isNull_or_blank()) "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Impha2VmcW9veGZzcXZ2aHZtd3hjIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTE5Nzk2MzIsImV4cCI6MjEwNzU1NTYzMn0.6nPg4AKXLnQNoQLEn6nkvqJjnc9EVzZgg060CB69SJg" else key
        }

    private fun String?.isNull_or_blank(): Boolean = this == null || this.trim().isEmpty() || this == "MY_SUPABASE_URL"

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // ── Authentication ─────────────────────────────────────────────────────

    suspend fun signUpOrLogin(usernameInput: String, passwordInput: String): Result<SupabaseUser> = withContext(Dispatchers.IO) {
        try {
            val cleanUsername = usernameInput.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
            val finalUsername = if (cleanUsername.isBlank()) "user_${System.currentTimeMillis()}" else cleanUsername
            val simulatedEmail = "$finalUsername@lovecctv.app"
            val cleanPassword = passwordInput.trim()

            // Attempt Login via Supabase Auth
            val loginBody = JSONObject().apply {
                put("email", simulatedEmail)
                put("password", cleanPassword)
            }.toString().toRequestBody(jsonMediaType)

            val loginRequest = Request.Builder()
                .url("$baseUrl/auth/v1/token?grant_type=password")
                .header("apikey", anonKey)
                .header("Content-Type", "application/json")
                .post(loginBody)
                .build()

            val loginResponse = client.newCall(loginRequest).execute()
            val loginResponseBody = loginResponse.body?.string() ?: ""

            if (loginResponse.isSuccessful) {
                val json = JSONObject(loginResponseBody)
                val token = json.optString("access_token", anonKey)
                val userObj = json.optJSONObject("user")
                val userId = userObj?.optString("id") ?: "usr_$cleanUsername"
                return@withContext Result.success(SupabaseUser(userId, cleanUsername, token))
            }

            // If login failed, attempt Registration (SignUp)
            val signUpBody = JSONObject().apply {
                put("email", simulatedEmail)
                put("password", cleanPassword)
                put("data", JSONObject().apply { put("username", cleanUsername) })
            }.toString().toRequestBody(jsonMediaType)

            val signUpRequest = Request.Builder()
                .url("$baseUrl/auth/v1/signup")
                .header("apikey", anonKey)
                .header("Content-Type", "application/json")
                .post(signUpBody)
                .build()

            val signUpResponse = client.newCall(signUpRequest).execute()
            val signUpResponseBody = signUpResponse.body?.string() ?: ""

            if (signUpResponse.isSuccessful) {
                val json = JSONObject(signUpResponseBody)
                val token = json.optString("access_token", anonKey)
                val userObj = json.optJSONObject("user")
                val userId = userObj?.optString("id") ?: "usr_$cleanUsername"
                return@withContext Result.success(SupabaseUser(userId, cleanUsername, token))
            } else {
                val errorJson = try { JSONObject(signUpResponseBody) } catch (_: Exception) { JSONObject() }
                val errorMsg = errorJson.optString("msg",
                    errorJson.optString("error_description", "Sign up failed. Check credentials."))
                return@withContext Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e("SupabaseService", "Auth error", e)
            Result.failure(Exception("Network error: ${e.message}"))
        }
    }

    // ── Stream Publishing (Camera → Cloud) ─────────────────────────────────

    suspend fun publishStream(
        username: String,
        isLive: Boolean,
        torchOn: Boolean,
        lensFacingFront: Boolean,
        sirenActive: Boolean,
        nightVision: Boolean,
        batteryLevel: Int,
        frameBase64: String?,
        recordingActive: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val bodyJson = JSONObject().apply {
                put("username", username)
                put("is_live", isLive)
                put("torch_on", torchOn)
                put("lens_facing_front", lensFacingFront)
                put("siren_active", sirenActive)
                put("night_vision", nightVision)
                put("battery_level", batteryLevel)
                put("recording_active", recordingActive)
                if (frameBase64 != null) {
                    put("frame_base64", frameBase64)
                }
                put("updated_at", System.currentTimeMillis())
            }.toString().toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url("$baseUrl/rest/v1/cctv_streams?on_conflict=username")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $anonKey")
                .header("Content-Type", "application/json")
                .header("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(bodyJson)
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e("SupabaseService", "Failed to publish stream: ${e.message}")
            false
        }
    }

    // ── Stream Fetching (Viewer ← Cloud) ───────────────────────────────────

    suspend fun fetchStream(username: String): StreamData? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/rest/v1/cctv_streams?username=eq.$username&select=*")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $anonKey")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (response.isSuccessful && body.startsWith("[")) {
                val array = JSONArray(body)
                if (array.length() > 0) {
                    val obj = array.getJSONObject(0)
                    return@withContext StreamData(
                        username = obj.optString("username", username),
                        isLive = obj.optBoolean("is_live", false),
                        torchOn = obj.optBoolean("torch_on", false),
                        lensFacingFront = obj.optBoolean("lens_facing_front", false),
                        sirenActive = obj.optBoolean("siren_active", false),
                        nightVision = obj.optBoolean("night_vision", false),
                        batteryLevel = obj.optInt("battery_level", 85),
                        frameBase64 = obj.optString("frame_base64", null),
                        updatedAt = obj.optLong("updated_at", System.currentTimeMillis()),
                        recordingActive = obj.optBoolean("recording_active", false)
                    )
                }
            }
            null
        } catch (e: Exception) {
            Log.e("SupabaseService", "Fetch stream error: ${e.message}")
            null
        }
    }

    // ── Remote Control Update ──────────────────────────────────────────────

    suspend fun updateRemoteControl(
        username: String,
        torchOn: Boolean? = null,
        sirenActive: Boolean? = null,
        lensFacingFront: Boolean? = null,
        nightVision: Boolean? = null,
        recordingActive: Boolean? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val bodyObj = JSONObject().apply {
                torchOn?.let { put("torch_on", it) }
                sirenActive?.let { put("siren_active", it) }
                lensFacingFront?.let { put("lens_facing_front", it) }
                nightVision?.let { put("night_vision", it) }
                recordingActive?.let { put("recording_active", it) }
                put("updated_at", System.currentTimeMillis())
            }.toString().toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url("$baseUrl/rest/v1/cctv_streams?username=eq.$username")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $anonKey")
                .header("Content-Type", "application/json")
                .patch(bodyObj)
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e("SupabaseService", "Update remote control error", e)
            false
        }
    }

    // ── Motion Alert Logging ───────────────────────────────────────────────

    suspend fun logMotionAlert(
        username: String,
        deviceId: String,
        deviceName: String,
        severity: String,
        intensity: Float,
        description: String,
        snapshotBase64: String? = null,
        torchTriggered: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val bodyObj = JSONObject().apply {
                put("username", username)
                put("device_id", deviceId)
                put("device_name", deviceName)
                put("severity", severity)
                put("intensity", intensity)
                put("description", description)
                put("torch_triggered", torchTriggered)
                put("acknowledged", false)
                if (snapshotBase64 != null) {
                    put("snapshot_base64", snapshotBase64)
                }
                put("timestamp", System.currentTimeMillis())
            }.toString().toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url("$baseUrl/rest/v1/cctv_motion_alerts")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $anonKey")
                .header("Content-Type", "application/json")
                .post(bodyObj)
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e("SupabaseService", "Log motion error", e)
            false
        }
    }

    // ── Fetch Unread Motion Alerts ─────────────────────────────────────────

    suspend fun fetchUnreadMotionAlerts(username: String): List<RemoteMotionAlert> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(
                    "$baseUrl/rest/v1/cctv_motion_alerts" +
                    "?username=eq.$username" +
                    "&acknowledged=eq.false" +
                    "&order=timestamp.desc" +
                    "&limit=10" +
                    "&select=*"
                )
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $anonKey")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (response.isSuccessful && body.startsWith("[")) {
                val array = JSONArray(body)
                (0 until array.length()).mapNotNull { i ->
                    try {
                        val obj = array.getJSONObject(i)
                        RemoteMotionAlert(
                            id = obj.optString("id", ""),
                            username = obj.optString("username", username),
                            deviceId = obj.optString("device_id", ""),
                            deviceName = obj.optString("device_name", "Camera"),
                            severity = obj.optString("severity", "LOW"),
                            intensity = obj.optDouble("intensity", 0.0).toFloat(),
                            description = obj.optString("description", ""),
                            snapshotBase64 = obj.optString("snapshot_base64", null),
                            torchTriggered = obj.optBoolean("torch_triggered", false),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                        )
                    } catch (e: Exception) { null }
                }
            } else emptyList()
        } catch (e: Exception) {
            Log.e("SupabaseService", "fetchUnreadAlerts error", e)
            emptyList()
        }
    }

    // ── Acknowledge Motion Alert ───────────────────────────────────────────

    suspend fun acknowledgeMotionAlert(alertId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val bodyObj = JSONObject().apply {
                put("acknowledged", true)
            }.toString().toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url("$baseUrl/rest/v1/cctv_motion_alerts?id=eq.$alertId")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $anonKey")
                .header("Content-Type", "application/json")
                .patch(bodyObj)
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e("SupabaseService", "acknowledgeAlert error", e)
            false
        }
    }

    // ── FCM Token Registration ─────────────────────────────────────────────

    suspend fun registerFcmToken(
        username: String,
        fcmToken: String,
        deviceLabel: String = "My Phone",
        isViewer: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val bodyObj = JSONObject().apply {
                put("username", username)
                put("fcm_token", fcmToken)
                put("device_label", deviceLabel)
                put("is_viewer", isViewer)
                put("last_seen", System.currentTimeMillis())
            }.toString().toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url("$baseUrl/rest/v1/cctv_fcm_tokens?on_conflict=fcm_token")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $anonKey")
                .header("Content-Type", "application/json")
                .header("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(bodyObj)
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e("SupabaseService", "registerFcmToken error", e)
            false
        }
    }

    // ── Command Queue (Viewer → Camera) ────────────────────────────────────

    suspend fun insertCommand(username: String, deviceId: String, action: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val bodyObj = JSONObject().apply {
                put("username", username)
                put("device_id", deviceId)
                put("action", action)
                put("consumed", false)
                put("timestamp", System.currentTimeMillis())
            }.toString().toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url("$baseUrl/rest/v1/cctv_commands")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $anonKey")
                .header("Content-Type", "application/json")
                .post(bodyObj)
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e("SupabaseService", "Insert command error", e)
            false
        }
    }

    /**
     * Fetches unconsumed commands for a given username or device. Camera side polls this.
     */
    suspend fun fetchPendingCommands(username: String, deviceId: String = ""): List<RemoteCommand> = withContext(Dispatchers.IO) {
        try {
            val filter = if (username.isNotBlank()) "username=eq.$username" else "device_id=eq.$deviceId"
            val request = Request.Builder()
                .url(
                    "$baseUrl/rest/v1/cctv_commands" +
                    "?$filter" +
                    "&consumed=eq.false" +
                    "&order=timestamp.asc" +
                    "&limit=20" +
                    "&select=*"
                )
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $anonKey")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (response.isSuccessful && body.startsWith("[")) {
                val array = JSONArray(body)
                (0 until array.length()).mapNotNull { i ->
                    try {
                        val obj = array.getJSONObject(i)
                        RemoteCommand(
                            id = obj.optLong("id", 0),
                            deviceId = obj.optString("device_id", ""),
                            username = obj.optString("username", ""),
                            action = obj.optString("action", ""),
                            consumed = obj.optBoolean("consumed", false),
                            timestamp = obj.optLong("timestamp", 0)
                        )
                    } catch (e: Exception) { null }
                }
            } else emptyList()
        } catch (e: Exception) {
            Log.e("SupabaseService", "fetchPendingCommands error", e)
            emptyList()
        }
    }

    /**
     * Marks a command as consumed so it won't be fetched again.
     */
    suspend fun markCommandConsumed(commandId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val bodyObj = JSONObject().apply {
                put("consumed", true)
            }.toString().toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url("$baseUrl/rest/v1/cctv_commands?id=eq.$commandId")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $anonKey")
                .header("Content-Type", "application/json")
                .patch(bodyObj)
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e("SupabaseService", "markCommandConsumed error", e)
            false
        }
    }

    // ── Paired Device CRUD ─────────────────────────────────────────────────

    suspend fun createPairedDevice(
        username: String,
        pairingCode: String,
        deviceName: String,
        ipAddress: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("username", username)
                put("pairing_code", pairingCode)
                put("device_name", deviceName)
                put("ip_address", ipAddress)
                put("created_at", System.currentTimeMillis())
            }.toString().toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url("$baseUrl/rest/v1/cctv_paired_devices?on_conflict=pairing_code")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $anonKey")
                .header("Content-Type", "application/json")
                .header("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e("SupabaseService", "Create paired device error: ${e.message}")
            false
        }
    }

    suspend fun getPairedDeviceByCode(pairingCode: String): com.example.cctv.data.CctvDevice? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/rest/v1/cctv_paired_devices?pairing_code=eq.$pairingCode&select=*")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $anonKey")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (response.isSuccessful && body.startsWith("[")) {
                val array = JSONArray(body)
                if (array.length() > 0) {
                    val obj = array.getJSONObject(0)
                    return@withContext com.example.cctv.data.CctvDevice(
                        id = obj.optString("id", ""),
                        name = obj.optString("device_name", "Paired Camera"),
                        pairingCode = obj.optString("pairing_code", ""),
                        ipAddress = obj.optString("ip_address", ""),
                        isOnline = true,
                        isStreaming = false,
                        batteryLevel = 100
                    )
                }
            }
            null
        } catch (e: Exception) {
            Log.e("SupabaseService", "Get paired device error", e)
            null
        }
    }

    suspend fun listDevicesForUser(username: String): List<com.example.cctv.data.CctvDevice> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/rest/v1/cctv_paired_devices?username=eq.$username&select=*")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $anonKey")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (response.isSuccessful && body.startsWith("[")) {
                val array = JSONArray(body)
                (0 until array.length()).mapNotNull { i ->
                    try {
                        val obj = array.getJSONObject(i)
                        com.example.cctv.data.CctvDevice(
                            id = obj.optString("id", ""),
                            name = obj.optString("device_name", "Camera"),
                            pairingCode = obj.optString("pairing_code", ""),
                            ipAddress = obj.optString("ip_address", ""),
                            isOnline = true,
                            isStreaming = false,
                            batteryLevel = 100
                        )
                    } catch (e: Exception) { null }
                }
            } else emptyList()
        } catch (e: Exception) {
            Log.e("SupabaseService", "List devices error", e)
            emptyList()
        }
    }
    suspend fun clearAllPairedDevices(username: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/rest/v1/cctv_paired_devices?username=eq.$username")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $anonKey")
                .delete()
                .build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e("SupabaseService", "Clear paired devices error: ${e.message}")
            false
        }
    }
}
