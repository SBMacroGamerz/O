package com.macroindustry.O

/**
<<<<<<< HEAD
 * Lightweight shared in-memory state between MainActivity, CaptureService,
 * and ControlAccessibilityService for the current process lifetime.
 * The actual pairing code persists to disk via PairingStore — Session just
 * holds it (and other transient state) while the app/service is running.
=======
 * Lightweight shared state between MainActivity, CaptureService, and
 * ControlAccessibilityService. Not persisted — a fresh room code is
 * generated/entered each session.
>>>>>>> 5b62811b678d87e2c728d223add01b0971044b31
 */
object Session {

    enum class Role { HOST, VIEWER, NONE }

    var role: Role = Role.NONE
    var roomCode: String = ""
    var isSharing: Boolean = false

<<<<<<< HEAD
    /** Host generates a fresh 6-digit pairing code. Only called once, during setup. */
=======
    /** Host generates a fresh 6-digit room code to share with the Viewer. */
>>>>>>> 5b62811b678d87e2c728d223add01b0971044b31
    fun generateRoomCode(): String {
        roomCode = (100000..999999).random().toString()
        return roomCode
    }

    fun isValidCode(code: String): Boolean {
        return code.length == 6 && code.all { it.isDigit() }
    }
}
