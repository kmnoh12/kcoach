package dev.kyungmin.kcoach.core

enum class CameraTimestampSource { PHONE_ELAPSED_REALTIME, UNKNOWN }

/** Preserve MediaPipe's two coordinate systems and optional confidences separately. */
data class CameraLandmark(val x: Double, val y: Double, val z: Double, val visibility: Double?, val presence: Double?) {
    init {
        require(listOf(x, y, z).all(Double::isFinite))
        require(visibility == null || visibility.isFinite() && visibility in 0.0..1.0)
        require(presence == null || presence.isFinite() && presence in 0.0..1.0)
    }
}

data class CameraPoseFrame(
    val frameIndex: Long,
    val cameraTimestampNs: Long,
    val phoneReceivedNs: Long,
    val modelTimestampMs: Long,
    val modelCompletedNs: Long,
    val videoPresentationUs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val previewMirrored: Boolean,
    val timestampSource: CameraTimestampSource,
    val phoneClockId: String,
    val imageLandmarks: List<List<CameraLandmark>>,
    val worldLandmarks: List<List<CameraLandmark>>,
    val videoFrameWritten: Boolean,
    val error: String? = null,
) {
    init {
        require(frameIndex >= 0 && listOf(cameraTimestampNs, phoneReceivedNs, modelTimestampMs, videoPresentationUs).all { it >= 0 })
        require(modelCompletedNs >= phoneReceivedNs && width > 0 && height > 0 && phoneClockId.isNotBlank())
        require(rotationDegrees in setOf(0, 90, 180, 270))
        require(imageLandmarks.size <= 2 && worldLandmarks.size == imageLandmarks.size)
        require((imageLandmarks + worldLandmarks).all { it.size == 33 })
    }
    val phoneFrameTimeNs: Long? get() = cameraTimestampNs.takeIf { timestampSource == CameraTimestampSource.PHONE_ELAPSED_REALTIME }
    val uniquelyVisible: Boolean get() = error == null && imageLandmarks.size == 1
}
