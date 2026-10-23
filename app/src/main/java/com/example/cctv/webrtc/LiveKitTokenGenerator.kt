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

    val LIVEKIT_CLOUD_URL: String
        get() {
            val url = try { com.example.BuildConfig.LIVEKIT_URL } catch (_: Throwable) { null }
            return if (url.isNullOrBlank() || url == "MY_LIVEKIT_URL") "wss://live-cam-g2een99y.livekit.cloud" else url
        }

    val LIVEKIT_API_KEY: String
        get() {
            val key = try { com.example.BuildConfig.LIVEKIT_API_KEY } catch (_: Throwable) { null }
            return if (key.isNullOrBlank() || key == "MY_LIVEKIT_API_KEY") "APIoUizemAhL4XR" else key
        }

    val LIVEKIT_API_SECRET: String
        get() {
            val secret = try { com.example.BuildConfig.LIVEKIT_API_SECRET } catch (_: Throwable) { null }
            return if (secret.isNullOrBlank() || secret == "MY_LIVEKIT_API_SECRET") "OmqL8hsDwstYmL4s4ObjoEs0ox32DsoSx910q24wyCM" else secret
        }

    fun generateAccessToken(
        roomName: String,
        identity: String,
        apiKey: String = LIVEKIT_API_KEY,
        apiSecret: String = LIVEKIT_API_SECRET,
        isPublisher: Boolean = true,
        ttlSeconds: Long = 86400L // 24 hours
    ): String {
        return try {
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
            }

            // Payload
            val payloadJson = JSONObject().apply {
                put("iss", apiKey)
                put("sub", identity)
                put("nbf", now - 5)
                put("exp", exp)
                put("video", videoGrant)
            }

            val headerBase64 = base64UrlEncode(headerJson.toString().toByteArray(Charsets.UTF_8))
            val payloadBase64 = base64UrlEncode(payloadJson.toString().toByteArray(Charsets.UTF_8))
            val unsignedToken = "$headerBase64.$payloadBase64"

            val signature = hmacSha256(unsignedToken, apiSecret)
            val signatureBase64 = base64UrlEncode(signature)

            "$unsignedToken.$signatureBase64"
        } catch (e: Exception) {
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
