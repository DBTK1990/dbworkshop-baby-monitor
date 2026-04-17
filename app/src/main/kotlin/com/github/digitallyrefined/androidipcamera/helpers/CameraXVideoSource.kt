package com.github.digitallyrefined.androidipcamera.helpers

class CameraXVideoSource(private val manager: WebRtcManager) {
    fun pushFrame(jpegBytes: ByteArray, width: Int, height: Int, rotationDegrees: Int) {
        if (!manager.hasPeers()) return
        manager.pushFrame(jpegBytes, width, height, rotationDegrees)
    }
}
