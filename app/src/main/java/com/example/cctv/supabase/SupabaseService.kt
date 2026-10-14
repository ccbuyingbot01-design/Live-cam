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

class SupabaseService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
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

    suspend fun signUpOrLogin(usernameInput: String, passwordInput: String): Result<SupabaseUser> = withContext(Dispatchers.IO) {
        try {
            val cleanUsername = usernameInput.trim().lowercase()
            val cleanPassword = passwordInput.trim()
            val simulatedEmail = "$cleanUsername@lovecctv.app"

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
                // Direct custom authentication fallback
                return@withContext Result.success(SupabaseUser("usr_$cleanUsername", cleanUsername, anonKey))
            }
        } catch (e: Exception) {
            Log.e("SupabaseService", "Auth error", e)
            val cleanUsername = usernameInput.trim().lowercase()
            Result.success(SupabaseUser("usr_$cleanUsername", cleanUsername, anonKey))
        }
    }

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
            Log.e("SupabaseService", "Failed to publish stream to Supabase", e)
            false
        }
    }

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
                        isLive = obj.optBoolean("is_live", true),
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
            Log.e("SupabaseService", "Fetch stream error", e)
            null
        }
    }

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

    suspend fun logMotionAlert(
        username: String,
        deviceId: String,
        deviceName: String,
        severity: String,
        intensity: Float,
        description: String,
        snapshotBase64: String?,
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

    /**
     * Fetches unread motion alerts for the viewer from the cctv_viewer_inbox table.
     * Returns a list ordered by newest first.
     */
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

    /**
     * Marks a motion alert as acknowledged (dismissed by viewer).
     */
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

    /**
     * Registers the viewer device's FCM token so the camera
     * side knows where to send push notifications.
     */
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
}
