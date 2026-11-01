package garden.vayne.chorus.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushTest {
    @Test
    fun fcmIsPreferredByDefaultEvenWhenNtfyIsInstalled() {
        val got = Push.selectTarget(
            fcmToken = "fcm-token-123",
            distributors = listOf("io.heckel.ntfy"),
            currentDistributor = "io.heckel.ntfy",
            choice = null,
        )
        assertEquals(Push.Target.Fcm("fcm-token-123"), got)
    }

    @Test
    fun explicitDistributorChoiceOverridesFcmWhenInstalled() {
        val got = Push.selectTarget(
            fcmToken = "fcm-token-123",
            distributors = listOf("io.heckel.ntfy"),
            currentDistributor = Fcm.DISTRIBUTOR,
            choice = "distributor:io.heckel.ntfy",
        )
        assertEquals(Push.Target.Distributor("io.heckel.ntfy"), got)

        // if that distributor was uninstalled, fall back to FCM rather than breaking push
        val uninstalled = Push.selectTarget(
            fcmToken = "fcm-token-123",
            distributors = emptyList(),
            currentDistributor = "io.heckel.ntfy",
            choice = "distributor:io.heckel.ntfy",
        )
        assertEquals(Push.Target.Fcm("fcm-token-123"), uninstalled)
    }

    @Test
    fun fallsBackToUnifiedPushOrPollingWhenFcmIsUnavailable() {
        val ntfy = Push.selectTarget(
            fcmToken = null,
            distributors = listOf("org.example.push", "io.heckel.ntfy"),
            currentDistributor = null,
            choice = null,
        )
        assertEquals(Push.Target.Distributor("io.heckel.ntfy"), ntfy)

        val none = Push.selectTarget(
            fcmToken = null,
            distributors = emptyList(),
            currentDistributor = "io.heckel.ntfy",
            choice = null,
        )
        assertEquals(Push.Target.None, none)
    }

    @Test
    fun stateRoundTripsThroughJson() {
        val keys = WebPushCrypto.generate()
        val s = Push.State("tok-1", Fcm.DISTRIBUTOR, "fcm:abc123", keys)
        val parsed = Push.State.parse(s.json())
        assertEquals("tok-1", parsed.token)
        assertEquals(Fcm.DISTRIBUTOR, parsed.distributor)
        assertEquals("fcm:abc123", parsed.endpoint)
        assertArrayEquals(keys.privatePkcs8, parsed.keys.privatePkcs8)
        assertArrayEquals(keys.publicRaw, parsed.keys.publicRaw)
        assertArrayEquals(keys.auth, parsed.keys.auth)

        val empty = Push.State.parse(s.copy(endpoint = null).json())
        assertNull(empty.endpoint)
    }
}
