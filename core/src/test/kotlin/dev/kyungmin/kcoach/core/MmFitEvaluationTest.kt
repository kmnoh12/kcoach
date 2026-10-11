package dev.kyungmin.kcoach.core

import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test

/**
 * Offline evaluation of [WristTrajectoryTracker] on MM-Fit (left-wrist watch).
 * Runs only when KCOACH_MMFIT_DIR points at tools/mmfit_convert.py output;
 * writes a per-exercise report to core/build/mmfit-report.txt.
 */
class MmFitEvaluationTest {
    private data class SetLabel(val startNs: Long, val endNs: Long, val reps: Int, val exercise: String)
    private data class Row(val sensor: Int, val t: Long, val x: Float, val y: Float, val z: Float)
    private data class SetResult(val exercise: String, val reps: Int, val segments: List<MotionSegment>)

    @Test
    fun `report segment counts against labelled reps`() {
        val dir = System.getenv("KCOACH_MMFIT_DIR")?.let(::File) ?: return
        val results = mutableListOf<SetResult>()
        dir.listFiles { f -> f.name.matches(Regex("w\\d+\\.csv")) }!!.sorted().forEach { csv ->
            val rows = csv.useLines { lines ->
                lines.drop(1).map { line ->
                    val p = line.split(',')
                    Row(p[0].toInt(), p[1].toLong(), p[2].toFloat(), p[3].toFloat(), p[4].toFloat())
                }.toList()
            }
            val labels = File(dir, csv.nameWithoutExtension + "_labels.csv").readLines().drop(1).map {
                val p = it.split(',')
                SetLabel(p[0].toLong(), p[1].toLong(), p[2].toInt(), p[3])
            }
            labels.forEach { label ->
                val tracker = WristTrajectoryTracker()
                val segments = mutableListOf<MotionSegment>()
                // Start early so the tracker can initialise on the rest before the set.
                rows.asSequence()
                    .filter { it.t in (label.startNs - LEAD_NS)..(label.endNs + TAIL_NS) }
                    .forEach { r ->
                        if (r.sensor == 0) tracker.onAccel(r.t, r.x, r.y, r.z)
                        else tracker.onGyro(r.t, r.x, r.y, r.z).forEach { s ->
                            if (s.pathLengthM >= 0.03 && s.endNanos >= label.startNs && s.startNanos <= label.endNs) segments += s
                        }
                    }
                results += SetResult(label.exercise, label.reps, segments)
            }
        }

        val report = buildString {
            appendLine("MM-Fit left wrist · ${results.size} sets")
            appendLine("exercise                  sets  reps  segs  |err| 1seg/rep  |err| 2seg/rep  ±1(best)  path(cm)  peak(m/s)")
            results.groupBy { it.exercise }.toSortedMap().forEach { (exercise, sets) ->
                val errOne = sets.map { abs(it.segments.size - it.reps) }
                val errTwo = sets.map { abs((it.segments.size / 2.0).roundToInt() - it.reps) }
                val best = if (errOne.average() <= errTwo.average()) errOne else errTwo
                val all = sets.flatMap { it.segments }
                appendLine(
                    "%-24s %5d %5.1f %5.1f  %14.2f  %14.2f  %7.0f%%  %8.1f  %9.2f".format(
                        exercise, sets.size,
                        sets.map { it.reps }.average(),
                        sets.map { it.segments.size }.average(),
                        errOne.average(), errTwo.average(),
                        100.0 * best.count { it <= 1 } / sets.size,
                        if (all.isEmpty()) 0.0 else all.map { it.pathLengthM * 100 }.average(),
                        if (all.isEmpty()) 0.0 else all.map { it.peakSpeedMps }.average(),
                    ),
                )
            }
        }
        File("build/mmfit-report.txt").writeText(report)
        println(report)
    }

    private companion object {
        const val LEAD_NS = 10_000_000_000L
        const val TAIL_NS = 1_000_000_000L
    }
}
