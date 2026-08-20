package com.macroindustry.O

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Starts CaptureService in "ready" mode as soon as the device finishes
 * booting, so O is standing by with a persistent notification. Actual
 * screen sharing still requires one tap on that notification, since
 * MediaProjection consent cannot be silently re-granted after reboot —
 * this is an Android OS restriction, not something any app can bypass.
 *
 * Note: on MIUI, "Autostart" must additionally be enabled manually for
 * this app in Security app settings, or the OS will prevent this
 * receiver from firing at all. This can't be requested in-code.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        Log.i(TAG, "Boot completed, starting CaptureService in ready mode")

        val serviceIntent = Intent(context, CaptureService::class.java).apply {
            action = CaptureService.ACTION_READY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
