package dev.kyungmin.kcoach.core

import kotlin.test.*

class AnalysisSignalQualityTest {
    private fun records(): List<SensorCaptureRecord> = buildList {
        var t = 1_000_000L
        repeat(3) {
            for (rate in listOf(1.2f, 0f, -1.2f, 0f)) repeat(20) {
                add(SensorCaptureRecord(SensorSample(t, 0f, 0f, 9.8f, rate, 0f, 0f)))
                t += 50_000_000L
            }
        }
    }
    private fun analyze(records: List<SensorCaptureRecord>, requestedHz: Double = 20.0) =
        PhoneRepAnalyzer.analyze(SensorCaptureDocument(1, requestedHz, records), "lat_pull_down", watchDetectedReps = 3).result
    @Test fun aCleanRegularSignalStillConfirmsItsCompletedRepetitions() {
        val result = analyze(records())
        assertEquals(3, result.phoneReanalyzedReps)
        assertEquals(3, result.confirmedReps)
        assertEquals(1.0, result.signalQuality)
        assertNull(result.relativeRom)
        assertNull(result.wristMotionRange)
        assertNull(result.wristMotionSpeed)
        assertNull(result.averageAngularSpeed)
    }
    @Test fun aLongMissingIntervalCannotConfirmTheWholeSetDespiteClearRemainingReps() {
        val baseline = analyze(records())
        assertNotNull(baseline.confirmedReps)
        val damaged = records().mapIndexed { i, row ->
            if (i >= 80) row.copy(sample = row.sample.copy(timestampNs = row.sample.timestampNs + 10_000_000_000L)) else row
        }
        val result = analyze(damaged)
        assertTrue(result.phoneReanalyzedReps!! > 0)
        assertNull(result.confirmedReps)
        assertTrue(result.analysisConfidence < baseline.analysisConfidence)
    }
    @Test fun uniformlyMissingSamplesAreNotHiddenByTheirRegularCadence() {
        val result = analyze(records(), requestedHz = 50.0)
        assertEquals(3, result.phoneReanalyzedReps)
        assertNull(result.confirmedReps)
        assertTrue(result.signalQuality!! < 0.5)
    }
    @Test fun explicitlyUnreliableSensorSamplesCannotProduceAConfirmedCount() {
        val unreliable = records().map { it.copy(sample = it.sample.copy(sensorAccuracy = 0)) }
        val result = analyze(unreliable)
        assertNull(result.confirmedReps)
        assertEquals(0.0, result.signalQuality)
    }
    @Test fun unreliableStaleOrFutureAccelerationCannotBeHiddenByReliableGyroscopeSamples() {
        val good = records().map { r -> r.copy(sample = r.sample.copy(sensorAccuracy = 3),
            accelerationTimestampNs = r.sample.timestampNs, gyroscopeTimestampNs = r.sample.timestampNs,
            receivedElapsedNs = r.sample.timestampNs + 1_000_000, accelerationAccuracy = 3) }
        fun result(rows: List<SensorCaptureRecord>) = PhoneRepAnalyzer.analyze(SensorCaptureDocument(2, 20.0, rows),
            "lat_pull_down", watchDetectedReps = 3).result
        assertEquals(3, result(good).confirmedReps)
        for (damaged in listOf(good.map { it.copy(accelerationAccuracy = 0) },
            good.map { it.copy(accelerationTimestampNs = it.sample.timestampNs - 200_000_000L) },
            good.map { it.copy(accelerationTimestampNs = it.sample.timestampNs + 1_000_000L) })) {
            val measured = result(damaged)
            assertNull(measured.confirmedReps, "A reliable gyro must not hide invalid acceleration")
            assertTrue(measured.signalQuality!! < .85)
            assertNull(measured.motionMetrics)
        }
    }
    @Test fun ordinarySchedulingJitterDoesNotDiscardACompleteSignal() {
        var t = 1_000_000L
        val jittered = records().mapIndexed { i, row ->
            val timed = row.copy(sample = row.sample.copy(timestampNs = t))
            t += if (i % 2 == 0) 25_000_000L else 75_000_000L
            timed
        }
        assertEquals(3, analyze(jittered).confirmedReps)
        assertTrue(analyze(jittered).signalQuality!! > 0.95)
    }
    @Test fun invalidSamplesAndInvalidRequestedRateStayUnconfirmed() {
        val invalid = records().toMutableList()
        invalid[1] = invalid[1].copy(sample = invalid[1].sample.copy(accelerationX = Float.NaN))
        assertNull(analyze(invalid).confirmedReps)
        assertNull(analyze(records(), Double.POSITIVE_INFINITY).confirmedReps)
        invalid[1] = invalid[0]
        assertNull(analyze(invalid).confirmedReps)
    }
    @Test fun oneShorterButSignificantGapAlsoCannotConfirmTheWholeSet() {
        val damaged = records().mapIndexed { i, row ->
            if (i >= 80) row.copy(sample = row.sample.copy(timestampNs = row.sample.timestampNs + 500_000_000L)) else row
        }
        assertNull(analyze(damaged).confirmedReps)
    }

}
