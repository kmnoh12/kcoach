package dev.kyungmin.kcoach.core

import java.io.File
import java.util.Locale
import kotlin.math.abs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Previously explored public replay data, not independent Watch8/personal validation. */
class PeakEngineComparisonTest {
    private data class Row(val session: String, val exercise: String, val expected: Int, val manual: Boolean,
        val old: CurrentDetectorReplay.Result, val peak: PeakRepAnalysis)

    @Test fun compareFrozenPeakCandidateWithCurrentDetectorOnIdenticalOriginalSamples() {
        val directory = System.getenv("KCOACH_MMFIT_DIR")?.let(::File)
        assumeTrue(directory?.isDirectory == true, "MM-Fit converted source data required")
        val selectedSessions = System.getenv("KCOACH_PEAK_SESSIONS")?.split(',')?.toSet()
        val files = directory!!.listFiles().orEmpty().filter { it.name.matches(Regex("w\\d+\\.csv")) &&
            (selectedSessions == null || it.nameWithoutExtension in selectedSessions) }.sortedBy { it.name }
        require(files.isNotEmpty())
        val rows = mutableListOf<Row>()
        files.forEach { csv ->
            val samples = CurrentDetectorReplay.readSamples(csv)
            File(directory, csv.nameWithoutExtension + "_labels.csv").useLines { lines ->
                lines.drop(1).forEach { line ->
                    val label = line.split(','); require(label.size == 4)
                    val start = label[0].toLong(); val end = label[1].toLong(); val expected = label[2].toInt()
                    val selected = samples.filter { it.timestampNs in start..end }
                    require(selected.isNotEmpty())
                    val id = CurrentDetectorReplay.exerciseId(label[3])
                    val manual = ExerciseTrackingProfiles.resolve(id).mode == TrackingMode.MANUAL_REQUIRED
                    rows.add(Row(csv.nameWithoutExtension, label[3], expected, manual, CurrentDetectorReplay.evaluate(selected, id),
                        PaperPeakRepAnalyzer.analyze(CurrentDetectorReplay.capture(selected), id)))
                }
            }
            println("Peak comparison: ${csv.nameWithoutExtension} complete (${rows.size} sets)")
        }
        File("build/peak-engine-comparison.csv").writeText(buildString {
            appendLine("session,exercise,label_count,label_scope,legacy_watch,legacy_phone,legacy_selected,peak_count,peak_recordable,axis,peak_rejection,lookahead_ms")
            rows.forEach { row ->
                appendLine("${row.session},${row.exercise},${row.expected},${if (row.exercise == "bicep_curls") "both_arms_alternating_unscored" else if (row.manual) "manual_unscored" else "set_reps"}," +
                    "${row.old.watch ?: ""},${row.old.phone ?: ""},${row.old.selected ?: ""},${row.peak.estimatedCount ?: ""}," +
                    "${if (row.peak.rejectionReason == null) row.peak.estimatedCount ?: "" else ""},${row.peak.axis?.name.orEmpty()},${row.peak.rejectionReason.orEmpty()},${row.peak.lookaheadNs / 1_000_000}")
            }
        })
        File("build/peak-engine-comparison.md").writeText(buildString {
            appendLine("# Frozen paper-inspired peak candidate versus current phone engine")
            appendLine("${rows.size} labelled sets, ${files.size} MM-Fit sessions. Same source gyro times and latest preceding acceleration. No target-count stop or interpolation.")
            appendLine("Previously explored public data: this is descriptive replay, not held-out personal/Watch8 acceptance. Known set boundaries bypass session preparation and transport.")
            appendLine("Alternating bilateral curls cannot be scored against one wrist without separate arm labels. Manual-only exercises remain unavailable, never zero-error automatic counts.")
            appendLine("Candidate uses fourth-order ~2s offline SG, within-unit mean-centered dominant axis, robust range thresholds and complete-return checks. These Watch8 adaptations are not an exact replication of the paper's sensor/equipment thresholds.")
            appendLine("Offline lookahead is 1000ms. This configuration cannot satisfy a 500ms watch latency gate; no watch adoption occurs here.")
            appendLine("| Exercise | Scored sets | Current raw MAE | Peak raw MAE | Current accepted coverage | Peak accepted coverage | Current accepted exact / all | Peak accepted exact / all |")
            appendLine("|---|---:|---:|---:|---:|---:|---:|---:|")
            rows.groupBy { it.exercise }.toSortedMap().forEach { (exercise, values) ->
                val scored = values.filter { !it.manual && it.exercise != "bicep_curls" }
                fun mae(selector: (Row) -> Int?): String = scored.mapNotNull { row -> selector(row)?.let { abs(it - row.expected) } }
                    .let { errors -> if (errors.isEmpty()) "N/A" else "%.2f".format(Locale.US, errors.average()) }
                val currentAccepted = scored.filter { it.old.selected != null }
                val peakAccepted = scored.filter { it.peak.estimatedCount != null && it.peak.rejectionReason == null }
                appendLine("| $exercise | ${scored.size} | ${mae { it.old.phone }} | ${mae { it.peak.estimatedCount }} | " +
                    "${currentAccepted.size}/${scored.size} | ${peakAccepted.size}/${scored.size} | " +
                    "${currentAccepted.count { it.old.selected == it.expected }}/${scored.size} | ${peakAccepted.count { it.peak.estimatedCount == it.expected }}/${scored.size} |")
            }
            appendLine("Missing/rejected predictions stay in coverage and overall exact denominators. Raw MAE must be read alongside coverage. No engine is selected or enabled by this report.")
        })
        require(rows.filter { it.manual }.all { it.peak.estimatedCount == null })
    }
}
