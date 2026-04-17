package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.JavaI420Buffer
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnection.IceConnectionState
import org.webrtc.PeerConnection.IceGatheringState
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WebRtcManager(
    private val context: Context,
    private val onLog: (String) -> Unit = {}
) {
    var onPeerCountChanged: ((Int) -> Unit)? = null

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    var videoSource: VideoSource? = null
        private set
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private val peers = ConcurrentHashMap<String, PeerConnection>()

    fun initialize() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "WebRtcManager.initialize() must be called on the main thread"
        }

        eglBase = EglBase.create()

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase!!.eglBaseContext,
            true,
            false // disable H264 high profile to avoid broken encoders on some devices
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        videoSource = factory!!.createVideoSource(false)
        videoTrack = factory!!.createVideoTrack("v0", videoSource)

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
        }
        audioSource = factory!!.createAudioSource(audioConstraints)
        audioTrack = factory!!.createAudioTrack("a0", audioSource)
    }

    fun hasPeers(): Boolean = peers.isNotEmpty()

    fun pushFrame(jpegBytes: ByteArray, width: Int, height: Int, rotationDegrees: Int) {
        val vs = videoSource ?: return
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return

        val w = bitmap.width
        val h = bitmap.height

        val argb = IntArray(w * h)
        bitmap.getPixels(argb, 0, w, 0, 0, w, h)
        bitmap.recycle()

        // Convert ARGB to I420
        val yPlane = ByteArray(w * h)
        val uPlane = ByteArray(w * h / 4)
        val vPlane = ByteArray(w * h / 4)
        var uIdx = 0; var vIdx = 0
        for (row in 0 until h) {
            for (col in 0 until w) {
                val pixel = argb[row * w + col]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yPlane[row * w + col] = y.coerceIn(0, 255).toByte()
                if (row % 2 == 0 && col % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    uPlane[uIdx++] = u.coerceIn(0, 255).toByte()
                    vPlane[vIdx++] = v.coerceIn(0, 255).toByte()
                }
            }
        }

        val strideY = w
        val strideUV = w / 2
        val i420Buffer = JavaI420Buffer.allocate(w, h)
        i420Buffer.dataY.put(yPlane); i420Buffer.dataY.rewind()
        i420Buffer.dataU.put(uPlane); i420Buffer.dataU.rewind()
        i420Buffer.dataV.put(vPlane); i420Buffer.dataV.rewind()

        val frame = VideoFrame(i420Buffer, rotationDegrees, System.nanoTime())
        vs.capturerObserver.onFrameCaptured(frame)
        frame.release()
    }

    suspend fun handleOffer(sessionId: String, offerSdp: String): String {
        val f = factory ?: throw IllegalStateException("WebRtcManager not initialized")
        val vt = videoTrack ?: throw IllegalStateException("VideoTrack not initialized")
        val at = audioTrack ?: throw IllegalStateException("AudioTrack not initialized")

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        }

        val iceGatheringDone = CompletableDeferred<Unit>()

        val observer = object : PeerConnection.Observer {
            override fun onIceGatheringChange(state: IceGatheringState) {
                if (state == IceGatheringState.COMPLETE) {
                    iceGatheringDone.complete(Unit)
                }
            }
            override fun onIceConnectionChange(state: IceConnectionState) {
                if (state == IceConnectionState.FAILED ||
                    state == IceConnectionState.CLOSED ||
                    state == IceConnectionState.DISCONNECTED) {
                    onLog("WebRTC session $sessionId ICE state: $state")
                    closeSession(sessionId)
                }
            }
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out MediaStream>?) {}
        }

        val pc = f.createPeerConnection(rtcConfig, observer)
            ?: throw IllegalStateException("Failed to create PeerConnection")

        pc.addTransceiver(
            vt,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
        )
        pc.addTransceiver(
            at,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
        )

        pc.setRemoteDescriptionSuspend(SessionDescription(SessionDescription.Type.OFFER, offerSdp))

        val answer = pc.createAnswerSuspend(MediaConstraints())
        pc.setLocalDescriptionSuspend(answer)

        // Wait for ICE gathering to complete (2s max on LAN)
        withTimeoutOrNull(2000L) { iceGatheringDone.await() }

        peers[sessionId] = pc
        onPeerCountChanged?.invoke(peers.size)
        onLog("WebRTC session $sessionId connected (total peers: ${peers.size})")

        return pc.localDescription?.description
            ?: throw IllegalStateException("No local description after ICE gathering")
    }

    fun closeSession(sessionId: String) {
        peers.remove(sessionId)?.let { pc ->
            try { pc.close() } catch (_: Exception) {}
            onLog("WebRTC session $sessionId closed (remaining peers: ${peers.size})")
            onPeerCountChanged?.invoke(peers.size)
        }
    }

    fun shutdown() {
        peers.keys.toList().forEach { closeSession(it) }
        audioTrack?.dispose()
        audioSource?.dispose()
        videoTrack?.dispose()
        videoSource?.dispose()
        factory?.dispose()
        eglBase?.release()
        audioTrack = null
        audioSource = null
        videoTrack = null
        videoSource = null
        factory = null
        eglBase = null
    }

    // ── Coroutine adapters for callback-based WebRTC API ──────────────────────

    private suspend fun PeerConnection.setRemoteDescriptionSuspend(sd: SessionDescription) =
        suspendCancellableCoroutine<Unit> { cont ->
            setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() = cont.resume(Unit)
                override fun onSetFailure(err: String?) =
                    cont.resumeWithException(Exception("setRemoteDescription failed: $err"))
                override fun onCreateSuccess(sd: SessionDescription?) = Unit
                override fun onCreateFailure(err: String?) = Unit
            }, sd)
        }

    private suspend fun PeerConnection.createAnswerSuspend(constraints: MediaConstraints) =
        suspendCancellableCoroutine<SessionDescription> { cont ->
            createAnswer(object : SdpObserver {
                override fun onCreateSuccess(sd: SessionDescription?) {
                    if (sd != null) cont.resume(sd)
                    else cont.resumeWithException(Exception("createAnswer returned null SDP"))
                }
                override fun onCreateFailure(err: String?) =
                    cont.resumeWithException(Exception("createAnswer failed: $err"))
                override fun onSetSuccess() = Unit
                override fun onSetFailure(err: String?) = Unit
            }, constraints)
        }

    private suspend fun PeerConnection.setLocalDescriptionSuspend(sd: SessionDescription) =
        suspendCancellableCoroutine<Unit> { cont ->
            setLocalDescription(object : SdpObserver {
                override fun onSetSuccess() = cont.resume(Unit)
                override fun onSetFailure(err: String?) =
                    cont.resumeWithException(Exception("setLocalDescription failed: $err"))
                override fun onCreateSuccess(sd: SessionDescription?) = Unit
                override fun onCreateFailure(err: String?) = Unit
            }, sd)
        }
}
