package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*
import org.json.JSONObject
import dev.kyungmin.kcoach.core.WireCodec
import dev.kyungmin.kcoach.core.WirePayloadKeys

internal data class SetAnalysisUi(
    val exerciseId: String,
    val exerciseName: String,
    val setIndex: Int,
    val watchReps: Int?,
    val phoneReps: Int?,
    val confirmedReps: Int?,
    val confidence: Double,
    val trackingMode: String = "WRIST_IMU_CAUTION",
    val trackingProfileId: String = "generic-wrist",
    val trackingStatus: String = "",
    val capabilityTier: String = "C_PERSONALIZED_AUTO_COUNT_BETA",
    val repConfidence: Double? = null,
    val wristMotionSpeed: Double? = null,
    val wristMotionRange: Double? = null,
    val tempoConsistency: Int? = null,
    val signalQuality: Double? = null,
    val averageSpeed: Double? = null,
    val relativeRom: Double? = null,
    val lateDrift: Double? = null,
    val setId: String = "",
    val attemptId: String = "",
    val captureId: String = "",
    val motionMetrics: ObservedMotionMetrics? = null,
) {
    val key: String get() = SetIdentity.key(exerciseId, setIndex, setId, attemptId)
}

/** Reads durable native results, including captures received with no visible Activity. */
internal class SetAnalysisStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("wear-sync", Context.MODE_PRIVATE)

    private fun snapshotFor(sessionId: String): WorkoutSnapshot? {
        return SessionStore(context).loadActive().getOrNull()?.takeIf { it.sessionId == sessionId }
            ?: runCatching { context.getSharedPreferences("finished-workouts", Context.MODE_PRIVATE).getString("session:$sessionId", null)
                ?.let { WorkoutSnapshotCodec.decode(JSONObject(it).getString("snapshot")) } }.getOrNull()
    }

    fun matchingSaved(sessionId: String, saved: Collection<SetAnalysisUi>): Map<String, SetAnalysisUi> {
        val snapshot = snapshotFor(sessionId) ?: return saved.associateBy { it.key }
        return saved.mapNotNull { analysis ->
            val target = SetIdentity.resolve(snapshot, analysis.exerciseId, analysis.setIndex, analysis.setId, analysis.attemptId) ?: return@mapNotNull null
            analysis.copy(setIndex = target.setIndex, setId = target.setId, attemptId = target.attemptId)
        }.associateBy { it.key }
    }

    internal data class SessionRead(val rows: Map<String, SetAnalysisUi>, val revision: Long)

    private fun indexKey(sessionId: String) = "analysis-captures:$sessionId"
    private fun revisionKey(sessionId: String) = "analysis-revision:$sessionId"
    private fun fingerprintKey(captureId: String) = "analysis-fingerprint:$captureId"
    private fun fingerprint(result: String, manifest: String, receivedAt: Long): String {
        val bytes = org.json.JSONArray(listOf(result, manifest, receivedAt)).toString().toByteArray(Charsets.UTF_8)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return java.util.Base64.getEncoder().encodeToString(digest)
    }

    private fun ensureIndex() {
        if (!prefs.getBoolean("analysis-index-ready", false)) refreshIndex()
    }

    /** One legacy scan, rather than a complete manifest scan and sort for each finished session. */
    fun refreshIndex() = synchronized(lock) {
        val saved = prefs.all
        val grouped = linkedMapOf<String, MutableSet<String>>()
        val changed = mutableSetOf<String>()
        val edit = prefs.edit()
        saved.keys.filter { it.startsWith("analysis:") }.forEach { key ->
            val id = key.removePrefix("analysis:")
            runCatching {
                val text = saved[CaptureInbox.manifestKey(id)] as? String ?: return@forEach
                val manifest = WireCodec.decode(text)
                require(manifest.payload[WirePayloadKeys.CAPTURE_ID] == id)
                val result = saved[key] as? String ?: return@forEach
                grouped.getOrPut(manifest.sessionId) { linkedSetOf() }.add(id)
                val hash = fingerprint(result, text, saved[CaptureInbox.receivedAtKey(id)] as? Long ?: 0L)
                if (saved[fingerprintKey(id)] != hash) { changed.add(manifest.sessionId); edit.putString(fingerprintKey(id), hash) }
            }
        }
        val known = saved.keys.filter { it.startsWith("analysis-captures:") }.map { it.removePrefix("analysis-captures:") }
        val finished = context.getSharedPreferences("finished-workouts", Context.MODE_PRIVATE).all.keys
            .filter { it.startsWith("session:") }.map { it.removePrefix("session:") }
        (known + finished + grouped.keys).toSet().forEach { session ->
            val ids = grouped[session].orEmpty()
            if (prefs.getStringSet(indexKey(session), emptySet()).orEmpty() != ids) changed.add(session)
            if (prefs.getStringSet(indexKey(session), null) != ids) edit.putStringSet(indexKey(session), ids)
            if (session in changed) edit.putLong(revisionKey(session), Math.addExact(prefs.getLong(revisionKey(session), 0L), 1L))
        }
        check(edit.putBoolean("analysis-index-ready", true).commit()) { "Failed to persist session analysis index" }
    }

    /** Result, session index and recovery revision are committed together before any reconciliation. */
    fun persistAnalysis(manifest: WireEnvelope, encoded: String) = synchronized(lock) {
        ensureIndex()
        val id = manifest.payload[WirePayloadKeys.CAPTURE_ID].orEmpty()
        require(id.matches(Regex("[A-Za-z0-9._-]{1,160}")))
        val ids = prefs.getStringSet(indexKey(manifest.sessionId), emptySet()).orEmpty().toMutableSet().apply { add(id) }
        val storedManifest = prefs.getString(CaptureInbox.manifestKey(id), null) ?: error("Missing durable capture manifest")
        val hash = fingerprint(encoded, storedManifest, prefs.getLong(CaptureInbox.receivedAtKey(id), 0L))
        val edit = prefs.edit().putString("analysis:$id", encoded).putStringSet(indexKey(manifest.sessionId), ids)
        if (prefs.getString(fingerprintKey(id), null) != hash) {
            edit.putString(fingerprintKey(id), hash)
                .putLong(revisionKey(manifest.sessionId), Math.addExact(prefs.getLong(revisionKey(manifest.sessionId), 0L), 1L))
        }
        check(edit.commit()) { "Failed to persist capture analysis" }
    }

    fun revision(sessionId: String): Long = synchronized(lock) { ensureIndex(); prefs.getLong(revisionKey(sessionId), 0L) }

    fun loadForSession(sessionId: String): Map<String, SetAnalysisUi> = readForSession(sessionId).rows

    fun readForSession(sessionId: String, allowLegacyScan: Boolean = true, savedSnapshot: WorkoutSnapshot? = null): SessionRead = synchronized(lock) {
        ensureIndex()
        if (allowLegacyScan && prefs.getStringSet(indexKey(sessionId), emptySet()).orEmpty().isEmpty()) refreshIndex()
        val snapshot = savedSnapshot ?: snapshotFor(sessionId)
        val saved = mutableMapOf<String, Any?>()
        prefs.getStringSet(indexKey(sessionId), emptySet()).orEmpty().forEach { id ->
            saved["analysis:$id"] = prefs.getString("analysis:$id", null)
            saved[CaptureInbox.manifestKey(id)] = prefs.getString(CaptureInbox.manifestKey(id), null)
            saved[CaptureInbox.receivedAtKey(id)] = prefs.getLong(CaptureInbox.receivedAtKey(id), 0L)
        }

        val results = linkedMapOf<String, SetAnalysisUi>()
        saved.keys.filter { it.startsWith("analysis:") }
            .sortedWith(compareBy<String> { saved[CaptureInbox.receivedAtKey(it.removePrefix("analysis:"))] as? Long ?: 0L }.thenBy { it })
            .forEach { key ->
                val captureId = key.removePrefix("analysis:")
                runCatching {
                    val manifest = WireCodec.decode(saved[CaptureInbox.manifestKey(captureId)] as? String ?: return@forEach)
                    if (manifest.sessionId != sessionId || manifest.sessionId.startsWith("calibration-")) return@forEach
                    require(manifest.payload[WirePayloadKeys.CAPTURE_ID] == captureId)
                    val f = (saved[key] as? String ?: return@forEach).split('|')
                    require(f.size == 24 || f.size == 25)
                    fun number(i: Int) = f[i].toDoubleOrNull()?.takeIf { it.isFinite() }
                    fun count(i: Int) = f[i].toIntOrNull()?.takeIf { it >= 0 }
                    val exerciseId = manifest.payload[WirePayloadKeys.EXERCISE_ID].orEmpty()
                    val index = manifest.payload[WirePayloadKeys.SET_INDEX]?.toIntOrNull() ?: return@forEach
                    require(exerciseId.isNotBlank() && index >= 0)
                    val attempt = manifest.payload[WirePayloadKeys.ATTEMPT_ID].orEmpty()
                    val id = if (attempt.isBlank()) "" else manifest.setId.orEmpty()
                    val target = snapshot?.let { SetIdentity.resolve(it, exerciseId, index, id, attempt) }
                    if (snapshot != null && target == null) return@forEach
                    val result = SetAnalysisUi(
                        exerciseId, manifest.payload[WirePayloadKeys.EXERCISE_NAME].orEmpty().ifBlank { "운동" }, index,
                        count(0), count(1), count(2), number(11) ?: 0.0,
                        f[13], f[14], f[15], f[16], number(17), number(18), number(19),
                        count(20), number(21), number(22), number(23), number(8),
                        setId = target?.setId ?: id, attemptId = target?.attemptId ?: attempt, captureId = captureId,
                        motionMetrics = f.getOrNull(24)?.takeIf { it.isNotBlank() }?.let { runCatching { ObservedMotionMetricsCodec.decode(it) }.getOrNull() },
                    )
                    val positioned = result.copy(setIndex = target?.setIndex ?: index)
                    results[positioned.key] = positioned
                }
            }
        SessionRead(results, prefs.getLong(revisionKey(sessionId), 0L))
    }

    companion object { private val lock = Any() }
}

internal fun measuredRepsText(value: Int?, missing: String): String = value?.let { "${it}회" } ?: missing
internal fun heartRateText(value: Int): String = if (value in 20..240) "심박 ${value}bpm" else "심박 측정 대기"

internal fun SetAnalysisUi.repMetricRows(): List<Pair<String, String>> = listOf(
    "워치 추정 횟수" to measuredRepsText(watchReps, "미측정"),
    "폰 재분석" to if (trackingMode == "MANUAL_REQUIRED") "자동 산출 안 함" else measuredRepsText(phoneReps, "산출 불가"),
    "자동 추정 횟수" to if (trackingMode == "MANUAL_REQUIRED") "수동 입력 필요" else measuredRepsText(confirmedReps, "확인 필요"),
)

internal const val ANALYSIS_ESTIMATE_NOTE = "자동 추정은 실제 횟수와 다를 수 있어요. 기록의 횟수를 확인하고 수정해 주세요. 분석 점수는 정확도나 정답 확률이 아니에요."

/** Presentation excludes legacy range ratios too; they never measured joint ROM. */
internal fun SetAnalysisUi.resultMetricRows(): List<Pair<String, String>> = buildList {
    add("측정 방식" to trackingStatus.ifBlank { if (trackingMode == "MANUAL_REQUIRED") "수동 입력" else "손목 센서 기반 추정" })
    addAll(repMetricRows())
    if (trackingMode != "MANUAL_REQUIRED") {
        val score = confidence.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
        add("분석 점수" to (score?.let { "${kotlin.math.round(it * 100).toInt()}/100" } ?: "산출 불가"))
        val signal = ExerciseTrackingProfiles.resolve(exerciseId, exerciseName).signal
        val metrics = motionMetrics
        if (metrics != null) {
            fun seconds(value: Double) = "%.2f초".format(java.util.Locale.US, value / 1000.0)
            add("관측한 반복" to "${metrics.reps.size}회 · 손목 신호의 완전한 왕복 구간")
            add("평균 반복 시간" to seconds(metrics.averageCycleMs))
            add("시작→전환 평균" to seconds(metrics.averageDepartureMs))
            add("전환→복귀 평균" to seconds(metrics.averageReturnMs))
            metrics.averagePauseMs?.let { add("확인한 정지 시간" to seconds(it)) }
            add("손목 관측 속도" to "%.2f rad/s".format(java.util.Locale.US, metrics.averageAngularSpeedRadS))
            metrics.wristMotionComparison?.let { comparison ->
                add("개인 기준 대비 손목 회전 움직임량" to "%.1f%% · 현재 반복 중간값".format(java.util.Locale.US, comparison.medianCurrentToBaselineRatio * 100))
                add("개인 손목 기준" to "확인한 ${comparison.reviewedRepCount}회 · 중간값 %.2f rad · 편차(MAD) %.2f rad".format(java.util.Locale.US,
                    comparison.baselineMedianAngularTravelRad, comparison.baselineMedianAbsoluteDeviationRad))
                add("움직임량 기준" to "같은 보정·착용 조건의 자이로 적분 비교 · 관절 ROM이나 자세 점수 아님")
            }
            metrics.tempoCoefficientOfVariation?.let { add("반복 시간 편차" to "%.1f%%".format(java.util.Locale.US, it * 100)) }
            metrics.lateCycleChangeRatio?.let { change ->
                add("후반 반복 시간 변화" to "%+.1f%% · 초반 대비".format(java.util.Locale.US, change * 100))
            }
            metrics.lateWristAngularTravelChangeRatio?.let { change ->
                add("후반 손목 회전 움직임량 변화" to "%+.1f%% · 초반 대비 자이로 적분".format(java.util.Locale.US, change * 100))
            }
            add("세부 시간 기준" to "손목 신호의 추정 경계 · 실제 자세나 근육 피로 판정 아님")
        } else if (signal == TrackingSignal.ROTATION_ENERGY || signal == TrackingSignal.FOREARM_ROTATION) {
            (wristMotionSpeed ?: averageSpeed)?.takeIf { it.isFinite() }?.let {
                add("손목 관측 속도" to "%.2f rad/s".format(java.util.Locale.US, it))
            }
        }
        (metrics?.tempoConsistency ?: tempoConsistency)?.let { add("템포 일관성" to "$it/100") }
        signalQuality?.takeIf { it.isFinite() }?.let {
            add("신호 품질" to "%.0f%%".format(java.util.Locale.US, it.coerceIn(0.0, 1.0) * 100))
        }
    }
}
