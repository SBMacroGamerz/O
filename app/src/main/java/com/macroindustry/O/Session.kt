package com.macroindustry.O

/**
 * Lightweight shared state between MainActivity, CaptureService, and
 * ControlAccessibilityService. Not persisted — a fresh room code is
 * generated/entered each session.
 */
object Session {

    enum class Role { HOST, VIEWER, NONE }

    var role: Role = Role.NONE
    var roomCode: String = ""
    var isSharing: Boolean = false

    /** Host generates a fresh 6-digit room code to share with the Viewer. */
    fun generateRoomCode(): String {
        roomCode = (100000..999999).random().toString()
        return roomCode
    }

    fun isValidCode(code: String): Boolean {
        return code.length == 6 && code.all { it.isDigit() }
    }
}
