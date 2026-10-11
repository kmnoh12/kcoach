package dev.kyungmin.kcoach.core

import java.io.File
import kotlin.test.Test

/**
 * Replays a probe CSV recorded by the phone (sensor,t_ns,x,y,z,screen_on)
 * through [WristTrajectoryTracker]. Runs only when KCOACH_PROBE_CSV is set;
 * writes core/build/probe-replay.txt.
 */
class ProbeReplayTest {
    @Test
    fun `replay recorded probe session`() {
        val csv = System.getenv("KCOACH_PROBE_CSV")?.let(::File) ?: return
        val tracker = WristTrajectoryTracker()
        val out = StringBuilder()
        var t0 = -1L
        var count = 0
        val trace = StringBuilder()
        var n = 0
        var lastT = 0L
        tracker.debugTrace = { l, _, v -> if (n++ % 10 == 0) trace.appendLine("%5.1f lin %5.1f %5.1f %5.1f v %6.2f moving=%s".format((lastT - t0) / 1e9, l.x, l.y, l.z, v, tracker.isMoving)) }
        csv.useLines { lines ->
            lines.drop(1).forEach { line ->
                val p = line.split(',')
                val t = p[1].toLong()
                if (t0 < 0) t0 = t
                lastT = t
                if (p[0] == "0") tracker.onAccel(t, p[2].toFloat(), p[3].toFloat(), p[4].toFloat())
                else tracker.onGyro(t, p[2].toFloat(), p[3].toFloat(), p[4].toFloat()).forEach { s ->
                    if (s.pathLengthM < 0.03) return@forEach
                    count++
                    val end = s.points.last()
                    out.appendLine(
                        "%2d  %5.1fs  %.2fs  이동 %3.0fcm  경로 %3.0fcm  최고 %.2fm/s  끝점(x,y,z) %4.0f %4.0f %4.0f cm  화면 %s".format(
                            count, (s.startNanos - t0) / 1e9, s.durationSec, s.displacementM * 100, s.pathLengthM * 100,
                            s.peakSpeedMps, end.x * 100, end.y * 100, end.z * 100, p[5],
                        ),
                    )
                }
            }
        }
        out.appendLine("구간 $count 개 → 반복 약 ${(count + 1) / 2}회")
        File("build/probe-replay.txt").writeText(out.toString())
        File("build/probe-trace.txt").writeText(trace.toString())
    }
}
