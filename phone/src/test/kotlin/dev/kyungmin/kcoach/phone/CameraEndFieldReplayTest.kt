package dev.kyungmin.kcoach.phone

import dev.kyungmin.kcoach.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Opt-in local field evidence, never packaged or treated as a labeled accuracy test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraEndFieldReplayTest {
    @Test fun replayObservedFramesAndReportWhetherTheyContainAnEndHold() {
        val folder = System.getenv("KCOACH_CAMERA_FIELD_DIR")
        assumeTrue("Needs separately supplied local field capture", folder != null)
        val root = File(requireNotNull(folder))
        val metadata = JSONObject(root.resolve("capture.json").readText())
        val guide = CameraCaptureGuide.forExercise(metadata.getString("exerciseId"), metadata.getBoolean("watchOnLeft"))
        val frames = root.resolve("camera-frames.jsonl").readLines().filter { it.isNotBlank() }.map { CameraFrameCodec.decode(JSONObject(it)) }
        val policy = CameraCaptureEnd()
        var cutoff: Long? = null
        var decision: Long? = null
        frames.forEach { frame ->
            if (cutoff == null) {
                cutoff = policy.observe(frame.cameraTimestampNs, frame.imageLandmarks.singleOrNull(), frame.worldLandmarks.singleOrNull(), guide, metadata.getBoolean("watchOnLeft"))
                if (cutoff != null) decision = frame.cameraTimestampNs
            }
        }
        println("FIELD_END frames=${frames.size} cutoffVideoSeconds=${cutoff?.let { (it-frames.first().cameraTimestampNs)/1e9 }} decisionVideoSeconds=${decision?.let { (it-frames.first().cameraTimestampNs)/1e9 }}")
        // Every end remains an actually encoded frame; absence of a 2.5s hold is a valid non-termination.
        assertTrue(cutoff == null || frames.any { it.cameraTimestampNs == cutoff && it.videoFrameWritten })
    }
}
