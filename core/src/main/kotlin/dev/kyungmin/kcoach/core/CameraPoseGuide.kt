package dev.kyungmin.kcoach.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sign

/**
 * Start-pose pictogram ("신호등 졸라맨") that follows the user instead of sitting fixed on screen.
 * It is only a picture: readiness stays with [CameraCaptureGuide] angle checks, never with overlap.
 *
 * Limb angles are degrees from straight down (gravity = image down); positive points toward the
 * side the body faces. Torso lean is from vertical, positive toward the facing side; lying = 90.
 */
enum class CameraPoseFamily(
    val front: Boolean,
    val torsoLean: Double,
    val arm: Pair<Double, Double>,
    val leg: Pair<Double, Double>? = null,
) {
    STAND_ARMS_DOWN_SIDE(false, 0.0, 0.0 to 4.0),
    STAND_ARMS_DOWN_FRONT(true, 0.0, 8.0 to 6.0),
    STAND_ELBOW_BENT_SIDE(false, 8.0, -6.0 to 100.0),
    OVERHEAD_EXTENSION_SIDE(false, 0.0, 170.0 to -70.0),
    CABLE_OVERHEAD_EXTENSION_SIDE(false, 30.0, 130.0 to -140.0),
    EXTERNAL_ROTATION_SIDE(false, 0.0, 0.0 to 90.0),
    PREACHER_SIDE(false, 10.0, 45.0 to 40.0),
    INCLINE_ARMS_DOWN_SIDE(false, 45.0, 0.0 to 0.0),
    INCLINE_PRESS_SIDE(false, 45.0, 180.0 to 180.0),
    SEATED_ARMS_UP_SIDE(false, -12.0, 150.0 to 165.0),
    SEATED_PRESS_FRONT(true, 0.0, 80.0 to 175.0),
    SEATED_ARMS_FORWARD_SIDE(false, 2.0, 85.0 to 88.0),
    SEATED_CHEST_PRESS_SIDE(false, -5.0, -30.0 to 80.0),
    ARMS_OPEN_FRONT(true, 0.0, 80.0 to 85.0),
    // Hands together in front of the chest. The fly gate measures elbow-shoulder-opposite shoulder, which a side view hides.
    FLY_CLOSED_FRONT(true, 0.0, 0.0 to -75.0),
    BENT_OVER_SIDE(false, 62.0, 0.0 to 0.0),
    LYING_ARMS_UP_SIDE(false, 90.0, 180.0 to 180.0),
    LYING_EXTENSION_SIDE(false, 90.0, 170.0 to 70.0),
    PULLOVER_SIDE(false, 90.0, 100.0 to 100.0),
    SQUAT_SIDE(false, 4.0, -40.0 to 160.0, 2.0 to -2.0);

    val lowerBody get() = leg != null
}

data class GuidePoint(val x: Double, val y: Double)

/** The user's measured anchor in frame pixels. [other] is the opposite shoulder (same point if unseen). */
data class GuideAnchor(val shoulder: GuidePoint, val other: GuidePoint, val bodyHeight: Double, val facing: Int)

data class GuideLimb(val points: List<GuidePoint>, val width: Double, val primary: Boolean)

/** [torso] is a two-point capsule (side views) or a four-point outline (front views). */
data class GuideFigure(val head: GuidePoint, val headRadius: Double, val torso: List<GuidePoint>, val torsoWidth: Double,
    val limbs: List<GuideLimb>, val lowerBody: Boolean)

object CameraPoseGuide {
    // Drillis-Contini segment lengths as a fraction of standing height.
    private const val UPPER_ARM = .186; private const val FOREARM = .146; private const val SHOULDER_WIDTH = .259
    private const val TORSO = .288; private const val HIP_WIDTH = .191; private const val THIGH = .245; private const val SHANK = .246
    private const val HEAD_RADIUS = .065; private const val HEAD_OFFSET = .115

    fun familyFor(exerciseId: String): CameraPoseFamily? {
        val id = ExerciseMotionCatalog.normalizeExerciseId(exerciseId)
        return when (ExerciseMeasurementContracts.resolve(id).recipe) {
            MeasurementRecipe.CURL -> when (id) {
                "preacher_curl" -> CameraPoseFamily.PREACHER_SIDE
                "incline_curl", "incline_hammer_curl" -> CameraPoseFamily.INCLINE_ARMS_DOWN_SIDE
                else -> CameraPoseFamily.STAND_ARMS_DOWN_SIDE
            }
            MeasurementRecipe.ROW -> if (id in setOf("seated_row", "cable_seated_row", "high_row_machine"))
                CameraPoseFamily.SEATED_ARMS_FORWARD_SIDE else CameraPoseFamily.BENT_OVER_SIDE
            MeasurementRecipe.VERTICAL_PULL -> CameraPoseFamily.SEATED_ARMS_UP_SIDE
            MeasurementRecipe.CHEST_PRESS -> when (id) {
                "chest_press" -> CameraPoseFamily.SEATED_CHEST_PRESS_SIDE
                "incline_bench" -> CameraPoseFamily.INCLINE_PRESS_SIDE
                else -> CameraPoseFamily.LYING_ARMS_UP_SIDE
            }
            MeasurementRecipe.SHOULDER_PRESS -> CameraPoseFamily.SEATED_PRESS_FRONT
            MeasurementRecipe.LATERAL_RAISE -> CameraPoseFamily.STAND_ARMS_DOWN_FRONT
            MeasurementRecipe.FLY -> if (id == "machine_fly") CameraPoseFamily.ARMS_OPEN_FRONT else CameraPoseFamily.FLY_CLOSED_FRONT
            MeasurementRecipe.PULLOVER -> CameraPoseFamily.PULLOVER_SIDE
            MeasurementRecipe.TRICEPS_EXTENSION -> when (id) {
                "cable_overhead_extension", "rope_overhead_extension" -> CameraPoseFamily.CABLE_OVERHEAD_EXTENSION_SIDE
                "dumbbell_triceps_extension" -> CameraPoseFamily.OVERHEAD_EXTENSION_SIDE
                "ez_lying_extension" -> CameraPoseFamily.LYING_EXTENSION_SIDE
                else -> CameraPoseFamily.STAND_ELBOW_BENT_SIDE
            }
            MeasurementRecipe.EXTERNAL_ROTATION -> CameraPoseFamily.EXTERNAL_ROTATION_SIDE
            MeasurementRecipe.SQUAT -> CameraPoseFamily.SQUAT_SIDE
            MeasurementRecipe.MANUAL -> null
        }
    }

    /** Anchor from normalized landmarks of a [width]x[height] frame, or null when the anchor joints are not seen. */
    fun measure(points: List<CameraLandmark>, width: Double, height: Double, family: CameraPoseFamily, left: Boolean): GuideAnchor? {
        fun seen(i: Int) = points.getOrNull(i)?.let { minOf(it.visibility ?: 0.0, it.presence ?: 0.0) >= .5 &&
            it.x in -.05..1.05 && it.y in -.05..1.05 } == true
        fun px(i: Int) = GuidePoint(points[i].x * width, points[i].y * height)
        val shoulderIndex = if (left) 11 else 12; val otherIndex = if (left) 12 else 11
        if (family.front) {
            if (!seen(11) || !seen(12)) return null
            val shoulder = px(shoulderIndex); val other = px(otherIndex)
            val span = dist(shoulder, other).takeIf { it > 1.0 } ?: return null
            return GuideAnchor(shoulder, other, span / SHOULDER_WIDTH, 1)
        }
        val elbowIndex = if (left) 13 else 14; val hipIndex = if (left) 23 else 24
        if (!seen(shoulderIndex) || !seen(elbowIndex)) return null
        val shoulder = px(shoulderIndex)
        val upperArm = dist(shoulder, px(elbowIndex)).takeIf { it > 1.0 } ?: return null
        val hip = if (seen(hipIndex)) px(hipIndex) else null
        // The upper arm can foreshorten toward the camera; a visible torso keeps the scale honest.
        val bodyHeight = max(upperArm / UPPER_ARM, hip?.let { dist(shoulder, it) / TORSO } ?: 0.0)
        val reclined = abs(family.torsoLean) >= 30.0
        val facing = when {
            reclined && hip != null && abs(shoulder.x - hip.x) > 2.0 -> sign(shoulder.x - hip.x).toInt()
            seen(0) && abs(px(0).x - shoulder.x) > 2.0 -> sign(px(0).x - shoulder.x).toInt()
            else -> 1
        }
        return GuideAnchor(shoulder, if (seen(otherIndex)) px(otherIndex) else shoulder, bodyHeight, facing)
    }

    fun build(family: CameraPoseFamily, anchor: GuideAnchor): GuideFigure {
        val h = anchor.bodyHeight
        val (upper, fore) = family.arm
        if (family.front) {
            val limbs = listOf(anchor.shoulder to anchor.other, anchor.other to anchor.shoulder).map { (s, opposite) ->
                val out = sign(s.x - opposite.x)
                val elbow = s + outward(upper, out) * (UPPER_ARM * h)
                GuideLimb(listOf(s, elbow, elbow + outward(fore, out) * (FOREARM * h)), .055 * h, true)
            }
            val inset = (SHOULDER_WIDTH - HIP_WIDTH) / 2 * h
            fun hipUnder(s: GuidePoint, toward: GuidePoint) = GuidePoint(s.x + sign(toward.x - s.x) * inset, s.y + TORSO * h)
            val mid = GuidePoint((anchor.shoulder.x + anchor.other.x) / 2, (anchor.shoulder.y + anchor.other.y) / 2)
            return GuideFigure(GuidePoint(mid.x, mid.y - HEAD_OFFSET * h), HEAD_RADIUS * h,
                listOf(anchor.shoulder, anchor.other, hipUnder(anchor.other, anchor.shoulder), hipUnder(anchor.shoulder, anchor.other)),
                0.0, limbs, false)
        }
        val f = anchor.facing.toDouble()
        fun dir(degrees: Double) = Math.toRadians(degrees).let { GuidePoint(f * sin(it), cos(it)) }
        val up = Math.toRadians(family.torsoLean).let { GuidePoint(f * sin(it), -cos(it)) }
        val hip = anchor.shoulder + up * (-TORSO * h)
        val far = if (anchor.other == anchor.shoulder) anchor.shoulder + GuidePoint(-f * .03 * h, -.005 * h) else anchor.other
        val limbs = mutableListOf<GuideLimb>()
        for ((s, primary) in listOf(far to false, anchor.shoulder to true)) {
            val elbow = s + dir(upper) * (UPPER_ARM * h)
            limbs += GuideLimb(listOf(s, elbow, elbow + dir(fore) * (FOREARM * h)), .055 * h, primary)
        }
        family.leg?.let { (thigh, shank) ->
            for ((offset, primary) in listOf(-.03 to false, 0.0 to true)) {
                val start = hip + GuidePoint(f * offset * h, 0.0)
                val knee = start + dir(thigh) * (THIGH * h)
                limbs += GuideLimb(listOf(start, knee, knee + dir(shank) * (SHANK * h)), .07 * h, primary)
            }
        }
        return GuideFigure(anchor.shoulder + up * (HEAD_OFFSET * h), HEAD_RADIUS * h, listOf(anchor.shoulder, hip), .12 * h,
            limbs, family.lowerBody)
    }

    private fun outward(degrees: Double, out: Double) = Math.toRadians(degrees).let { GuidePoint(out * sin(it), cos(it)) }
    private fun dist(a: GuidePoint, b: GuidePoint) = hypot(a.x - b.x, a.y - b.y)
    private operator fun GuidePoint.plus(o: GuidePoint) = GuidePoint(x + o.x, y + o.y)
    private operator fun GuidePoint.times(k: Double) = GuidePoint(x * k, y * k)
}

/** Light exponential smoothing so the pictogram does not jitter; it lingers briefly when tracking drops. */
class CameraPoseGuideSmoother(private val alpha: Double = .35, private val holdMs: Long = 400) {
    private var last: GuideAnchor? = null
    private var seenAtMs = 0L

    fun update(anchor: GuideAnchor?, nowMs: Long): GuideAnchor? {
        if (anchor == null) {
            if (last != null && nowMs - seenAtMs > holdMs) last = null
            return last
        }
        val previous = last
        last = if (previous == null || previous.facing != anchor.facing) anchor else GuideAnchor(
            mix(previous.shoulder, anchor.shoulder), mix(previous.other, anchor.other),
            previous.bodyHeight + alpha * (anchor.bodyHeight - previous.bodyHeight), anchor.facing)
        seenAtMs = nowMs
        return last
    }

    private fun mix(a: GuidePoint, b: GuidePoint) = GuidePoint(a.x + alpha * (b.x - a.x), a.y + alpha * (b.y - a.y))
}
