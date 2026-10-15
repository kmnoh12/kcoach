package dev.kyungmin.kcoach.wear

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchCompletionServiceTest {
    @Test fun fullCompletionPersistsItsIdentityBeforeTheRuntimeIsCleared() {
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        val service = controller.get()
        WearWorkoutService::class.java.getDeclaredField("sessionId").apply { isAccessible = true }.set(service, "finished-original")
        WearWorkoutService::class.java.getDeclaredMethod("finishSession", String::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(service, "COMPLETE", 0)
        val home = WatchHomeStore(RuntimeEnvironment.getApplication())
        assertEquals("finished-original", home.completion()!!.sessionId); assertTrue(home.showsCompletion())
        assertEquals("", WearWorkoutService::class.java.getDeclaredField("sessionId").apply { isAccessible = true }.get(service))
        controller.destroy()
    }
    @Test fun stoppingIsNotACompletedWorkout() {
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        val service = controller.get()
        WearWorkoutService::class.java.getDeclaredField("sessionId").apply { isAccessible = true }.set(service, "stopped")
        WearWorkoutService::class.java.getDeclaredMethod("finishSession", String::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(service, "STOPPED", 0)
        assertFalse(WatchHomeStore(RuntimeEnvironment.getApplication()).showsCompletion())
        assertNull(WatchHomeStore(RuntimeEnvironment.getApplication()).completion())
        controller.destroy()
    }
}
