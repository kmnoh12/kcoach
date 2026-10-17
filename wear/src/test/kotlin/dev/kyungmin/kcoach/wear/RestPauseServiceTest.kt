package dev.kyungmin.kcoach.wear

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestPauseServiceTest {
    private fun command(operation: String, revision: Long) = Intent().putExtra("command", operation).putExtra("session", "rest-session")
        .putExtra("exerciseId", "barbell_biceps_curl").putExtra("setId", "set").putExtra("attemptId", "attempt")
        .putExtra("targetReps", 8).putExtra("restSeconds", 90).putExtra("commandRevision", revision)
    private fun state() = shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents.last { it.action == WearWorkoutService.ACTION_STATE }

    @Test fun aPausedRestStaysAFrozenRestForTheScreenAndResumeContinuesFromTheSameTime() {
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        try {
            controller.get().onStartCommand(command("START", 1), 0, 1)
            controller.get().onStartCommand(command("REST", 2).putExtra("restEndAtMs", System.currentTimeMillis() + 60_000L), 0, 1)
            assertTrue(state().getBooleanExtra("resting", false))
            assertFalse(state().getBooleanExtra("restPaused", true))
            controller.get().onStartCommand(command("PAUSE", 3), 0, 2)
            assertTrue(state().getBooleanExtra("paused", false))
            assertTrue("the screen must keep showing the rest, not the last set", state().getBooleanExtra("restPaused", false))
            val frozen = state().getLongExtra("pausedRestRemainingMs", 0L)
            assertTrue("remaining time is kept: $frozen", frozen in 50_000L..60_000L)
            controller.get().onStartCommand(command("RESUME", 4), 0, 3)
            assertTrue(state().getBooleanExtra("resting", false))
            assertFalse(state().getBooleanExtra("restPaused", true))
            assertTrue(state().getLongExtra("restEndAtMs", 0L) - System.currentTimeMillis() in (frozen - 5_000L)..(frozen + 1_000L))
        } finally { controller.destroy() }
    }
}
