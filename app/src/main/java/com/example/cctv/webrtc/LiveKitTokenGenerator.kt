package com.example.cctv.webrtc

import android.util.Base64
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * LiveKitTokenGenerator
 *
 * Generates HMAC-SHA256 JWT Access Tokens for LiveKit Cloud streaming.
 * Allows instant, dynamic room access for both Camera Sender and Viewer devices.
 */
object LiveKitTokenGenerator {

    const val LIVEKIT_CLOUD_URL: String = "wss://live-cam-g2een99y.livekit.cloud"
    const val LIVEKIT_API_KEY: String = "APIoUizemAhL4XR"
    const val LIVEKIT_API_SECRET: String = "OmqL8hsDwstYmL4s4ObjoEs0ox32DsoSx910q24wyCM"

    fun generateAccessToken(
        roomName: String,
        identity: String,
        apiKey: String = LIVEKIT_API_KEY,
        apiSecret: String = LIVEKIT_API_SECRET,
        isPublisher: Boolean = true,
        ttlSeconds: Long = 86400L // 24 hours
    ): String {
        return try {
            android.util.Log.d("LiveKitDebug", "JWT Token generation start -> room: '$roomName', identity: '$identity', publisher: $isPublisher")
            val now = System.currentTimeMillis() / 1000L
            val exp = now + ttlSeconds

            // Header
            val headerJson = JSONObject().apply {
                put("alg", "HS256")
                put("typ", "JWT")
            }

            // Video Grant
            val videoGrant = JSONObject().apply {
                put("roomJoin", true)
                put("room", roomName)
                put("canPublish", isPublisher)
                put("canSubscribe", true)
                put("canPublishData", true)
                put("canPublishSources", org.json.JSONArray().apply {
                    put("camera")
                    put("microphone")
                    put("screen_share")
                })
            }

            // Payload
            val payloadJson = JSONObject().apply {
                put("iss", apiKey)
                put("sub", identity)
                put("iat", now)
                put("nbf", now - 5)
                put("exp", exp)
                put("identity", identity)
                put("video", videoGrant)
            }

            val headerBase64 = base64UrlEncode(headerJson.toString().toByteArray(Charsets.UTF_8))
            val payloadBase64 = base64UrlEncode(payloadJson.toString().toByteArray(Charsets.UTF_8))
            val unsignedToken = "$headerBase64.$payloadBase64"

            val signature = hmacSha256(unsignedToken, apiSecret)
            val signatureBase64 = base64UrlEncode(signature)

            val token = "$unsignedToken.$signatureBase64"
            android.util.Log.d("LiveKitDebug", "JWT Token generated successfully (length: ${token.length})")
            token
        } catch (e: Exception) {
            android.util.Log.e("LiveKitDebug", "FATAL: Error generating JWT token: ${e.message}", e)
            e.printStackTrace()
            ""
        }
    }

    private fun hmacSha256(data: String, secret: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun base64UrlEncode(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
