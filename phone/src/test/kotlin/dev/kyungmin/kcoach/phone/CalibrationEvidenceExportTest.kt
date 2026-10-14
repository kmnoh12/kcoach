package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalibrationEvidenceExportTest {
    @Test fun oneSelectedCaptureExportsOriginalsClocksAndExplicitZeroWithoutCredentialsOrUnselectedVideo() {
        val app = RuntimeEnvironment.getApplication()
        val captures = CalibrationCaptureStore(app)
        val capture = captures.begin("dumbbell_curl", "컬", true, "phone", "OUTSIDE").getOrThrow()
        val records = (0..5).map { SensorCaptureRecord(SensorSample(1_000_000_000L + it * 20_000_000L, 0f, 0f, 9.8f, 0f, 0f, 0f)) }
        val raw = ByteArrayOutputStream().apply { SensorCaptureFormat.write(this, records, 50.0) }.toByteArray()
        val manifest = WireMessages.captureManifest(capture.id, capture.exerciseId, capture.setId, "raw-export",
            SensorCaptureFormat.FORMAT_VERSION, "NONE", records.size, 50.0, 50.0, records.first().sample.timestampNs,
            records.last().sample.timestampNs, SensorCaptureFormat.sha256(raw), raw.size.toLong(), 0,
            capture.exerciseId, capture.exerciseName, attemptId = capture.attemptId)
        val bound = manifest.copy(payload = manifest.payload + mapOf("capturePurpose" to "PAIRED_REFERENCE", "watchClockId" to "watch",
            "watchEpochVerified" to "true", "access_token" to "DO_NOT_EXPORT_TOKEN"))
        val received = CaptureInbox(app, app.getSharedPreferences("wear-sync", Context.MODE_PRIVATE)).receive(bound, raw).getOrThrow()
        captures.attachSensor(received).getOrThrow()
        captures.videoFile(capture.id).writeText("DO_NOT_EXPORT_VIDEO")
        captures.finish(capture.id, false, "interrupted test input").getOrThrow()
        CalibrationReviewStore(app).saveCount(capture.id, 0, 0).getOrThrow()
        val clocks = CaptureClockStore(app)
        val probe = clocks.prepareProbe(capture.id, "START", 2_000_000_000, "phone", true).getOrThrow()
        clocks.acceptReply(ClockProbeProtocol.reply(probe, 1_010_000_000, 1_012_000_000, "watch", true),
            2_022_000_000, "phone").getOrThrow()
        app.getSharedPreferences("account", Context.MODE_PRIVATE).edit().putString("token", "DO_NOT_EXPORT_ACCOUNT").commit()
        val output = ByteArrayOutputStream()
        val receipt = CalibrationEvidenceExport(app).write(capture.id, false, output).getOrThrow()
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes() }
        }
        assertArrayEquals(raw, entries.getValue("watch.kcs"))
        val exportedManifest = WireCodec.decode(entries.getValue("watch.manifest").toString(Charsets.UTF_8))
        assertEquals(records.size, CaptureValidator.validate(exportedManifest, entries.getValue("watch.kcs")).document.sampleCount)
        assertEquals(0, JSONObject(entries.getValue("review.json").toString(Charsets.UTF_8)).getJSONObject("count").getInt("reps"))
        assertEquals(1, JSONObject(entries.getValue("clocks.json").toString(Charsets.UTF_8)).getJSONArray("START").length())
        assertFalse(entries.containsKey("reference.mp4"))
        val text = entries.values.joinToString { it.toString(Charsets.UTF_8) }
        assertFalse(text.contains("DO_NOT_EXPORT"))
        assertFalse(JSONObject(entries.getValue("evidence-manifest.json").toString(Charsets.UTF_8)).getBoolean("deviceAcceptanceVerified"))
        assertEquals(SensorCaptureFormat.sha256(output.toByteArray()), receipt.zipSha256)
        assertArrayEquals(raw, received.rawFile.readBytes())
        // Optional cross-language integration artifact; never an actual Watch8 capture.
        System.getenv("KCOACH_EXPORT_FIXTURE")?.let { path ->
            java.io.File(path).apply { parentFile?.mkdirs() }.writeBytes(output.toByteArray())
        }
    }
}
