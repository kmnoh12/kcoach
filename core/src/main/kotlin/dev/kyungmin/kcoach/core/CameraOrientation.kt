package dev.kyungmin.kcoach.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Camera-relative anatomical yaw: front 0, back 180; negative brings the left side nearer.
 * These are conservative capture gates, not validated MediaPipe angular accuracy estimates.
 */
data class CameraViewTarget(val degrees: Double, val nearArm: Boolean = true) {
    val label: String get() = when (degrees) {
        CameraOrientationPolicy.FRONT -> "정면"
        CameraOrientationPolicy.REAR_OBLIQUE -> "후면 45° 사선"
        else -> "45° 사선"
    }
}

object CameraOrientationPolicy {
    const val FRONT = 0.0
    const val OBLIQUE = 45.0
    const val REAR_OBLIQUE = 135.0
    // ±20 allows noisy monocular yaw estimates while still excluding front and pure side views.
    // A 3-degree release margin suppresses flicker; field accuracy remains unverified.
    const val TOLERANCE = 20.0
    const val HYSTERESIS = 3.0
    const val WINDOW = 9
    const val MIN_FRAMES = 5
    const val MAX_GAP_NS = 500_000_000L
    val torsoJoints = setOf(11, 12, 23, 24)
    fun target(id: String): CameraViewTarget = when (ExerciseMeasurementContracts.resolve(id).recipe) {
        MeasurementRecipe.VERTICAL_PULL -> CameraViewTarget(REAR_OBLIQUE)
        MeasurementRecipe.FLY, MeasurementRecipe.LATERAL_RAISE -> CameraViewTarget(FRONT, false)
        MeasurementRecipe.MANUAL -> if (id in setOf("hip_adduction", "hip_abduction")) CameraViewTarget(FRONT, false)
            else CameraViewTarget(OBLIQUE, false)
        MeasurementRecipe.SQUAT -> CameraViewTarget(OBLIQUE, false)
        else -> CameraViewTarget(OBLIQUE)
    }
}

data class CameraOrientation(val yaw: Double?, val accepted: Boolean, val message: String?, val rawYaw: Double? = yaw)

object CameraTorsoOrientation {
    fun evaluate(image: List<CameraLandmark>, world: List<CameraLandmark>, left: Boolean,
        target: CameraViewTarget): CameraOrientation {
        fun blocked(message: String, yaw: Double? = null) = CameraOrientation(yaw, false, message)
        if (image.size != 33 || world.size != 33 || CameraOrientationPolicy.torsoJoints.any { i ->
                val p = image[i]; val w = world[i]
                minOf(p.visibility ?: 0.0, p.presence ?: 0.0, w.visibility ?: 1.0, w.presence ?: 1.0) < CameraCaptureGuide.MIN_CONFIDENCE ||
                    p.x !in .02.. .98 || p.y !in .02.. .98 || !listOf(w.x, w.y, w.z).all(Double::isFinite)
            }) return blocked("양어깨와 골반이 보이게 해 주세요")
        // Both bilateral axes are needed: twisting shoulders over a front-facing pelvis is not a body turn.
        fun yaw(a: Int, b: Int): Double? {
            val dx = world[a].x - world[b].x; val dz = world[a].z - world[b].z
            if (hypot(dx, dz) < .08) return null
            return Math.toDegrees(atan2(dz, dx))
        }
        val shoulder = yaw(11, 12) ?: return blocked("몸 방향을 확인할 수 없어요")
        val hip = yaw(23, 24) ?: return blocked("몸 방향을 확인할 수 없어요")
        if (abs(shoulder - hip) > 25) return blocked("어깨와 골반을 같은 방향으로 맞춰 주세요")
        val value = (shoulder + hip) / 2
        if (target.nearArm && abs(value) > CameraOrientationPolicy.TOLERANCE) {
            val s = if (left) 11 else 12; val other = if (left) 12 else 11
            if (world[s].z >= world[other].z - .025) return blocked("워치를 찬 팔이 카메라 가까이 오게 돌려 주세요", value)
            for (i in listOf(if (left) 13 else 14, if (left) 15 else 16)) {
                val p = image[i]; val w = world[i]
                val torso = CameraOrientationPolicy.torsoJoints.map { image[it] }
                val behindTorso = p.x in torso.minOf { it.x }..torso.maxOf { it.x } &&
                    p.y in torso.minOf { it.y }..torso.maxOf { it.y } && w.z > world[s].z + .03
                if (minOf(p.visibility ?: 0.0, p.presence ?: 0.0, w.visibility ?: 1.0, w.presence ?: 1.0) < CameraCaptureGuide.MIN_CONFIDENCE ||
                    !listOf(w.x, w.y, w.z).all(Double::isFinite) || behindTorso)
                    return blocked("팔이 몸에 가려져요", value)
            }
        }
        return judge(value, target)
    }

    internal fun judge(yaw: Double, target: CameraViewTarget, tolerance: Double = CameraOrientationPolicy.TOLERANCE): CameraOrientation {
        val delta = abs(yaw) - target.degrees
        val message = when {
            delta < -tolerance -> "몸을 더 돌려 주세요"
            delta > tolerance -> "조금만 덜 돌려 주세요"
            else -> null
        }
        return CameraOrientation(yaw, message == null, message)
    }
}

/** Missing people/joints and source-clock gaps clear history. Never carries green across a dropout. */
class CameraOrientationTracker {
    private val recent = ArrayDeque<Double>()
    private var lastNs: Long? = null
    private var accepted = false
    @Synchronized fun reset() { recent.clear(); accepted = false; lastNs = null }
    @Synchronized fun observe(ns: Long, image: List<CameraLandmark>?, world: List<CameraLandmark>?, left: Boolean,
        target: CameraViewTarget): CameraOrientation {
        if (lastNs?.let { ns <= it || ns - it > CameraOrientationPolicy.MAX_GAP_NS } == true) reset()
        lastNs = ns
        if (image == null || world == null) { reset(); return CameraOrientation(null, false, "한 사람의 전신 관절이 보이게 해 주세요") }
        val raw = CameraTorsoOrientation.evaluate(image, world, left, target)
        if (raw.yaw == null || raw.message in setOf("팔이 몸에 가려져요", "워치를 찬 팔이 카메라 가까이 오게 돌려 주세요")) {
            reset(); return raw
        }
        recent.addLast(raw.yaw)
        if (recent.size > CameraOrientationPolicy.WINDOW) recent.removeFirst()
        if (recent.size < CameraOrientationPolicy.MIN_FRAMES) return raw.copy(accepted = false, message = "몸 방향을 확인하는 중이에요")
        val median = recent.sorted()[recent.size / 2]
        val judged = CameraTorsoOrientation.judge(median, target, CameraOrientationPolicy.TOLERANCE +
            if (accepted) CameraOrientationPolicy.HYSTERESIS else 0.0)
        // A small isolated yaw spike can use the stable median; a clearly wrong view cannot.
        // Joint visibility and near-arm occlusion have already rejected the frame above.
        val currentTolerance = CameraOrientationPolicy.TOLERANCE + CameraOrientationPolicy.HYSTERESIS + if (accepted) 5.0 else 0.0
        accepted = judged.accepted && abs(abs(raw.yaw) - target.degrees) <= currentTolerance
        return judged.copy(accepted = accepted, rawYaw = raw.yaw,
            message = if (accepted) null else judged.message ?: raw.message ?: "몸 방향을 다시 맞춰 주세요")
    }
}
