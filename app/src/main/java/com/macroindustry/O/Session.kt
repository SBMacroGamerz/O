package com.macroindustry.O

/**
 * Lightweight shared in-memory state between MainActivity, CaptureService,
 * and ControlAccessibilityService for the current process lifetime.
 * The actual pairing code persists to disk via PairingStore — Session just
 * holds it (and other transient state) while the app/service is running.
 */
object Session {

    enum class Role { HOST, VIEWER, NONE }

    var role: Role = Role.NONE
    var roomCode: String = ""
    var isSharing: Boolean = false

    /** Host generates a fresh 6-digit pairing code. Only called once, during setup. */
    fun generateRoomCode(): String {
        roomCode = (100000..999999).random().toString()
        return roomCode
    }

    fun isValidCode(code: String): Boolean {
        return code.length == 6 && code.all { it.isDigit() }
    }
}
