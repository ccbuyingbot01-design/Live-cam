package com.example.cctv.service

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class StreamingPerformanceProfile(
    val targetFps: Int,
    val jpegQuality: Int,
    val maxDimension: Int,
    val cloudSyncIntervalMs: Long,
    val description: String
)

data class BatteryReport(
    val batteryPct: Int,
    val isCharging: Boolean,
    val isLowPowerMode: Boolean,
    val temperatureCelsius: Float,
    val voltageMv: Int,
    val estimatedHoursRemaining: Float,
    val powerProfileName: String,
    val fpsTarget: Int,
    val resolutionLabel: String,
    val aiInsight: String
)

/**
 * AiBatteryOptimizer
 *
 * Intelligent adaptive power engine that monitors battery telemetry,
 * detects when the battery drops below 40% (or custom threshold),
 * and throttles camera streaming to an ultra-low consumption profile.
 */
class AiBatteryOptimizer(private val context: Context) {

    companion object {
        private const val TAG = "AiBatteryOptimizer"
        const val DEFAULT_LOW_POWER_THRESHOLD = 40

        val PROFILE_HIGH_PERFORMANCE = StreamingPerformanceProfile(
            targetFps = 15,
            jpegQuality = 30,
            maxDimension = 320,
            cloudSyncIntervalMs = 1500L,
            description = "Standard Surveillance (15 FPS • 320p)"
        )

        val PROFILE_LOW_POWER_ACTIVE = StreamingPerformanceProfile(
            targetFps = 5,
            jpegQuality = 20,
            maxDimension = 240,
            cloudSyncIntervalMs = 3500L,
            description = "AI Eco Saver (<40%): 5 FPS • 240p Low-Power"
        )

        val PROFILE_ULTRA_ECO_IDLE = StreamingPerformanceProfile(
            targetFps = 2,
            jpegQuality = 18,
            maxDimension = 240,
            cloudSyncIntervalMs = 5000L,
            description = "AI Ultra-Eco Idle (2 FPS • Quiet Scene)"
        )
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    fun getStreamingProfile(
        isLowPower: Boolean,
        hasRecentMotion: Boolean
    ): StreamingPerformanceProfile {
        return when {
            !isLowPower -> PROFILE_HIGH_PERFORMANCE
            hasRecentMotion -> PROFILE_LOW_POWER_ACTIVE
            else -> PROFILE_ULTRA_ECO_IDLE
        }
    }

    fun computeReport(
        batteryPct: Int,
        isCharging: Boolean,
        smartSaverEnabled: Boolean,
        lowPowerThreshold: Int = DEFAULT_LOW_POWER_THRESHOLD,
        hasRecentMotion: Boolean = false,
        tempCelsius: Float = 28f,
        voltageMv: Int = 3850
    ): BatteryReport {
        val isLowPower = (smartSaverEnabled && !isCharging && batteryPct <= lowPowerThreshold)

        val profile = getStreamingProfile(isLowPower, hasRecentMotion)

        // Estimated remaining hours based on discharge rate
        val dischargePerHour = when {
            isCharging -> -20.0f // Charging rate ~20%/hr
            isLowPower -> 6.5f   // Eco mode draws ~6.5%/hr with wake lock + Wi-Fi
            else -> 16.0f        // Normal mode draws ~16%/hr with video encoding
        }

        val estimatedHours = if (isCharging) {
            ((100 - batteryPct) / 20.0f).coerceAtLeast(0.1f)
        } else {
            (batteryPct / dischargePerHour).coerceAtLeast(0.1f)
        }

        val profileName = when {
            isCharging -> "AC Power (High Performance)"
            isLowPower && !hasRecentMotion -> "AI Ultra-Eco Saver (Idle 2 FPS)"
            isLowPower -> "AI Smart Battery Saver (<40% Active)"
            else -> "Standard Stream (15 FPS)"
        }

        val aiInsight = generateLocalInsight(
            batteryPct = batteryPct,
            isCharging = isCharging,
            isLowPower = isLowPower,
            tempCelsius = tempCelsius,
            estimatedHours = estimatedHours
        )

        return BatteryReport(
            batteryPct = batteryPct,
            isCharging = isCharging,
            isLowPowerMode = isLowPower,
            temperatureCelsius = tempCelsius,
            voltageMv = voltageMv,
            estimatedHoursRemaining = estimatedHours,
            powerProfileName = profileName,
            fpsTarget = profile.targetFps,
            resolutionLabel = "${profile.maxDimension}p @ ${profile.targetFps} FPS",
            aiInsight = aiInsight
        )
    }

    private fun generateLocalInsight(
        batteryPct: Int,
        isCharging: Boolean,
        isLowPower: Boolean,
        tempCelsius: Float,
        estimatedHours: Float
    ): String {
        return when {
            isCharging -> {
                "⚡ Device is charging. Full-performance 15-30 FPS streaming is active with zero battery throttling."
            }
            batteryPct <= 15 -> {
                "⚠️ CRITICAL BATTERY ($batteryPct%): Stream throttled to 2 FPS. Flash & siren disabled. Recommend enabling Stealth Mode or plugging in charger immediately."
            }
            isLowPower -> {
                "⚡ AI ECO SAVER ENGAGED ($batteryPct%): Live stream throttled to 5 FPS @ 240p to minimize CPU/Wi-Fi drain. Estimated runtime extended to ~${String.format("%.1f", estimatedHours)} hours."
            }
            batteryPct in 41..60 -> {
                "🔋 Normal Battery ($batteryPct%): AI Saver will automatically activate once battery drops under 40% to prevent unexpected shutdown."
            }
            else -> {
                "✅ Optimal Battery ($batteryPct%): Running at full standard fidelity. Camera, motion analyzer, and cloud WebRTC operating at peak performance."
            }
        }
    }

    /**
     * Checks if the app is currently whitelisted from Android system battery optimization.
     */
    fun isBatteryOptimizationIgnored(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            return pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        }
        return true
    }
}
