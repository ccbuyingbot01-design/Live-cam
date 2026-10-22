package com.example.cctv.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.cctv.data.SessionManager

/**
 * BootReceiver
 *
 * Listens for ACTION_BOOT_COMPLETED, QUICKBOOT_POWERON, and MY_PACKAGE_REPLACED.
 * Checks if this phone was set to remember Live Cam (CAMERA_SENDER) mode.
 * If configured, immediately auto-starts CctvForegroundService so camera surveillance
 * resumes automatically after phone restarts without user intervention.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Received broadcast action: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val savedMode = SessionManager.getSavedModeSync(context)
            Log.i(TAG, "Boot received. Saved phone mode: $savedMode")

            // Only auto-start foreground camera service if this phone is configured and remembered as Camera Sender
            if (savedMode == "CAMERA_SENDER") {
                val serviceIntent = Intent(context, CctvForegroundService::class.java).apply {
                    setAction(CctvForegroundService.ACTION_START)
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    Log.i(TAG, "Auto-started CctvForegroundService after reboot.")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start CctvForegroundService on boot", e)
                }
            } else {
                Log.d(TAG, "Device mode is $savedMode (Viewer Monitor), skipping camera service auto-start.")
            }
        }
    }
}
