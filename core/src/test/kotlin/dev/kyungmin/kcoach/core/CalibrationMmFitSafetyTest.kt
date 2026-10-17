package dev.kyungmin.kcoach.core

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.*
import org.junit.jupiter.api.Assumptions.assumeTrue

/** External-data stress test, never personal adoption evidence or cross-device compatibility proof. */
class CalibrationMmFitSafetyTest {
    @Test fun aBadSyntheticShoulderPressProfileIsBlockedOnComparablePublicRepetitions() {
        val dir = System.getenv("KCOACH_CALIBRATION_MMFIT_DIR")?.let(::File)
        assumeTrue(dir?.isDirectory == true, "Converted MM-Fit source not available")
        val csv = File(dir!!, "w00.csv")
        val samples = CurrentDetectorReplay.readSamples(csv)
        val key = PatternCompatibility("synthetic-benchmark", MeasurementRecipe.SHOULDER_PRESS, "LEFT", "OUTSIDE", "mmfit-fixture", "watch-native-si-v1")
        val bad = PatternRuntimeProfile("bad-synthetic", 1, "a".repeat(64), key,
            List(33) { List(6) { 0.0 } }, List(6) { 1.0 }, listOf(0), 3_000_000_000, 1_800_000_000, 4_800_000_000, .05)
        val material = File(dir, "w00_labels.csv").readLines().drop(1).mapNotNull { line ->
            val f = line.split(',')
            if (f[3] != "dumbbell_shoulder_press") return@mapNotNull null
            val start = f[0].toLong(); val end = f[1].toLong()
            val document = CurrentDetectorReplay.capture(samples.filter { it.timestampNs in start..end })
            val bytes = ByteArrayOutputStream().apply { SensorCaptureFormat.write(this, document.records,
                document.sampleRateRequestedHz, document.formatVersion) }.toByteArray()
            HeldOutPatternSet(ReviewedPatternSet("w00-$start", SensorCaptureFormat.sha256(bytes), "dumbbell_shoulder_press",
                key, "2019-07-10", document, emptyList()), SetCountReference("set-$start", "attempt-$start", f[2].toInt(), 1))
        }
        assertEquals(3, material.size)
        val report = PatternRegressionValidation.compare(bad, null, material).getOrThrow()
        assertFalse(report.passed)
        assertTrue("generic_count_regression" in report.failures, report.toString())
        File("build/calibration-mmfit-safety.csv").writeText(buildString {
            appendLine("capture,expected,generic,bad_synthetic,blocked")
            report.rows.forEach { row -> appendLine("${row.captureId},${row.countReference.reps},${row.genericCount},${row.candidateCount},${!report.passed}") }
        })
        assertFalse(report.independentAccuracyEvidence)
    }
}
