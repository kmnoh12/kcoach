package dev.kyungmin.kcoach.phone

import dev.kyungmin.kcoach.core.CameraObservedMetrics
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
