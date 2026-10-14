package dev.kyungmin.kcoach.core

/** Anatomical MediaPipe indices, independent of the mirrored selfie preview. Guides are not accuracy claims. */
data class CameraCaptureGuide(val id: String, val direction: String, val framing: String, val required: Set<Int>,
    val startDescription: String, val metric: CameraMotion?) {
    fun visible(points: List<CameraLandmark>): Boolean = required.isNotEmpty() && required.all { index ->
        val point = points.getOrNull(index) ?: return@all false
        minOf(point.visibility ?: 0.0, point.presence ?: 0.0) >= 0.65 && point.x in 0.02..0.98 && point.y in 0.02..0.98
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
                recipe == MeasurementRecipe.SQUAT -> setOf(shoulder, hip, if (left) 25 else 26, if (left) 27 else 28)
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
                else -> null
            }
            return CameraCaptureGuide("camera-$id-v1", direction,
                "폰을 고정해 보이는 관절이 화면 가장자리 밖으로 나가지 않게 맞춰요. 셀카 화면은 반전돼도 $side 해부학적 관절을 관측해요.",
                required, contract.startDescription + ". 관측 범위 밖 자세는 평가하지 않아요.", metric)
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
            val value = UpperBodyPoseGeometry.metric(world.mapIndexed { i, p -> PosePoint(p.x, p.y, p.z,
                minOf(image[i].visibility ?: 0.0, image[i].presence ?: 0.0)) }, motion, left) ?: run { reset(); return@forEach }
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
