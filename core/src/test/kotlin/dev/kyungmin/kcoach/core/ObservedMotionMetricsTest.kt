package dev.kyungmin.kcoach.core

import kotlin.test.*

class ObservedMotionMetricsTest {
    private fun document() = SensorCaptureDocument(SensorCaptureFormat.FORMAT_VERSION, 50.0,
        (0..250).map { i -> SensorCaptureRecord(SensorSample(i * 20_000_000L, 0f, 0f, 9.8f, 2f, 0f, 0f)) })
    @Test fun unequalObservedPhasesAndTrueGyroUnitsSurviveDurableEncoding() {
        val cycle = RepCycle(0, 300_000_000, 1_000_000_000, MusclePhase.UNKNOWN)
        val metrics = assertNotNull(ObservedMotionAnalyzer.analyze(document(), listOf(cycle), "GENERIC_WRIST_CANDIDATE"))
        assertEquals(300.0, metrics.averageDepartureMs)
        assertEquals(700.0, metrics.averageReturnMs)
        assertNull(metrics.averageConcentricMs)
        assertNull(metrics.averageEccentricMs)
        assertNull(metrics.averagePauseMs)
        assertEquals(2.0, metrics.averageAngularSpeedRadS, 1e-10)
        assertNull(metrics.tempoConsistency)
        assertEquals(metrics, ObservedMotionMetricsCodec.decode(ObservedMotionMetricsCodec.encode(metrics)))
    }
    @Test fun reviewedPauseLabelsAreExcludedFromMovingPhasesAndEccentricDepartureIsMapped() {
        val cycle = RepCycle(0, 300_000_000, 1_000_000_000, MusclePhase.ECCENTRIC,
            listOf(RepPause(250_000_000, 450_000_000)))
        val metrics = assertNotNull(ObservedMotionAnalyzer.analyze(document(), listOf(cycle), "REVIEWED_WRIST_REFERENCE", true))
        assertEquals(250.0, metrics.averageEccentricMs)
        assertEquals(550.0, metrics.averageConcentricMs)
        assertEquals(200.0, metrics.averagePauseMs)
        val unreviewed = assertNotNull(ObservedMotionAnalyzer.analyze(document(), listOf(cycle), "PERSONAL_WRIST_CANDIDATE"))
        assertNull(unreviewed.averagePauseMs)
        assertEquals(300.0, unreviewed.averageDepartureMs)
    }
    @Test fun tempoUsesDurationsAndShowsMeasuredLateSlowdownWithoutInventingFatigue() {
        val cycles = listOf(RepCycle(0, 300_000_000, 1_000_000_000, MusclePhase.UNKNOWN),
            RepCycle(1_000_000_000, 1_300_000_000, 2_000_000_000, MusclePhase.UNKNOWN),
            RepCycle(2_000_000_000, 2_400_000_000, 3_500_000_000, MusclePhase.UNKNOWN),
            RepCycle(3_500_000_000, 3_900_000_000, 5_000_000_000, MusclePhase.UNKNOWN))
        val metrics = assertNotNull(ObservedMotionAnalyzer.analyze(document(), cycles, "GENERIC_WRIST_CANDIDATE"))
        assertEquals(.5, metrics.lateCycleChangeRatio)
        assertEquals(.2, metrics.tempoCoefficientOfVariation)
        assertEquals(80, metrics.tempoConsistency)
    }
    @Test fun lateWristTravelChangeIsSeparateFromTempoAndDoesNotDivideByZero() {
        fun metrics(travel: List<Double>) = ObservedMotionMetrics("PERSONAL_WRIST_CANDIDATE", travel.mapIndexed { i, value ->
            val start = i * 1_000_000_000L
            ObservedRepMotion(start, start + 300_000_000, start + 1_000_000_000,
                300.0, 700.0, null, null, null, value, value)
        })
        val measured = metrics(listOf(2.0, 2.0, 1.0, 1.0))
        assertEquals(-.5, measured.lateWristAngularTravelChangeRatio)
        assertEquals(0.0, measured.lateCycleChangeRatio)
        assertNull(metrics(listOf(0.0, 0.0, 1.0, 1.0)).lateWristAngularTravelChangeRatio)
        assertNull(metrics(listOf(1.0, 1.0, 1.0)).lateWristAngularTravelChangeRatio)
    }
    @Test fun brokenCoverageOutOfCaptureAndOverlappingCyclesCannotProduceMetrics() {
        val cycle = RepCycle(0, 300_000_000, 1_000_000_000, MusclePhase.UNKNOWN)
        val doc = document()
        assertNull(ObservedMotionAnalyzer.analyze(doc.copy(records = doc.records.filterIndexed { i, _ -> i % 2 == 0 }), listOf(cycle), "GENERIC_WRIST_CANDIDATE"))
        assertNull(ObservedMotionAnalyzer.analyze(doc.copy(records = doc.records.map { it.copy(sample = it.sample.copy(sensorAccuracy = 0)) }), listOf(cycle), "GENERIC_WRIST_CANDIDATE"))
        assertNull(ObservedMotionAnalyzer.analyze(doc.copy(records = doc.records.drop(1)), listOf(cycle), "GENERIC_WRIST_CANDIDATE"))
        assertNull(ObservedMotionAnalyzer.analyze(doc, listOf(cycle, cycle), "GENERIC_WRIST_CANDIDATE"))
        assertFails { ObservedMotionMetricsCodec.decode(ObservedMotionMetricsCodec.encode(assertNotNull(ObservedMotionAnalyzer.analyze(doc, listOf(cycle), "GENERIC_WRIST_CANDIDATE"))) + "AAAA") }
    }
    @Test fun genericAnalyzerRetainsUnequalTurnaroundTimesWithoutAnatomicalPhaseClaims() {
        var time = 1_000_000L
        val records = buildList {
            for ((rate, n) in listOf(0f to 15, 1.2f to 16, 0f to 2, -1.2f to 35, 0f to 15)) repeat(n) {
                add(SensorCaptureRecord(SensorSample(time, 0f, 0f, 9.8f, rate, 0f, 0f)))
                time += 20_000_000
            }
        }
        val output = PhoneRepAnalyzer.analyze(SensorCaptureDocument(SensorCaptureFormat.FORMAT_VERSION, 50.0, records), "lat_pull_down")
        assertTrue(output.events.isNotEmpty())
        val metrics = assertNotNull(output.result.motionMetrics)
        assertTrue(metrics.averageDepartureMs < metrics.averageReturnMs)
        assertTrue(output.events.all { it.concentricMs == null && it.eccentricMs == null })
        assertTrue(metrics.averageAngularSpeedRadS > 0)
    }
}
