package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticesTest {
    @Test fun aFreshStartStillConnectingShowsNoBanner() {
        assertEquals(Notices.Connection.None, Notices.connection(Status.Connecting, 0, network = true))
        assertEquals(Notices.Connection.None, Notices.connection(Status.Connecting, 0, network = false))
        assertEquals(Notices.Connection.None, Notices.connection(Status.Live, 5, network = true))
        assertEquals(Notices.Connection.None, Notices.connection(Status.StorageError, 5, network = false))
    }

    @Test fun noNetworkIsAwayAndRepeatedFailuresOnANetworkAreAsleep() {
        assertEquals(Notices.Connection.Away, Notices.connection(Status.Offline, 1, network = false))
        assertEquals(Notices.Connection.Away, Notices.connection(Status.Offline, 9, network = false))
        assertEquals(Notices.Connection.Away, Notices.connection(Status.Connecting, 1, network = true))
        assertEquals(Notices.Connection.Asleep, Notices.connection(Status.Connecting, Notices.ASLEEP_AFTER, network = true))
    }

    @Test fun theWordsDependOnTheInstall() {
        assertNull(Notices.connectionText(Notices.Connection.None, homeInstall = true))
        assertTrue(Notices.connectionText(Notices.Connection.Away, homeInstall = true)!!.startsWith("Away from home"))
        assertTrue(Notices.connectionText(Notices.Connection.Away, homeInstall = false)!!.startsWith("Offline"))
        assertTrue(Notices.connectionText(Notices.Connection.Asleep, homeInstall = true)!!.startsWith("Chorus Home is asleep"))
        assertTrue(!Notices.connectionText(Notices.Connection.Asleep, homeInstall = false)!!.contains("Home"))
    }

    @Test fun retryAfterIsWholeSecondsAndTheToastCountsDown() {
        assertEquals(12, Notices.retryAfter(" 12 "))
        assertNull(Notices.retryAfter("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(Notices.retryAfter("0"))
        assertNull(Notices.retryAfter(null))
        assertEquals("Catching its breath. Try again in 12 seconds.", Notices.slowDownText(12))
        assertEquals("Catching its breath. Try again in 1 second.", Notices.slowDownText(1))
        assertEquals("Catching its breath. Try again in a moment.", Notices.slowDownText(null))
    }
}
