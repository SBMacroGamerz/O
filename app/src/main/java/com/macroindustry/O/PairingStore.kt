package com.macroindustry.O

import android.content.Context

/**
 * Persists the pairing code and onboarding state to disk, so Host and
 * Viewer only need to pair once instead of re-entering a code every time.
 *
 * Host: generates the code once on first setup, stores it, reuses it on
 *       every future "Start Sharing" (including after reboot).
 * Viewer: enters the code once, stores it, then just taps "Connect".
 */
object PairingStore {
    private const val PREFS = "o_pairing_prefs"
    private const val KEY_PAIRING_CODE = "pairing_code"
    private const val KEY_ROLE = "role"
    private const val KEY_SETUP_COMPLETE = "setup_complete"

    fun getPairingCode(context: Context): String? {
        return prefs(context).getString(KEY_PAIRING_CODE, null)
    }

    fun savePairingCode(context: Context, code: String) {
        prefs(context).edit().putString(KEY_PAIRING_CODE, code).apply()
    }

    fun clearPairingCode(context: Context) {
        prefs(context).edit().remove(KEY_PAIRING_CODE).apply()
    }

    fun getSavedRole(context: Context): Session.Role {
        val name = prefs(context).getString(KEY_ROLE, null) ?: return Session.Role.NONE
        return runCatching { Session.Role.valueOf(name) }.getOrDefault(Session.Role.NONE)
    }

    fun saveRole(context: Context, role: Session.Role) {
        prefs(context).edit().putString(KEY_ROLE, role.name).apply()
    }

    fun isSetupComplete(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SETUP_COMPLETE, false)
    }

    fun markSetupComplete(context: Context) {
        prefs(context).edit().putBoolean(KEY_SETUP_COMPLETE, true).apply()
    }

    /** Full reset — used if the parent wants to unpair/re-pair from scratch. */
    fun reset(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
