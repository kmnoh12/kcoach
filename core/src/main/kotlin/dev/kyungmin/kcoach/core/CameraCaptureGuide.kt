package dev.kyungmin.kcoach.core

/** Anatomical MediaPipe indices, independent of the mirrored selfie preview. Guides are not accuracy claims. */
data class CameraCaptureGuide(val id: String, val direction: String, val framing: String, val required: Set<Int>,
    val startDescription: String, val metric: CameraMotion?, val readyElbowBand: ClosedFloatingPointRange<Double>? = null,
    val headline: String = direction, val startHint: String? = null) {
    /** How long the start pose must hold before capture may start; without an angle check it needs a steadier hold. */
    val readyHoldMs: Long get() = if (metric == null) 1000L else 200L

    /** Start-pose readiness: the movement metric at rest, or for rotation lifts only the elbow set-up. */
    fun startReady(image: List<CameraLandmark>, world: List<CameraLandmark>, left: Boolean): Boolean {
        if (!visible(image)) return false
        metric?.let { motion -> return observedMetric(image, world, left)?.let(motion::atRest) == true }
        val band = readyElbowBand ?: return true
        if (world.size != 33) return false
        val points = world.mapIndexed { i, p -> PosePoint(p.x, p.y, p.z, minOf(image[i].visibility ?: 0.0, image[i].presence ?: 0.0)) }
        return UpperBodyPoseGeometry.angle(points, if (left) 11 else 12, if (left) 13 else 14, if (left) 15 else 16)?.let { it in band } == true
    }
    fun visible(points: List<CameraLandmark>): Boolean = required.isNotEmpty() && required.all { index ->
        val point = points.getOrNull(index) ?: return@all false
        minOf(point.visibility ?: 0.0, point.presence ?: 0.0) >= 0.65 && point.x in 0.02..0.98 && point.y in 0.02..0.98
    }
    fun observedMetric(image: List<CameraLandmark>, world: List<CameraLandmark>, left: Boolean): Double? {
        val motion = metric ?: return null
        if (image.size != 33 || world.size != 33 || !visible(image)) return null
        val points = world.mapIndexed { i, p -> PosePoint(p.x, p.y, p.z,
            minOf(image[i].visibility ?: 0.0, image[i].presence ?: 0.0,
                p.visibility ?: 1.0, p.presence ?: 1.0)) }
        val value = UpperBodyPoseGeometry.metric(points, motion, left) ?: return null
        if (motion == CameraMotion.SQUAT_KNEE_FLEXION && motion.atRest(value)) {
            val shoulderY = (image[11].y + image[12].y) / 2
            val hipY = (image[23].y + image[24].y) / 2
            if (!(image[0].y < shoulderY && shoulderY < hipY &&
                hipY < image[if (left) 25 else 26].y && image[if (left) 25 else 26].y < image[if (left) 27 else 28].y)) return null
        }
        return value
    }
    companion object {
        fun forExercise(id: String, left: Boolean): CameraCaptureGuide {
            val contract = ExerciseMeasurementContracts.resolve(id)
            val recipe = contract.recipe
            val side = if (left) "왼팔" else "오른팔"
            val shoulder = if (left) 11 else 12; val elbow = if (left) 13 else 14
            val wrist = if (left) 15 else 16; val hip = if (left) 23 else 24
            val required = when {
                recipe == MeasurementRecipe.MANUAL && id in setOf("leg_press", "seated_leg_curl", "leg_extension") -> setOf(hip, if (left) 25 else 26, if (left) 27 else 28)
                recipe == MeasurementRecipe.MANUAL && id in setOf("hip_adduction", "hip_abduction") -> setOf(23, 24, 25, 26, 27, 28)
                recipe == MeasurementRecipe.MANUAL -> setOf(11, 12, 15, 16, 23, 24, 27, 28)
                recipe == MeasurementRecipe.SQUAT -> recipe.cameraLandmarks + 0
                recipe == MeasurementRecipe.FLY -> setOf(shoulder, elbow, wrist, if (left) 12 else 11)
                recipe == MeasurementRecipe.EXTERNAL_ROTATION -> setOf(shoulder, elbow, wrist)
                // Elbow angles use shoulder-elbow-wrist only: an upper-body selfie may crop the hip.
                recipe in setOf(MeasurementRecipe.CURL, MeasurementRecipe.ROW, MeasurementRecipe.VERTICAL_PULL,
                    MeasurementRecipe.SHOULDER_PRESS, MeasurementRecipe.TRICEPS_EXTENSION, MeasurementRecipe.CHEST_PRESS) ->
                    setOf(shoulder, elbow, wrist)
                else -> setOf(shoulder, elbow, wrist, hip)
            }
            val direction = when (recipe) {
                MeasurementRecipe.VERTICAL_PULL -> "후면 사선 · $side 팔과 상체, 기구에 가리지 않는 위치"
                MeasurementRecipe.LATERAL_RAISE, MeasurementRecipe.FLY -> "정면/사선 · $side 팔 전체와 골반"
                MeasurementRecipe.SQUAT -> "측면 · 머리부터 발까지 전신"
                MeasurementRecipe.MANUAL -> if (id in setOf("hip_adduction", "hip_abduction")) "정면 · 골반부터 양발까지" else "측면/사선 · 수행 부위와 기구의 움직임 전체"
                else -> "$side 쪽 측면/사선 · 어깨부터 골반까지, 팔과 기구가 겹치지 않는 위치"
            }
            val metric = when (recipe) {
                MeasurementRecipe.CURL, MeasurementRecipe.ROW, MeasurementRecipe.VERTICAL_PULL -> CameraMotion.ELBOW_FOLD
                MeasurementRecipe.SHOULDER_PRESS, MeasurementRecipe.TRICEPS_EXTENSION -> CameraMotion.ELBOW_PRESS
                MeasurementRecipe.CHEST_PRESS -> if (id == "chest_press") CameraMotion.ELBOW_PRESS else CameraMotion.ELBOW_FOLD
                MeasurementRecipe.LATERAL_RAISE -> CameraMotion.SHOULDER_RAISE
                MeasurementRecipe.SQUAT -> CameraMotion.SQUAT_KNEE_FLEXION
                MeasurementRecipe.PULLOVER -> CameraMotion.PULLOVER_REACH
                MeasurementRecipe.FLY -> if (id == "machine_fly") CameraMotion.FLY_OPEN else CameraMotion.FLY_CLOSED
                else -> null
            }
            return CameraCaptureGuide("camera-$id-v1", direction,
                "폰을 고정해 보이는 관절이 화면 가장자리 밖으로 나가지 않게 맞춰요. 셀카 화면은 반전돼도 $side 해부학적 관절을 관측해요.",
                required, contract.startDescription + ". 관측 범위 밖 자세는 평가하지 않아요.", metric,
                // External rotation turns the forearm about the upper arm, which a phone camera cannot
                // judge reliably; it only checks the elbow is set at about 90 degrees beside the body.
                if (recipe == MeasurementRecipe.EXTERNAL_ROTATION) 75.0..105.0 else null,
                brief(id, recipe, left), startHint(id, recipe))
        }

        /** One short line: which way to face the camera and what must be in frame. */
        private fun brief(id: String, recipe: MeasurementRecipe, left: Boolean): String {
            val arm = if (left) "왼팔" else "오른팔"; val side = if (left) "왼쪽 측면" else "오른쪽 측면"
            return when (recipe) {
                MeasurementRecipe.SQUAT -> "측면 · 머리부터 발까지 보이게"
                MeasurementRecipe.MANUAL -> if (id in setOf("hip_adduction", "hip_abduction")) "정면 · 골반부터 발까지 보이게" else "측면 · 움직이는 부위가 다 보이게"
                MeasurementRecipe.LATERAL_RAISE -> "정면 · 상체와 골반이 보이게"
                MeasurementRecipe.FLY -> "정면 · 양어깨와 ${arm}이 보이게"
                MeasurementRecipe.VERTICAL_PULL -> "뒤쪽 사선 · 상체와 ${arm}이 보이게"
                else -> "$side · 상체와 ${arm}이 보이게"
            }
        }

        /** The start pose in a few words; the pictogram shows the rest. */
        private fun startHint(id: String, recipe: MeasurementRecipe): String? = when (recipe) {
            MeasurementRecipe.CURL -> when (id) {
                "preacher_curl" -> "패드에 팔을 올리고 편 상태에서 시작"
                "incline_curl", "incline_hammer_curl" -> "등을 기대고 팔을 내린 상태에서 시작"
                else -> "팔을 쭉 내린 상태에서 시작"
            }
            MeasurementRecipe.ROW -> if (id in setOf("seated_row", "cable_seated_row", "high_row_machine")) "팔을 앞으로 뻗고 시작"
                else "상체를 숙이고 팔을 내린 상태에서 시작"
            MeasurementRecipe.VERTICAL_PULL -> "팔을 위로 쭉 뻗고 시작"
            MeasurementRecipe.CHEST_PRESS -> if (id == "chest_press") "손잡이를 가슴 옆에 두고 시작" else "누워서 팔을 위로 편 상태에서 시작"
            MeasurementRecipe.SHOULDER_PRESS -> "손을 어깨 높이에 두고 시작"
            MeasurementRecipe.LATERAL_RAISE -> "팔을 내린 상태에서 시작"
            MeasurementRecipe.FLY -> if (id == "machine_fly") "팔을 옆으로 벌리고 시작" else "팔을 앞으로 모으고 시작"
            MeasurementRecipe.PULLOVER -> "누워서 팔을 머리 뒤로 뻗고 시작"
            MeasurementRecipe.TRICEPS_EXTENSION -> when (id) {
                "cable_overhead_extension", "rope_overhead_extension", "dumbbell_triceps_extension" -> "팔을 머리 위로 들고 팔꿈치를 굽힌 채 시작"
                "ez_lying_extension" -> "누워서 팔꿈치를 굽힌 채 시작"
                else -> "팔꿈치를 굽혀 옆구리에 붙이고 시작"
            }
            MeasurementRecipe.EXTERNAL_ROTATION -> "팔꿈치를 90°로 굽혀 옆구리에 붙이고 시작"
            MeasurementRecipe.SQUAT -> "곧게 선 상태에서 시작"
            MeasurementRecipe.MANUAL -> null
        }
    }
}

/** Proposed boundaries remain CAMERA_CANDIDATE until the user reviews actual frames. */
object CameraRepProposals {
    fun propose(frames: List<CameraPoseFrame>, exerciseId: String, left: Boolean): List<RepCycle> {
        val guide = CameraCaptureGuide.forExercise(exerciseId, left)
        val motion = guide.metric ?: return emptyList()
        var start: Long? = null; var turn: Long? = null; var extreme = 0.0; var departed = false
        var stableSince: Long? = null; var last = -1L
        val rows = mutableListOf<RepCycle>()
        fun reset() { start = null; turn = null; departed = false; stableSince = null }
        frames.forEach { frame ->
            val ns = frame.cameraTimestampNs
            if (ns <= last || last >= 0 && ns - last > 500_000_000L) reset()
            last = ns
            val image = frame.imageLandmarks.singleOrNull()
            val world = frame.worldLandmarks.singleOrNull()
            if (!frame.uniquelyVisible || !frame.videoFrameWritten || image == null || world == null || !guide.visible(image)) { reset(); return@forEach }
            val value = guide.observedMetric(image, world, left) ?: run { reset(); return@forEach }
            if (start == null) {
                if (motion.atRest(value)) {
                    if (stableSince == null) stableSince = ns
                    if (ns - stableSince!! >= 200_000_000L) { start = ns; extreme = value }
                } else stableSince = null
                return@forEach
            }
            // READY is permission to observe, not the start of the repetition.
            // Retain the last actually observed resting frame before movement.
            if (!departed && motion.atRest(value)) { start = ns; extreme = value; return@forEach }
            if (!departed && !motion.atRest(value)) { departed = true; turn = ns; extreme = value }
            if (departed && (if (motion.increasing) value > extreme else value < extreme)) { extreme = value; turn = ns }
            if (departed && motion.atRest(value)) {
                val first = start!!; val middle = turn!!
                if (motion.departed(extreme) && middle > first && ns > middle && ns - first in 350_000_000L..12_000_000_000L) {
                    rows.add(RepCycle(first, middle, ns, ExerciseMeasurementContracts.resolve(exerciseId).departurePhase))
                }
                start = ns; turn = null; departed = false; extreme = value
            }
        }
        return rows
    }
}
