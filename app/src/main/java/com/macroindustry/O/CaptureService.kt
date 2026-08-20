package com.macroindustry.O

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

/**
 * Foreground service, Host side.
 *
 * Two ways it gets started:
 *  1. User taps "Start Sharing" in MainActivity after granting MediaProjection
 *     consent -> ACTION_START_WITH_PROJECTION with the result Intent attached.
 *  2. BootReceiver starts it in "ready" mode after reboot -> ACTION_READY.
 *     It posts a persistent notification; tapping the notification launches
 *     MainActivity to (re-)request the MediaProjection consent dialog, since
 *     that consent cannot be silently re-granted after a reboot.
 *
 * Once actively sharing, it also creates the WebRTC PeerConnection, the
 * screen video track, and relays control-channel messages to
 * ControlAccessibilityService.
 */
class CaptureService : Service() {

    companion object {
        private const val TAG = "CaptureService"
        private const val NOTIF_CHANNEL_ID = "o_capture_channel"
        private const val NOTIF_ID = 1

        const val ACTION_READY = "com.macroindustry.O.action.READY"
        const val ACTION_START_WITH_PROJECTION = "com.macroindustry.O.action.START_WITH_PROJECTION"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_ROOM_CODE = "room_code"
    }

    private var webRtcClient: WebRtcClient? = null
    private var signaling: RoomSignaling? = null
    private var locationReporter: LocationReporter? = null
<<<<<<< HEAD
    private val fileShareManager by lazy { FileShareManager(this) }
=======
>>>>>>> 5b62811b678d87e2c728d223add01b0971044b31

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_READY -> {
                startForeground(NOTIF_ID, buildReadyNotification())
                Log.i(TAG, "Service ready, awaiting user tap to start sharing")
            }
            ACTION_START_WITH_PROJECTION -> {
                startForeground(NOTIF_ID, buildSharingNotification())
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val roomCode = intent.getStringExtra(EXTRA_ROOM_CODE)

                if (resultData == null || roomCode == null) {
                    Log.e(TAG, "Missing projection data or room code, stopping")
                    stopSelf()
                    return START_NOT_STICKY
                }
                beginSharing(resultCode, resultData, roomCode)
            }
        }
        return START_STICKY
    }

    private fun beginSharing(resultCode: Int, resultData: Intent, roomCode: String) {
        Session.role = Session.Role.HOST
        Session.roomCode = roomCode
<<<<<<< HEAD
        PairingStore.savePairingCode(this, roomCode)
        PairingStore.saveRole(this, Session.Role.HOST)
=======
>>>>>>> 5b62811b678d87e2c728d223add01b0971044b31

        signaling = RoomSignaling(roomCode)
        val client = WebRtcClient(this, isHost = true, signaling = signaling!!)
        webRtcClient = client

        client.init()
        client.startScreenCapture(resultCode, resultData)
        client.createPeerConnection()

        client.onControlMessage = { message -> handleControlMessage(message) }
        client.onConnected = {
            Session.isSharing = true
            Log.i(TAG, "Viewer connected")
        }
        client.onDisconnected = {
            Session.isSharing = false
            Log.i(TAG, "Viewer disconnected")
        }
        client.onDataChannelOpen = {
            // Tell the Viewer our real screen size so it can map touch
            // coordinates to accurate pixel positions instead of guessing.
            val metrics = resources.displayMetrics
            val dims = JSONObject().apply {
                put("type", "screen_info")
                put("width", metrics.widthPixels)
                put("height", metrics.heightPixels)
            }
            client.sendControlMessage(dims.toString())

            // Data channel is ready to carry location updates alongside control commands.
            locationReporter = LocationReporter(this) { locationJson ->
                client.sendControlMessage(locationJson)
            }
            locationReporter?.start()
        }

<<<<<<< HEAD
        // Clear any stale offer/answer/candidates from a previous session on
        // this same reused code before starting a fresh handshake.
        signaling?.resetForNewSession {
            client.createOffer()
            signaling?.listenForAnswer { answer -> client.handleAnswer(answer) }
            signaling?.listenForIceCandidates(isHost = true) { candidate ->
                client.addRemoteIceCandidate(candidate)
            }
=======
        client.createOffer()
        signaling?.listenForAnswer { answer -> client.handleAnswer(answer) }
        signaling?.listenForIceCandidates(isHost = true) { candidate ->
            client.addRemoteIceCandidate(candidate)
>>>>>>> 5b62811b678d87e2c728d223add01b0971044b31
        }
    }

    private fun handleControlMessage(message: String) {
        try {
            val json = JSONObject(message)
<<<<<<< HEAD
            when (json.optString("type")) {
                "tap", "swipe", "longpress" -> handleGestureCommand(json)
                "switch_camera" -> {
                    val front = json.optBoolean("front", false)
                    webRtcClient?.startCameraCapture(front)
                }
                "switch_screen" -> {
                    // Re-switching back to screen requires a fresh MediaProjection
                    // consent on some Android versions; simplest reliable path is
                    // asking the user to tap "Start Sharing" again from the dashboard.
                    Log.i(TAG, "Switch-to-screen requested; needs fresh consent, ignored here")
                }
                "audio_on" -> webRtcClient?.startAudioCapture()
                "audio_off" -> webRtcClient?.stopAudioCapture()
                "file_list" -> {
                    val category = json.optString("category")
                    val response = fileShareManager.listCategory(category)
                    webRtcClient?.sendControlMessage(response)
                }
                "file_get" -> {
                    val category = json.optString("category")
                    val name = json.optString("name")
                    val response = fileShareManager.readFile(category, name)
                    webRtcClient?.sendControlMessage(response)
                }
=======
            val svc = ControlAccessibilityService.instance
            if (svc == null) {
                Log.w(TAG, "Accessibility service not enabled, dropping control command")
                return
            }
            when (json.optString("type")) {
                "tap" -> svc.performTap(
                    json.optDouble("x").toFloat(),
                    json.optDouble("y").toFloat()
                )
                "swipe" -> svc.performSwipe(
                    json.optDouble("x1").toFloat(),
                    json.optDouble("y1").toFloat(),
                    json.optDouble("x2").toFloat(),
                    json.optDouble("y2").toFloat(),
                    json.optLong("duration", 200)
                )
                "longpress" -> svc.performLongPress(
                    json.optDouble("x").toFloat(),
                    json.optDouble("y").toFloat()
                )
>>>>>>> 5b62811b678d87e2c728d223add01b0971044b31
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bad control message: ${e.message}")
        }
    }

<<<<<<< HEAD
    private fun handleGestureCommand(json: JSONObject) {
        val svc = ControlAccessibilityService.instance
        if (svc == null) {
            Log.w(TAG, "Accessibility service not enabled, dropping gesture command")
            return
        }
        when (json.optString("type")) {
            "tap" -> svc.performTap(
                json.optDouble("x").toFloat(),
                json.optDouble("y").toFloat()
            )
            "swipe" -> svc.performSwipe(
                json.optDouble("x1").toFloat(),
                json.optDouble("y1").toFloat(),
                json.optDouble("x2").toFloat(),
                json.optDouble("y2").toFloat(),
                json.optLong("duration", 200)
            )
            "longpress" -> svc.performLongPress(
                json.optDouble("x").toFloat(),
                json.optDouble("y").toFloat()
            )
        }
    }

=======
>>>>>>> 5b62811b678d87e2c728d223add01b0971044b31
    override fun onDestroy() {
        Session.isSharing = false
        webRtcClient?.close()
        signaling?.stopListening()
        webRtcClient = null
        signaling = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID, "O Screen Sharing",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun readyPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
<<<<<<< HEAD
=======
            putExtra("prompt_start_sharing", true)
>>>>>>> 5b62811b678d87e2c728d223add01b0971044b31
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun buildReadyNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("O is ready")
            .setContentText("Tap to allow screen access")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(readyPendingIntent())
            .setOngoing(true)
            .build()
    }

    private fun buildSharingNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("O is sharing your screen")
            .setContentText("Room code: ${Session.roomCode}")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(readyPendingIntent())
            .setOngoing(true)
            .build()
    }
}
