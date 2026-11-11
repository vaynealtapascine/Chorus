package garden.vayne.chorus.data

import garden.vayne.chorus.data.SyncLink.Effect
import garden.vayne.chorus.data.SyncLink.Event
import garden.vayne.chorus.data.SyncLink.Renewal
import garden.vayne.chorus.data.SyncLink.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncLinkTest {
    private fun State.on(vararg events: Event): State = events.fold(this) { s, e -> SyncLink.reduce(s, e).state }
    private fun State.effects(e: Event): List<Effect> = SyncLink.reduce(this, e).effects

    /** Linked, in front, socket 1 opened. */
    private val connecting = State().on(Event.Wanted(true), Event.Loaded(device = true, signedOut = false))
    private val live = connecting.on(Event.Welcome(1))

    @Test fun aLinkedPhoneInFrontOpensOneSocketAndGoesLiveOnItsWelcome() {
        assertEquals(Status.Connecting, connecting.status)
        assertEquals(1L, connecting.socket)
        assertTrue(Effect.Open(1) in State().on(Event.Wanted(true)).effects(Event.Loaded(device = true, signedOut = false)))
        assertEquals(Status.Live, live.status)
        assertTrue(live.liveOn(1))
        // asking again while connected opens nothing more
        assertEquals(emptyList<Effect>(), live.effects(Event.ConnectNow))
        assertEquals(Status.NoDevice, State().on(Event.Wanted(true), Event.Loaded(device = false, signedOut = false)).status)
        // nothing opens while nobody wants a socket
        assertEquals(Status.Offline, State().on(Event.Loaded(device = true, signedOut = false)).status)
    }

    @Test fun unauthenticatedThenRenewalRefusedIsSignedOutWithNoReconnect() {
        val renewing = live.on(Event.Unauthenticated(1))
        assertTrue(renewing.renewing)
        assertNull(renewing.socket)
        val step = SyncLink.reduce(renewing, Event.RenewalDone(Renewal.Refused))
        assertEquals(Status.SignedOut, step.state.status)
        assertFalse(step.state.renewing)
        assertTrue(Effect.RememberSignedOut in step.effects)
        assertTrue(step.effects.none { it is Effect.Open || it is Effect.Retry })
        // and nothing brings a socket back: not the network, the app in front, a work lease or a due retry
        for (e in listOf(Event.ConnectNow, Event.Wanted(true), Event.RetryDue, Event.Dropped(1), Event.Welcome(1))) {
            val after = SyncLink.reduce(step.state, e)
            assertEquals(e.toString(), Status.SignedOut, after.state.status)
            assertTrue(e.toString(), after.effects.none { it is Effect.Open || it is Effect.Retry })
        }
    }

    @Test fun unauthenticatedThenATransientFailureBacksOff() {
        val renewing = live.on(Event.Unauthenticated(1))
        assertTrue(Effect.Renew in live.effects(Event.Unauthenticated(1)))
        assertTrue(Effect.Close(1, "unauthenticated") in live.effects(Event.Unauthenticated(1)))
        val step = SyncLink.reduce(renewing, Event.RenewalDone(Renewal.Failed))
        assertEquals(Status.Offline, step.state.status)
        assertTrue(Effect.Retry(SyncLink.FIRST_BACKOFF) in step.effects)
        assertEquals(SyncLink.FIRST_BACKOFF * 2, step.state.backoff)
        // the retry opens a new socket; failing again waits twice as long, up to five minutes
        val again = step.state.on(Event.RetryDue)
        assertEquals(2L, again.socket)
        assertTrue(Effect.Retry(SyncLink.FIRST_BACKOFF * 2) in again.effects(Event.Dropped(2)))
        var s = again
        repeat(20) { s = s.on(Event.Dropped(s.socket ?: 0), Event.RetryDue) }
        assertEquals(SyncLink.MAX_BACKOFF, s.backoff)
        // a renewal that worked reconnects at once, from the first wait again
        val renewed = SyncLink.reduce(renewing, Event.RenewalDone(Renewal.Renewed))
        assertEquals(Status.Connecting, renewed.state.status)
        assertTrue(Effect.Open(2) in renewed.effects)
    }

    @Test fun aSecondUnauthenticatedWhileRenewingChangesNothing() {
        val renewing = live.on(Event.Unauthenticated(1))
        assertEquals(renewing, renewing.on(Event.Unauthenticated(1)))
        assertEquals(renewing, renewing.on(Event.ConnectNow))
    }

    @Test fun aLateWelcomeFromASocketNoLongerCurrentIsIgnored() {
        // socket 1 dropped, 2 is connecting: 1's welcome arrives late
        val reopened = connecting.on(Event.Dropped(1), Event.ConnectNow)
        assertEquals(2L, reopened.socket)
        val late = reopened.on(Event.Welcome(1))
        assertEquals(Status.Connecting, late.status)
        assertFalse(late.liveOn(1))
        // and after going to the background, a welcome from the closed socket can't make it live
        val background = connecting.on(Event.Wanted(false))
        assertEquals(Status.Offline, background.on(Event.Welcome(1)).status)
    }

    @Test fun aBackgroundCloseThenAStaleFrameDoesNotReportCaughtUp() {
        val closed = SyncLink.reduce(live, Event.Wanted(false))
        assertEquals(Status.Offline, closed.state.status)
        assertNull(closed.state.socket)
        assertTrue(Effect.Close(1, "background") in closed.effects)
        assertTrue(Effect.Disconnected in closed.effects)
        // Chorus reports caught-up only while liveOn(the frame's socket)
        assertFalse(closed.state.liveOn(1))
        // its close callback afterwards counts no failure and books no retry
        val echoed = SyncLink.reduce(closed.state, Event.Dropped(1))
        assertEquals(closed.state, echoed.state)
        assertEquals(emptyList<Effect>(), echoed.effects)
    }

    @Test fun aDropCountsAFailureAndAWelcomeClearsThem() {
        val dropped = live.on(Event.Dropped(1))
        assertEquals(1, dropped.failures)
        assertEquals(Status.Offline, dropped.status)
        assertEquals(0, dropped.on(Event.RetryDue, Event.Welcome(2)).failures)
    }

    @Test fun aRefusalFromAnotherSignedInCallClosesTheLiveSocket() {
        val step = SyncLink.reduce(live, Event.RenewalDone(Renewal.Refused))
        assertEquals(Status.SignedOut, step.state.status)
        assertTrue(Effect.Close(1, "signed out") in step.effects)
        // a renewal another call made, or one that failed, leaves a live socket alone
        assertEquals(live, live.on(Event.RenewalDone(Renewal.Renewed)))
        assertEquals(live, live.on(Event.RenewalDone(Renewal.Failed)))
    }

    @Test fun aColdStartSignedOutAsksNothingAndLinkingAgainLeavesIt() {
        val cold = State().on(Event.Wanted(true), Event.Loaded(device = true, signedOut = true))
        assertEquals(Status.SignedOut, cold.status)
        assertNull(cold.socket)
        // adopt() forgets the flag and opens again
        val linked = SyncLink.reduce(cold, Event.Loaded(device = true, signedOut = false))
        assertEquals(Status.Connecting, linked.state.status)
        assertEquals(SyncLink.FIRST_BACKOFF, linked.state.backoff)
        assertTrue(linked.effects.any { it is Effect.Open })
    }

    @Test fun storageFailureIsFinal() {
        val failed = SyncLink.reduce(live, Event.StorageFailed)
        assertEquals(Status.StorageError, failed.state.status)
        assertTrue(Effect.Close(1, "storage unavailable") in failed.effects)
        assertEquals(Status.StorageError, failed.state.on(Event.ConnectNow, Event.Loaded(device = true, signedOut = false),
            Event.RenewalDone(Renewal.Refused)).status)
    }
}
