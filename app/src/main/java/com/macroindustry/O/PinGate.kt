package com.macroindustry.O

import android.content.Context

/**
 * Stores the parent PIN in SharedPreferences (plain app-private storage,
 * not encrypted — adequate for deterring a child, not a determined
 * attacker with root/adb access to the device).
 */
object PinGate {
    private const val PREFS = "o_admin_prefs"
    private const val KEY_PIN = "admin_pin"
    private const val DEFAULT_PIN = "6146"

    fun getPin(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PIN, DEFAULT_PIN) ?: DEFAULT_PIN
    }

    fun setPin(context: Context, newPin: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PIN, newPin).apply()
    }

    fun check(context: Context, candidate: String): Boolean {
        return candidate == getPin(context)
    }
}
