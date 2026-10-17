package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*
import java.util.Base64

/** A confirmed regression revokes the exact observed snapshot; never edits workout records. */
class CalibrationPatternSafetyMonitor(private val context: Context) {
    fun notice(captureId: String): String? = runCatching {
        val file = CalibrationCaptureStore(context).userFile(captureId, "safety-action.json")
        if (file.isFile && file.length() <= 2000) org.json.JSONObject(file.readText()).getString("notice") else null
    }.getOrNull()

    fun check(captureId: String): Result<Boolean> = runCatching {
        val captures = CalibrationCaptureStore(context)
        val capture = captures.load(captureId).getOrThrow()
        val count = CalibrationReviewStore(context).load(captureId).getOrThrow().countReference?.reps ?: return@runCatching false
        val sensor = captures.sensor(captureId).getOrThrow()
        val candidates = PatternCandidateStore(context)
        val key = PatternCompatibility(candidates.localUserId(), ExerciseMeasurementContracts.resolve(capture.exerciseId).recipe,
            if (capture.watchOnLeft) "LEFT" else "RIGHT", capture.watchOrientation,
            sensor.manifest.payload["watchDeviceId"].orEmpty(), "watch-native-si-v1")
        val outbox = PatternTransferOutbox(context)
        val active = outbox.activeHistory(key).getOrThrow().firstOrNull() ?: return@runCatching false
        val runtime = active.command.runtime
        val sourcesGood = runCatching { candidates.checkCurrentSources(candidates.load(runtime.id).getOrThrow().profile) }.isSuccess
        if (sourcesGood) {
            val raw = sensor.manifest.payload["patternActiveSnapshot"].orEmpty()
            if (raw.isEmpty() || sensor.manifest.payload["patternActiveUsed"] != "true" ||
                PatternRuntimeCodec.decode(Base64.getDecoder().decode(raw)) != runtime) return@runCatching false
            val generic = PhoneRepAnalyzer.analyze(sensor.validated.document, ExerciseTrackingProfiles.resolve(capture.exerciseId)).result.phoneReanalyzedReps
            val detector = ReviewedPatternDetector(capture.exerciseId, runtime.compatibility, runtime)
            val predicted = sensor.validated.document.records.count { detector.add(it.sample) != null }
            if (!CalibrationRegressionGuard.worse(count, generic, predicted)) return@runCatching false
        }
        val transition = PatternAdoptionController(context).disable(runtime.id).getOrThrow()
        val file = android.util.AtomicFile(captures.userFile(captureId, "safety-action.json"))
        val stream = file.startWrite()
        try {
            stream.write(org.json.JSONObject().put("profileId", runtime.id).put("commandId", transition.command.commandId)
                .put("notice", "보정 오류가 확인돼 기본 측정으로 복귀를 요청했어요. 워치의 다음 시도에서 해제 여부를 확인해요")
                .toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Throwable) { file.failWrite(stream); throw error }
        PhoneWorkoutService.send(context, WearSync.PATH_PATTERN_PROFILE, transition.command.encode())
        true // Queued, not an assertion that the watch already applied DISABLE.
    }
}
