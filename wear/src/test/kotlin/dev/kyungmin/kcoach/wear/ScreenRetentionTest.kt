package dev.kyungmin.kcoach.wear

import android.content.Intent
import android.view.WindowManager
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenRetentionTest {
    @Test fun waitingForPhoneKeepsWatchScreenVisible() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        MainActivity::class.java.getDeclaredMethod("updateScreenPolicy").apply { isAccessible = true }.invoke(activity)
        assertTrue("Waiting screen must not inherit the system inactivity timeout",
            activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
    }

    @Test fun idleRuntimeUpdateDoesNotClearScreenRetention() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        MainActivity::class.java.getDeclaredMethod("applyRuntimeState", Intent::class.java).apply { isAccessible = true }
            .invoke(activity, Intent().putExtra("paired", true).putExtra("running", false).putExtra("resting", false).putExtra("paused", false))
        assertTrue("IDLE updates must not allow the watch to hide the open app",
            activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
    }
}
