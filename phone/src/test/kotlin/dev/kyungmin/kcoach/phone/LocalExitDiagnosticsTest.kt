package dev.kyungmin.kcoach.phone

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalExitDiagnosticsTest {
    @Test fun localExceptionHasCodeFramesAndStateButNoPrivateMessages() {
        val app = RuntimeEnvironment.getApplication()
        LocalExitDiagnostics.state("ACTIVE", "WORKING")
        LocalExitDiagnostics.recordException(IllegalStateException("PRIVATE workout 80kg", IllegalArgumentException("PRIVATE bodyweight")))
        val dir = File(app.noBackupFilesDir, "exit-diagnostics")
        val text = File(dir, "last-exception.json").readText()
        assertTrue(text.contains("IllegalStateException")); assertTrue(text.contains("IllegalArgumentException"))
        assertTrue(text.contains("LocalExitDiagnosticsTest")); assertTrue(text.contains("WORKING"))
        assertFalse(text.contains("PRIVATE")); assertTrue(File(dir, "last-state.json").exists())
        val reports = LocalExitDiagnostics.readReports().getOrThrow()
        assertEquals(text, reports["last-exception.json"])
        assertTrue(reports.keys.all { it in setOf("last-exception.json", "last-state.json", "system-exits.json") })
        assertEquals(text, File(dir, "last-exception.json").readText())
    }
}
