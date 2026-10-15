package dev.kyungmin.kcoach.wear

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchPhoneResultTest {
    @Test fun exactFinishedSessionTravelsInTheBrowsableViewUriWithFreshClickIdentity() {
        val a = WatchPhoneResult.intent("finished-exact")
        val b = WatchPhoneResult.intent("finished-exact")
        assertEquals(Intent.ACTION_VIEW, a.action)
        assertTrue(a.categories.contains(Intent.CATEGORY_BROWSABLE))
        assertEquals("kcoach", a.data!!.scheme); assertEquals("workout", a.data!!.host)
        assertEquals("/result", a.data!!.path)
        assertEquals("finished-exact", a.data!!.getQueryParameter("session_id"))
        assertNotEquals(a.data!!.getQueryParameter("request_id"), b.data!!.getQueryParameter("request_id"))
        UUID.fromString(a.data!!.getQueryParameter("request_id"))
        assertNull(a.component); assertNull(a.`package`); assertNull(a.extras)
    }
    @Test(expected = IllegalArgumentException::class) fun invalidSessionDoesNotProduceAnOpenRequest() {
        WatchPhoneResult.intent("wrong&session_id=other")
    }
}
