package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*
import java.time.Instant
import java.time.ZoneOffset

/** Derive only from the exact frozen candidate and still-matching human-reviewed originals. */
class ReviewedMotionBaselineStore(private val context: Context) {
    fun derive(runtime: PatternRuntimeProfile): Result<ReviewedWristMotionBaseline> = runCatching {
        val candidates = PatternCandidateStore(context)
        val candidate = candidates.load(runtime.id).getOrThrow()
        require(candidate.sha256 == runtime.candidateHash && PatternRuntimeProfile.from(candidate.profile) == runtime) {
            "Capture runtime does not match the reviewed motion candidate"
        }
        candidates.checkCurrentSources(candidate.profile)
        val captures = CalibrationCaptureStore(context)
        val reviews = CalibrationReviewStore(context)
        val sets = candidate.profile.sources.map { source ->
            val capture = captures.load(source.captureId).getOrThrow()
            require(capture.status == CalibrationCaptureStatus.REVIEW_PENDING && capture.videoComplete)
            val sensor = captures.sensor(source.captureId).getOrThrow()
            require(sensor.manifest.payload["watchEpochVerified"] == "true")
            val device = sensor.manifest.payload["watchDeviceId"].orEmpty()
            val key = PatternCompatibility(candidates.localUserId(), ExerciseMeasurementContracts.resolve(capture.exerciseId).recipe,
                if (capture.watchOnLeft) "LEFT" else "RIGHT", capture.watchOrientation, device, "watch-native-si-v1")
            val day = Instant.ofEpochMilli(capture.createdAtMs).atOffset(ZoneOffset.UTC).toLocalDate().toString()
            ReviewedPatternSet(capture.id, sensor.manifest.payload.getValue(WirePayloadKeys.SHA256).lowercase(), capture.exerciseId,
                key, day, sensor.validated.document, reviews.load(capture.id).getOrThrow().references)
        }
        val baseline = ReviewedMotionBaseline.derive(candidate.profile, runtime, sets).getOrThrow()
        candidates.checkCurrentSources(candidate.profile)
        require(candidates.load(runtime.id).getOrThrow() == candidate) { "Motion candidate changed during analysis" }
        baseline
    }
}
