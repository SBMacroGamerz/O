package com.macroindustry.O

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import org.webrtc.*
import java.nio.charset.Charset

/**
 * Wraps a single WebRTC PeerConnection for either role:
 *  - HOST: captures the screen (via MediaProjection through screenCapturerAndroid)
 *          and adds it as an outgoing video track. Opens a data channel for
 *          incoming control commands from the Viewer.
 *  - VIEWER: receives the incoming video track and renders it to a SurfaceViewRenderer.
 *          Sends control commands over the data channel.
 *
 * Signaling (offer/answer/ICE) goes through RoomSignaling (Firestore).
 */
class WebRtcClient(
    private val context: Context,
    private val isHost: Boolean,
    private val signaling: RoomSignaling
) {

    companion object {
        private const val TAG = "WebRtcClient"

        // Public STUN server for NAT traversal. For reliable cross-network
        // connectivity (e.g. one phone on mobile data behind CGNAT), a TURN
        // server may be needed later — flagged as a possible follow-up.
        private val ICE_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
    }

    private lateinit var eglBase: EglBase
    private lateinit var factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null

    var onRemoteVideoTrack: ((VideoTrack) -> Unit)? = null
    var onControlMessage: ((String) -> Unit)? = null
    var onDataChannelOpen: (() -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    fun init() {
        eglBase = EglBase.create()
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun getEglContext(): EglBase.Context = eglBase.eglBaseContext

    /** HOST ONLY: begin screen capture and attach as local video track. */
    fun startScreenCapture(resultCode: Int, resultData: Intent) {
        val capturer = ScreenCapturerAndroid(resultData, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "Screen capture stopped by system")
                onDisconnected?.invoke()
            }
        })
        videoCapturer = capturer

        val surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        videoSource = factory.createVideoSource(true)
        capturer.initialize(surfaceHelper, context, videoSource!!.capturerObserver)

        val metrics = context.resources.displayMetrics
        capturer.startCapture(metrics.widthPixels, metrics.heightPixels, 15)

        localVideoTrack = factory.createVideoTrack("O_SCREEN_TRACK", videoSource)
    }

    fun createPeerConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                signaling.sendIceCandidate(candidate, isHost)
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                Log.i(TAG, "Connection state: $newState")
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> onConnected?.invoke()
                    PeerConnection.PeerConnectionState.DISCONNECTED,
                    PeerConnection.PeerConnectionState.FAILED,
                    PeerConnection.PeerConnectionState.CLOSED -> onDisconnected?.invoke()
                    else -> {}
                }
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track()
                if (track is VideoTrack) {
                    onRemoteVideoTrack?.invoke(track)
                }
            }

            override fun onDataChannel(channel: DataChannel) {
                dataChannel = channel
                registerDataChannelObserver(channel)
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
        })

        if (isHost) {
            localVideoTrack?.let { track ->
                peerConnection?.addTrack(track, listOf("O_STREAM"))
            }
            val dcInit = DataChannel.Init()
            val channel = peerConnection?.createDataChannel("control", dcInit)
            dataChannel = channel
            channel?.let { registerDataChannelObserver(it) }
        }
    }

    private fun registerDataChannelObserver(channel: DataChannel) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {
                Log.i(TAG, "Data channel state: ${channel.state()}")
                if (channel.state() == DataChannel.State.OPEN) {
                    onDataChannelOpen?.invoke()
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val text = String(bytes, Charset.forName("UTF-8"))
                onControlMessage?.invoke(text)
            }
        })
    }

    /**
     * Sends a JSON string over the data channel. Bidirectional in practice:
     * Viewer -> Host for control commands (tap/swipe/longpress), and
     * Host -> Viewer for periodic location updates.
     */
    fun sendControlMessage(json: String) {
        val buffer = DataChannel.Buffer(
            java.nio.ByteBuffer.wrap(json.toByteArray(Charset.forName("UTF-8"))),
            false
        )
        dataChannel?.send(buffer)
    }

    /** HOST: create and send an SDP offer. */
    fun createOffer() {
        val constraints = MediaConstraints()
        peerConnection?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(SdpObserverAdapter(), sdp)
                signaling.sendOffer(sdp)
            }
        }, constraints)
    }

    /** VIEWER: apply the Host's offer, then create and send an answer. */
    fun handleOfferAndCreateAnswer(offer: SessionDescription) {
        peerConnection?.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                val constraints = MediaConstraints()
                peerConnection?.createAnswer(object : SdpObserverAdapter() {
                    override fun onCreateSuccess(sdp: SessionDescription) {
                        peerConnection?.setLocalDescription(SdpObserverAdapter(), sdp)
                        signaling.sendAnswer(sdp)
                    }
                }, constraints)
            }
        }, offer)
    }

    /** HOST: apply the Viewer's answer once received. */
    fun handleAnswer(answer: SessionDescription) {
        peerConnection?.setRemoteDescription(SdpObserverAdapter(), answer)
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun close() {
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        videoSource?.dispose()
        localVideoTrack?.dispose()
        dataChannel?.close()
        peerConnection?.close()
        peerConnection = null
    }
}

/** Convenience no-op SdpObserver base so callers only override what they need. */
open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {
        Log.w("SdpObserverAdapter", "Create failure: $error")
    }
    override fun onSetFailure(error: String?) {
        Log.w("SdpObserverAdapter", "Set failure: $error")
    }
}
