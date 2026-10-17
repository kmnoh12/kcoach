package dev.kyungmin.kcoach.core

import kotlin.math.hypot

/** Capture control only: never counts repetitions or manufactures learning labels. */
class CameraCaptureEnd(private val holdNs: Long = 2_500_000_000L) {
    private var lastNs: Long? = null
    private var initial: List<CameraLandmark>? = null
    private var moved = false
    private var anchor: List<CameraLandmark>? = null
    private var stillSince: Long? = null
    var endCameraNs: Long? = null
        private set

    /** Missing/ambiguous poses and gaps cannot count as observed stillness. Camera time, never processing time. */
    fun observe(ns: Long, image: List<CameraLandmark>?, world: List<CameraLandmark>?,
        guide: CameraCaptureGuide, left: Boolean, allowEnd: Boolean = true): Long? {
        endCameraNs?.let { return it }
        val previous = lastNs
        lastNs = ns
        if (previous != null && (ns <= previous || ns - previous > 500_000_000L)) resetHold()
        if (image == null || world == null || !guide.visible(image)) { resetHold(); return null }
        val baseline = initial ?: image.also { initial = it }
        // The same tolerance is used for departure and stillness, not a rep ROM threshold.
        if (drift(baseline, image, guide.required) > POSITION_DRIFT || !guide.startReady(image, world, left)) moved = true
        if (!allowEnd || !moved || !guide.startReady(image, world, left) || drift(baseline, image, guide.required) > POSITION_DRIFT) {
            resetHold(); return null
        }
        val held = anchor
        if (held == null || drift(held, image, guide.required) > POSITION_DRIFT) {
            anchor = image; stillSince = ns
        }
        val since = stillSince ?: return null
        if (ns - since >= holdNs) endCameraNs = since
        return endCameraNs
    }

    private fun resetHold() { anchor = null; stillSince = null }
    private fun drift(a: List<CameraLandmark>, b: List<CameraLandmark>, joints: Set<Int>) =
        joints.maxOfOrNull { i ->
            val p = a.getOrNull(i); val q = b.getOrNull(i)
            if (p == null || q == null) Double.POSITIVE_INFINITY else hypot(p.x - q.x, p.y - q.y)
        } ?: Double.POSITIVE_INFINITY

    companion object {
        const val TARGET_REPS = 10
        /** Normalized image distance, provisional capture-control tolerance; requires phone validation. */
        private const val POSITION_DRIFT = 0.02
    }
}
