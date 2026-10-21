package com.example.cctv.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.*
import java.util.concurrent.Executors

/**
 * WebRtcManager
 *
 * Implements real-time Peer-to-Peer streaming over WebRTC (UDP/SRTP).
 * Integrates native Android MediaCodec hardware encoding (via [DefaultVideoEncoderFactory])
 * and hardware decoding (via [DefaultVideoDecoderFactory]) to eliminate CPU lag and achieve
 * sub-second live video playback.
 */
class WebRtcManager(private val context: Context) {

    companion object {
        private const val TAG = "WebRtcManager"
        const val VIDEO_TRACK_ID = "cctv_video_track"
        const val AUDIO_TRACK_ID = "cctv_audio_track"
        const val STREAM_LABEL = "cctv_live_stream"
    }

    val rootEglBase: EglBase = EglBase.create()
    private val executor = Executors.newSingleThreadExecutor()

    private var peerConnectionFactory: PeerConnectionFactory? = null
    var peerConnection: PeerConnection? = null
        private set

    private var videoSource: VideoSource? = null
    var localVideoTrack: VideoTrack? = null
        private set

    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoCapturer: VideoCapturer? = null

    // Listeners for signaling
    var onLocalIceCandidate: ((IceCandidate) -> Unit)? = null
    var onLocalSessionDescription: ((SessionDescription) -> Unit)? = null
    var onConnectionStateChanged: ((PeerConnection.PeerConnectionState) -> Unit)? = null
    var onRemoteTrackReceived: ((VideoTrack) -> Unit)? = null

    init {
        initPeerConnectionFactory()
    }

    /**
     * Initializes PeerConnectionFactory with native hardware acceleration enabled.
     */
    private fun initPeerConnectionFactory() {
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        // Native MediaCodec Hardware Acceleration for Video Compression & Decompression
        val encoderFactory = DefaultVideoEncoderFactory(
            rootEglBase.eglBaseContext,
            /* enableIntelVp8 = */ true,
            /* enableH264HighProfile = */ true
        )
        val decoderFactory = DefaultVideoDecoderFactory(rootEglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()

        Log.i(TAG, "WebRTC PeerConnectionFactory initialized with hardware encoders/decoders.")
    }

    /**
     * Creates a new WebRTC PeerConnection configured with standard STUN servers.
     */
    fun createPeerConnection(isSender: Boolean): PeerConnection? {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // Prioritize low-latency direct UDP streaming
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED // Enforce UDP
        }

        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "Local ICE Candidate generated: ${candidate.sdp}")
                onLocalIceCandidate?.invoke(candidate)
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}

            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                Log.d(TAG, "Signaling state: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "ICE Connection state: $state")
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                Log.i(TAG, "PeerConnection state changed: $newState")
                onConnectionStateChanged?.invoke(newState)
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                Log.d(TAG, "ICE Gathering state: $state")
            }

            override fun onAddStream(stream: MediaStream) {
                Log.i(TAG, "Remote media stream added with ${stream.videoTracks.size} video tracks")
                if (stream.videoTracks.isNotEmpty()) {
                    onRemoteTrackReceived?.invoke(stream.videoTracks[0])
                }
            }

            override fun onRemoveStream(stream: MediaStream) {}

            override fun onDataChannel(dataChannel: DataChannel) {}

            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                val track = receiver.track()
                if (track is VideoTrack) {
                    Log.i(TAG, "Remote VideoTrack received via onAddTrack")
                    onRemoteTrackReceived?.invoke(track)
                }
            }
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, observer)
        return peerConnection
    }

    /**
     * Sender Setup: Creates and attaches the camera video track at 640x480 @ 15 FPS.
     */
    fun startCameraCapture(useFrontLens: Boolean = false) {
        val factory = peerConnectionFactory ?: return

        surfaceTextureHelper = SurfaceTextureHelper.create("WebRtcCaptureThread", rootEglBase.eglBaseContext)
        videoSource = factory.createVideoSource(false)

        // Lock resolution to 640x480 at 15 FPS to prevent CPU/thermal bottlenecks
        videoSource?.adaptOutputFormat(640, 480, 15)

        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames
        val selectedName = deviceNames.find {
            if (useFrontLens) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it)
        } ?: deviceNames.firstOrNull()

        if (selectedName != null) {
            val capturer = enumerator.createCapturer(selectedName, null)
            videoCapturer = capturer
            capturer.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
            capturer.startCapture(640, 480, 15)
            Log.i(TAG, "CameraVideoCapturer started at 640x480 @ 15 FPS on device $selectedName")
        }

        localVideoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource)
        localVideoTrack?.setEnabled(true)

        // Add track to peer connection
        peerConnection?.addTrack(localVideoTrack, listOf(STREAM_LABEL))
    }

    /**
     * Creates an SDP Offer (Sender initiates the call).
     */
    fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }

        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                Log.i(TAG, "SDP Offer created successfully")
                if (desc != null) {
                    peerConnection?.setLocalDescription(SimpleSdpObserver(), desc)
                    onLocalSessionDescription?.invoke(desc)
                }
            }
        }, constraints)
    }

    /**
     * Creates an SDP Answer (Viewer answers the call).
     */
    fun createAnswer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }

        peerConnection?.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                Log.i(TAG, "SDP Answer created successfully")
                if (desc != null) {
                    peerConnection?.setLocalDescription(SimpleSdpObserver(), desc)
                    onLocalSessionDescription?.invoke(desc)
                }
            }
        }, constraints)
    }

    /**
     * Sets remote description (Offer received by viewer, or Answer received by sender).
     */
    fun setRemoteDescription(sdpType: SessionDescription.Type, sdpDescription: String, onDone: (() -> Unit)? = null) {
        val desc = SessionDescription(sdpType, sdpDescription)
        peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                Log.i(TAG, "Remote description set successfully: ${desc.type}")
                onDone?.invoke()
            }
        }, desc)
    }

    /**
     * Adds an incoming ICE Candidate to the active connection.
     */
    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    /**
     * Configures a [SurfaceViewRenderer] for hardware-accelerated playback with zero latency.
     */
    fun setupSurfaceRenderer(renderer: SurfaceViewRenderer) {
        renderer.init(rootEglBase.eglBaseContext, null)
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        renderer.setEnableHardwareScaler(true)
        renderer.setMirror(false)
    }

    fun close() {
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            surfaceTextureHelper?.dispose()
            localVideoTrack?.dispose()
            videoSource?.dispose()
            peerConnection?.close()
            peerConnectionFactory?.dispose()
            rootEglBase.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing WebRtcManager", e)
        }
    }

    open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) { Log.e(TAG, "SDP onCreateFailure: $p0") }
        override fun onSetFailure(p0: String?) { Log.e(TAG, "SDP onSetFailure: $p0") }
    }
}
