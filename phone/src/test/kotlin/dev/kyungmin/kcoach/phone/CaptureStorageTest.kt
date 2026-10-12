package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureStorageTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("wear-sync", Context.MODE_PRIVATE)
    private fun receive(id: String): ReceivedCapture {
        val records = (0 until 500).map { SensorCaptureRecord(SensorSample(1_000L + it * 20_000_000L, 0f, 0f, 9.8f, 0f, 0f, 0f)) }
        val bytes = ByteArrayOutputStream().also { SensorCaptureFormat.write(it, records, 50.0) }.toByteArray()
        val manifest = WireMessages.captureManifest("storage", "external_rotation", "set", id,
            SensorCaptureFormat.FORMAT_VERSION, "NONE", records.size, 50.0, 50.0, records.first().sample.timestampNs,
            records.last().sample.timestampNs, SensorCaptureFormat.sha256(bytes), bytes.size.toLong())
        return CaptureInbox(app, prefs).receive(manifest, bytes).getOrThrow()
    }
    @Test fun restartRecoveryLoadsOnlyOneCaptureAtATime() {
        repeat(3) { receive("capture-$it") }
        assertEquals(1, CaptureInbox(app, prefs).pendingAnalysis().size)
    }
    @Test fun compressionPreservesAllSamplesAndLeavesPendingAndFailedRawUntouched() {
        val inbox = CaptureInbox(app, prefs)
        val done = receive("done"); val original = done.rawFile.readBytes()
        val pending = receive("pending"); val failed = receive("failed")
        inbox.markAnalysisAttempted("done", true)
        prefs.edit().putBoolean("capture:done:acked", true).putString("analysis:done", "saved").commit()
        inbox.markAnalysisAttempted("failed", false)
        assertEquals(1, inbox.compressSuccessfulCaptures())
        val compressed = java.io.File(prefs.getString(CaptureInbox.pathKey("done"), null)!!)
        assertTrue(compressed.name.endsWith(".gz")); assertTrue(compressed.length() < original.size)
        val recovered = java.util.zip.GZIPInputStream(compressed.inputStream()).use { it.readBytes() }
        assertArrayEquals(original, recovered)
        assertEquals(500, CaptureValidator.validate(done.manifest, recovered).document.sampleCount)
        assertTrue(pending.rawFile.isFile); assertTrue(failed.rawFile.isFile)
        assertFalse(done.rawFile.exists())
        val report = inbox.storageReport()
        assertEquals(3, report.getInt("captures")); assertEquals(2, report.getInt("protectedCaptures"))
        assertTrue(java.io.File(app.filesDir, "capture-storage.json").isFile)
    }
    @Test fun compressedCaptureCanBeReanalyzedWithoutLosingItsManifestOrAnalysis() {
        val inbox = CaptureInbox(app, prefs); val raw = receive("again")
        inbox.markAnalysisAttempted("again", true)
        prefs.edit().putBoolean("capture:again:acked", true).putString("analysis:again", "saved").commit()
        inbox.compressSuccessfulCaptures()
        inbox.markAnalysisAttempted("again", false)
        val pending = inbox.pendingAnalysis().single()
        assertEquals(500, pending.validated.document.sampleCount)
        assertEquals(raw.manifest, pending.manifest)
        assertEquals("saved", prefs.getString("analysis:again", null))
    }
    @Test fun reusedCaptureIdCannotDestroyAValidOriginal() {
        val raw = receive("immutable"); val original = raw.rawFile.readBytes()
        val altered = raw.manifest.copy(sessionId = "different")
        assertTrue(CaptureInbox(app, prefs).receive(altered, original).isFailure)
        assertArrayEquals(original, raw.rawFile.readBytes())
        assertEquals(raw.manifest, WireCodec.decode(prefs.getString(CaptureInbox.manifestKey("immutable"), null)!!))
        assertEquals(CaptureState.RECEIVED, CaptureInbox(app, prefs).state("immutable"))
    }

    @Test fun backgroundRecoveryDrainsAllCapturesUsingBoundedWorkers() {
        repeat(4) { receive("drain-$it") }
        val controller = org.robolectric.Robolectric.buildService(PhoneWearListenerService::class.java).create()
        try {
            val deadline = System.nanoTime() + 10_000_000_000L
            val inbox = CaptureInbox(app, prefs)
            while ((0 until 4).any { !inbox.analysisSucceeded("drain-$it") } && System.nanoTime() < deadline) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            repeat(4) { assertTrue("Capture $it must not be stranded after a recovery race", inbox.analysisSucceeded("drain-$it")) }
        } finally { controller.destroy() }
    }
    @Test fun validRetransmissionRepairsAStoredButInvalidRawFile() {
        val raw = receive("repair"); val original = raw.rawFile.readBytes()
        raw.rawFile.writeBytes(ByteArray(original.size))
        val repaired = CaptureInbox(app, prefs).receive(raw.manifest, original).getOrThrow()
        assertEquals(500, repaired.validated.document.sampleCount)
        assertArrayEquals(original, repaired.rawFile.readBytes())
    }

    @Test fun oldAcknowledgementCannotDiscardTheOnlyRetransmittedRawCopy() {
        val raw = receive("missing-acked"); val original = raw.rawFile.readBytes()
        prefs.edit().putBoolean("capture:missing-acked:acked", true).commit()
        raw.rawFile.delete()
        val controller = org.robolectric.Robolectric.buildService(PhoneWearListenerService::class.java).create()
        try {
            var acknowledged = false
            val receive = PhoneWearListenerService::class.java.getDeclaredMethod("enqueueCapture", WireEnvelope::class.java,
                ByteArray::class.java, kotlin.jvm.functions.Function0::class.java).apply { isAccessible = true }
            receive.invoke(controller.get(), raw.manifest, original, { acknowledged = true })
            assertTrue(acknowledged)
            val stored = java.io.File(prefs.getString(CaptureInbox.pathKey("missing-acked"), null)!!)
            assertTrue("ACK must follow restoring the missing local original", stored.isFile)
        } finally { controller.destroy() }
    }

    @Test fun validRepairClearsTheOldValidationFailureSoRestartCanAnalyzeIt() {
        val raw = receive("validation-repair"); val original = raw.rawFile.readBytes()
        prefs.edit().remove(CaptureInbox.pathKey("validation-repair")).commit()
        val inbox = CaptureInbox(app, prefs)
        assertTrue(inbox.receive(raw.manifest, ByteArray(original.size)).isFailure)
        inbox.receive(raw.manifest, original).getOrThrow()
        assertEquals("validation-repair", inbox.pendingAnalysis().single().validated.captureId)
    }

}
