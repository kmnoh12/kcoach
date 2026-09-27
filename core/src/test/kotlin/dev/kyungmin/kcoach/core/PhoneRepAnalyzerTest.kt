package dev.kyungmin.kcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhoneRepAnalyzerTest {
    @Test
    fun refusesUnqualifiedGenericMotionInsteadOfFabricatingReps() {
        val records = buildList {
            var timestamp = 0L
            repeat(3) { rep ->
                repeat(20) { index ->
                    add(SensorCaptureRecord(
                        SensorSample(
                            timestampNs = timestamp,
                            accelerationX = 0f,
                            accelerationY = 0f,
                            accelerationZ = 9.8f,
                            gyroscopeX = (0.8f + index / 100f),
                            gyroscopeY = 0f,
                            gyroscopeZ = 0f,
                        ),
                        heartRateBpm = 120 + rep,
                    ))
                    timestamp += 50_000_000L
                }
                repeat(10) {
                    add(SensorCaptureRecord(
                        SensorSample(timestamp, 0f, 0f, 9.8f, 0f, 0f, 0f),
                        heartRateBpm = 118,
                    ))
                    timestamp += 50_000_000L
                }
            }
        }
        val output = PhoneRepAnalyzer.analyze(
            SensorCaptureDocument(SensorCaptureFormat.FORMAT_VERSION, 20.0, records),
            watchDetectedReps = 4,
        )
        assertEquals(null, output.result.watchDetectedReps)
        assertEquals(null, output.result.phoneReanalyzedReps)
        assertTrue(output.events.isEmpty())
        assertEquals(0.0, output.result.analysisConfidence)
        assertEquals(RepSource.MANUAL, output.result.repSource)
        assertEquals(TrackingMode.MANUAL_REQUIRED, output.result.trackingMode)
        assertEquals(CapabilityTier.D_MANUAL_REQUIRED, output.result.capabilityTier)
        assertEquals(null, output.result.repConfidence)
        assertEquals(null, output.result.wristMotionSpeed)
        assertEquals(null, output.result.wristMotionRange)
        assertEquals(null, output.result.tempoConsistency)
    }
}
