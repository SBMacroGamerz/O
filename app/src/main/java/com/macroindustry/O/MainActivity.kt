package com.macroindustry.O

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.macroindustry.O.databinding.ActivityMainBinding
import org.json.JSONObject
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var webRtcClient: WebRtcClient? = null
    private var signaling: RoomSignaling? = null
    private var remoteVideoTrack: VideoTrack? = null

    // Host's real screen size, reported once over the data channel on
    // connect (see CaptureService "screen_info"). Used to map Viewer touch
    // events to accurate pixel coordinates instead of guessing.
    private var hostScreenWidth: Int = 0
    private var hostScreenHeight: Int = 0

    private val mediaProjectionRequest =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                startHostSharing(result.resultCode, result.data!!)
            } else {
                Toast.makeText(this, "Screen share permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    private val requiredPermissions = listOf(
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
        android.Manifest.permission.POST_NOTIFICATIONS
    )

    private val permissionRequest =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestRuntimePermissions()

        binding.remoteRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)

        binding.hostRoleButton.setOnClickListener { setupHostUi() }
        binding.viewerRoleButton.setOnClickListener { setupViewerUi() }

        // Long-press the title to reach parent settings (PIN-gated inside).
        binding.titleText.setOnLongClickListener {
            startActivity(Intent(this, ChildSettingsActivity::class.java))
            true
        }

        if (intent.getBooleanExtra("prompt_start_sharing", false)) {
            setupHostUi()
        }
    }

    private fun requestRuntimePermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionRequest.launch(missing.toTypedArray())
        }
    }

    // ---------- HOST FLOW ----------

    private fun setupHostUi() {
        Session.role = Session.Role.HOST
        val code = Session.generateRoomCode()
        binding.statusText.text = "Host mode — share this code with the Viewer"
        binding.roomCodeText.text = code
        binding.actionButton.visibility = View.VISIBLE
        binding.actionButton.text = "Start Sharing"
        binding.accessibilityButton.visibility = View.VISIBLE
        updateAccessibilityButtonState()

        binding.actionButton.setOnClickListener {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionRequest.launch(mpm.createScreenCaptureIntent())
        }

        binding.accessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun updateAccessibilityButtonState() {
        val enabled = ControlAccessibilityService.instance != null
        binding.accessibilityButton.text =
            if (enabled) "Accessibility Access: ON" else "Enable Accessibility Access"
    }

    private fun startHostSharing(resultCode: Int, data: Intent) {
        val intent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START_WITH_PROJECTION
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            putExtra(CaptureService.EXTRA_ROOM_CODE, Session.roomCode)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        binding.statusText.text = "Sharing — room ${Session.roomCode}"
        binding.actionButton.text = "Sharing..."
        binding.actionButton.isEnabled = false
    }

    // ---------- VIEWER FLOW ----------

    private fun setupViewerUi() {
        Session.role = Session.Role.VIEWER
        binding.statusText.text = "Enter the Host's room code"
        binding.roomCodeInput.visibility = View.VISIBLE
        binding.actionButton.visibility = View.VISIBLE
        binding.actionButton.text = "Connect"

        binding.actionButton.setOnClickListener {
            val code = binding.roomCodeInput.text.toString()
            if (!Session.isValidCode(code)) {
                Toast.makeText(this, "Enter a valid 6-digit code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            connectAsViewer(code)
        }
    }

    private fun connectAsViewer(code: String) {
        Session.role = Session.Role.VIEWER
        Session.roomCode = code
        signaling = RoomSignaling(code)
        val client = WebRtcClient(this, isHost = false, signaling = signaling!!)
        webRtcClient = client

        client.init()
        binding.remoteRenderer.init(client.getEglContext(), null)
        client.createPeerConnection()

        client.onRemoteVideoTrack = { track ->
            runOnUiThread {
                remoteVideoTrack = track
                track.addSink(binding.remoteRenderer)
                binding.setupPanel.visibility = View.GONE
                binding.remoteRenderer.visibility = View.VISIBLE
            }
        }

        client.onControlMessage = { message -> handleIncomingMessage(message) }

        client.onDisconnected = {
            runOnUiThread {
                Toast.makeText(this, "Host disconnected", Toast.LENGTH_SHORT).show()
            }
        }

        signaling?.listenForOffer { offer ->
            client.handleOfferAndCreateAnswer(offer)
        }
        signaling?.listenForIceCandidates(isHost = false) { candidate ->
            client.addRemoteIceCandidate(candidate)
        }

        setupRemoteTouchControl()

        binding.statusText.text = "Connecting to room $code..."
        binding.actionButton.isEnabled = false
    }

    /** Handles both "screen_info" (once) and "location" (periodic) messages from the Host. */
    private fun handleIncomingMessage(message: String) {
        try {
            val json = JSONObject(message)
            when (json.optString("type")) {
                "screen_info" -> {
                    hostScreenWidth = json.optInt("width")
                    hostScreenHeight = json.optInt("height")
                }
                "location" -> {
                    val lat = json.optDouble("lat")
                    val lng = json.optDouble("lng")
                    runOnUiThread {
                        binding.locationText.visibility = View.VISIBLE
                        binding.locationText.text = "Location: %.5f, %.5f".format(lat, lng)
                    }
                }
            }
        } catch (e: Exception) {
            // Non-JSON or unrelated message, ignore.
        }
    }

    /**
     * Maps touch events on the rendered surface to the Host's real pixel
     * coordinates (using the screen_info reported on connect) so taps land
     * accurately regardless of the Viewer's own screen size or aspect fit.
     */
    private fun setupRemoteTouchControl() {
        var downX = 0f
        var downY = 0f

        binding.remoteRenderer.setOnTouchListener { view, event ->
            if (hostScreenWidth == 0 || hostScreenHeight == 0) return@setOnTouchListener true

            // Normalize within the rendered view first, then scale to host pixels.
            val nx = (event.x / view.width).coerceIn(0f, 1f)
            val ny = (event.y / view.height).coerceIn(0f, 1f)
            val hostX = nx * hostScreenWidth
            val hostY = ny * hostScreenHeight

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = hostX
                    downY = hostY
                }
                MotionEvent.ACTION_UP -> {
                    val dx = Math.abs(hostX - downX)
                    val dy = Math.abs(hostY - downY)
                    val json = JSONObject()
                    if (dx < hostScreenWidth * 0.02f && dy < hostScreenHeight * 0.02f) {
                        json.put("type", "tap")
                        json.put("x", hostX)
                        json.put("y", hostY)
                    } else {
                        json.put("type", "swipe")
                        json.put("x1", downX)
                        json.put("y1", downY)
                        json.put("x2", hostX)
                        json.put("y2", hostY)
                        json.put("duration", 200)
                    }
                    webRtcClient?.sendControlMessage(json.toString())
                }
            }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        if (Session.role == Session.Role.HOST) {
            updateAccessibilityButtonState()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webRtcClient?.close()
        signaling?.stopListening()
        binding.remoteRenderer.release()
    }
}
