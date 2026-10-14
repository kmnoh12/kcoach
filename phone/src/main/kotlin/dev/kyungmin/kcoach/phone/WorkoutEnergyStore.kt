package dev.kyungmin.kcoach.phone

import android.content.Context
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** User-selected session-average basis; no HR regression, volume multiplier, or inferred body mass. */
internal class WorkoutEnergyStore(context: Context) {
    private val prefs = context.getSharedPreferences("workout-energy", Context.MODE_PRIVATE)
    fun estimate(evidence: FinishedWorkoutStore.Evidence, profile: AiCoachProfile?): JSONObject {
        val date = Instant.ofEpochMilli(evidence.snapshot.startedAtMs).atZone(ZoneId.systemDefault()).toLocalDate()
        val days = profile?.weightMeasuredOn?.takeIf { it.isNotBlank() }?.let { ChronoUnit.DAYS.between(LocalDate.parse(it), date) }
        val valid = profile?.ageYears in 19..59 && profile?.weightKg != null && days != null && days in 0..90 &&
            profile.calorieBasis == "RESISTANCE_MULTIPLE_8_15" && evidence.minutes > 0
        val reason = when {
            profile == null || profile.weightKg == null -> "프로필 입력 후 확인"
            profile.ageYears !in 19..59 -> "성인 19~59세 추정 기준 확인 필요"
            days == null || days !in 0..90 -> "운동일 이전 90일 이내 체중 필요"
            profile.calorieBasis != "RESISTANCE_MULTIPLE_8_15" -> "프로필에서 칼로리 추정 기준 선택"
            evidence.minutes <= 0 -> "운동 시간 미확인"
            else -> "일반 근력운동의 세션 평균 추정"
        }
        val row = JSONObject().put("version", 1).put("session_id", evidence.snapshot.sessionId)
            .put("profile_id", profile?.id ?: JSONObject.NULL).put("profile_revision", profile?.revision ?: JSONObject.NULL)
            .put("weight_kg", profile?.weightKg ?: JSONObject.NULL).put("weight_measured_on", profile?.weightMeasuredOn ?: "")
            .put("minutes", evidence.minutes).put("basis", profile?.calorieBasis ?: "").put("met", if (valid) 3.5 else JSONObject.NULL)
            .put("active_kcal", if (valid) (3.5 - 1.0) * 3.5 * profile!!.weightKg!! / 200.0 * evidence.minutes else JSONObject.NULL)
            .put("source", "2024 Adult Compendium 02054").put("source_url", "https://pacompendium.com/conditioning-exercise/")
            .put("reason", reason).put("method", "사용자가 선택한 다종목 8~15회 근력운동의 3.5 MET 세션 평균. 일시정지 제외·휴식 포함 경과 시간, 1 MET 기초 소모 차감. 개인 실측 아님.")
        val previous = prefs.getString(evidence.snapshot.sessionId, null)?.let(::JSONObject)
        val comparable = JSONObject(row.toString()).toString()
        val before = previous?.let { JSONObject(it.toString()).apply { remove("revision"); remove("updated_reason") }.toString() }
        row.put("revision", (previous?.optLong("revision") ?: 0L) + if (before == comparable) 0L else 1L)
            .put("updated_reason", if (previous == null) "initial_estimate" else if (before == comparable) previous.optString("updated_reason") else "profile_or_duration_changed")
        if (previous?.toString() != row.toString()) check(prefs.edit().putString(evidence.snapshot.sessionId, row.toString()).commit())
        return row
    }
}
