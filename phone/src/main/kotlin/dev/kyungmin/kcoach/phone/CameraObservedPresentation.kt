package dev.kyungmin.kcoach.phone

import dev.kyungmin.kcoach.core.CameraObservedMetrics
import dev.kyungmin.kcoach.core.CameraSquatMetrics
import java.util.Locale

internal fun cameraObservedMetricsText(metrics: CameraObservedMetrics?): String {
    if (metrics == null) return "촬영 관측값: 아직 산출 불가\n확인한 반복에서 어깨·팔꿈치·손목·골반이 보이고 시간 정렬이 통과해야 해요."
    fun number(v: Double) = "%.1f".format(Locale.US, v)
    return buildString {
        appendLine("촬영 관측값 · ${if (metrics.watchOnLeft) "왼팔" else "오른팔"} · ${metrics.cycles.size}개 확인 구간")
        metrics.cycles.take(12).forEachIndexed { index, row ->
            appendLine("${index + 1}구간: 팔꿈치 추정각 ${number(row.minimumElbowAngleDegrees)}~${number(row.maximumElbowAngleDegrees)}°")
            appendLine("몸통 영상평면 기울기 변화 ${number(row.torsoTiltVariationDegrees)}° · 팔꿈치 위치 변화 ${number(row.maximumElbowPositionChangeShoulderWidths * 100)}% (영상 속 어깨 폭 기준)")
            row.averageSimultaneousElbowAngleDifferenceDegrees?.let { appendLine("동시 동작 양팔 추정각 차이 평균 ${number(it)}°") }
        }
        if (metrics.cycles.size > 12) appendLine("나머지 ${metrics.cycles.size - 12}개 구간도 기록에 저장했어요.")
        append("MediaPipe 추정값이에요. 기울기·위치 변화는 이 촬영 평면의 관측이며 자세의 옳고 그름이나 실제 관절 ROM을 판정하지 않아요.")
    }
}

internal fun cameraSquatMetricsText(metrics: CameraSquatMetrics?): String {
    if (metrics == null) return "스쿼트 촬영 관측값: 아직 산출 불가\n전신 관측·영상 구간 확인·워치 시간 정렬이 필요해요."
    fun number(v: Double) = "%.2f".format(Locale.US, v)
    return buildString {
        appendLine("스쿼트 영상 관측 · ${metrics.cycles.size}개 확인 구간")
        metrics.cycles.take(12).forEachIndexed { index, row ->
            appendLine("${index + 1}회 · 내려감 ${number(row.descendingMs / 1000.0)}초 / 올라감 ${number(row.ascendingMs / 1000.0)}초")
            appendLine("표시한 정지 ${row.markedPauseMs?.let { number(it / 1000.0) + "초" } ?: "미확인"} · 위 시간에서 표시한 정지만 제외했어요")
            appendLine("무릎 추정각 ${number(row.minimumKneeDegrees)}~${number(row.maximumKneeDegrees)}° · 고관절 추정각 ${number(row.minimumHipDegrees)}~${number(row.maximumHipDegrees)}°")
            appendLine("골반 영상평면 옆 이동 ${number(row.pelvisSidewaysTorsoLengths)} / 아래 이동 ${number(row.pelvisDropTorsoLengths)} / 복귀 위치 차이 ${number(row.returnOffsetTorsoLengths)} (처음 몸통 길이 기준)")
            appendLine("몸통 영상평면 기울기 변화 ${number(row.torsoTiltVariationDegrees)}°")
        }
        append("고정 카메라의 영상평면과 MediaPipe 관절 추정값이에요. 실제 3D 이동 높이·중량 속도·자세 정확도 점수는 아니며, 촬영 구간의 관측 시간이에요.")
    }
}
