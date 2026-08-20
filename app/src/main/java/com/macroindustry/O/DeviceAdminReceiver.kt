package com.macroindustry.O

import android.app.admin.DeviceAdminReceiver as AndroidDeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Device Admin receiver. Being an active device admin adds one extra
 * deliberate step to uninstalling this app (Settings > Device admin apps >
 * Deactivate, then Uninstall) and lets us show a warning at that point.
 *
 * IMPORTANT — being accurate about what this does NOT do:
 * Android's own "Deactivate this device admin app?" system screen cannot be
 * skinned, intercepted, or gated with a PIN by any third-party app. Anyone
 * with physical access who knows this path can still deactivate + uninstall
 * without ever entering the 4-digit PIN. The PIN in this app protects the
 * in-app child-settings screen (turning off location sharing, disabling the
 * accessibility service, etc.), not Android's own admin-deactivation flow.
 */
class DeviceAdminReceiver : AndroidDeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "Turning this off will stop location sharing and screen access for this device."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Session.isSharing = false
    }
}
