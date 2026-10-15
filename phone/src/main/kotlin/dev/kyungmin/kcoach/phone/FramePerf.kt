package dev.kyungmin.kcoach.phone

/**
 * Rolling one-second view of where the per-frame time goes, so frame-rate and latency problems are measured, not guessed.
 * Only the analysis thread feeds it; [summary] is read once a second for the `KCoachPerf` log and the debug overlay.
 */
internal class FramePerf {
    private var windowStartNs = 0L
    private var frames = 0
    private var convertNs = 0L; private var detectNs = 0L; private var encodeNs = 0L; private var storeNs = 0L; private var totalNs = 0L
    private var lastCameraNs = 0L
    private var gapSumNs = 0L; private var gapMaxNs = 0L; private var gaps = 0
    var latest: String = ""
        private set

    /** Stage durations in ns; [encode] and [store] are 0 while no capture is recording. Returns a line once per second, else null. */
    fun add(nowNs: Long, cameraNs: Long, convert: Long, detect: Long, encode: Long, store: Long, total: Long): String? {
        if (windowStartNs == 0L) windowStartNs = nowNs
        frames++; convertNs += convert; detectNs += detect; encodeNs += encode; storeNs += store; totalNs += total
        if (lastCameraNs != 0L) { val gap = cameraNs - lastCameraNs; gapSumNs += gap; gaps++; if (gap > gapMaxNs) gapMaxNs = gap }
        lastCameraNs = cameraNs
        if (nowNs - windowStartNs < 1_000_000_000L) return null
        val seconds = (nowNs - windowStartNs) / 1e9
        fun ms(sum: Long) = "%.0f".format(sum / 1e6 / frames)
        val line = "처리 %.1ffps · 간격 평균 %s/최대 %sms · 변환 %s 모델 %s 인코딩 %s 저장 %s 합계 %sms".format(
            frames / seconds, if (gaps > 0) "%.0f".format(gapSumNs / 1e6 / gaps) else "-", "%.0f".format(gapMaxNs / 1e6),
            ms(convertNs), ms(detectNs), ms(encodeNs), ms(storeNs), ms(totalNs))
        windowStartNs = nowNs; frames = 0; convertNs = 0; detectNs = 0; encodeNs = 0; storeNs = 0; totalNs = 0
        gapSumNs = 0; gapMaxNs = 0; gaps = 0
        latest = line
        return line
    }
}
