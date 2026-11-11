package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignedOutTest {
    private fun refused(code: String, body: String = "{}") = ApiException("refused", code, JSONObject(body))

    @Test fun signedOutForbiddenAndBadKeyAreSignedOutEverythingElseIsTransient() {
        val signedOut = refused("signed_out",
            """{"error":{"code":"signed_out","message":"this device was signed out","retry":false},"signed_out":{"at":1000,"wipe_at":2000}}""")
        assertEquals(SignedOutRecord(1000, 2000, 7), SignedOut.refusal(signedOut, offset = 7))
        // an older server, or a device it no longer has; a key that no longer matches: no times
        assertEquals(SignedOutRecord(), SignedOut.refusal(refused("forbidden")))
        assertEquals(SignedOutRecord(), SignedOut.refusal(refused("bad_key")))
        assertEquals(SignedOutRecord(at = 5), SignedOut.refusal(refused("signed_out", """{"signed_out":{"at":5,"wipe_at":null}}""")))
        for (code in listOf("unauthenticated", "rate_limited", "internal", "", "bad_request")) {
            assertNull(code, SignedOut.refusal(refused(code)))
        }
    }

    @Test fun theDeadlineIsTheServersCorrectedByTheClockOffset() {
        val record = SignedOutRecord(at = 0, wipeAt = 10_000, offset = 2_000)
        // our clock is 2 s behind the server's: due at our 8 000
        assertFalse(record.due(7_999))
        assertTrue(record.due(8_000))
        assertEquals(1_000L, record.delay(7_000))
        assertEquals(0L, record.delay(9_000))
        // no times: never on a timer
        assertFalse(SignedOutRecord().due(Long.MAX_VALUE / 2))
        assertNull(SignedOutRecord().delay(0))
        // wipe at once: due as soon as it is known
        assertTrue(SignedOutRecord(at = 5, wipeAt = 5).due(5))
    }

    @Test fun theRecordRoundTripsAndAnythingElseIsATimelessSignOut() {
        val record = SignedOutRecord(1, 2, -3)
        assertEquals(record, SignedOutRecord.fromJson(record.toJson()))
        assertEquals(SignedOutRecord(), SignedOutRecord.fromJson(SignedOutRecord().toJson()))
        // part A's first shape (a bare time) and junk
        assertEquals(SignedOutRecord(), SignedOutRecord.fromJson("1731300000000"))
        assertEquals(SignedOutRecord(), SignedOutRecord.fromJson("{"))
    }

    @Test fun theWelcomeLineSaysWhenWhatIsKeptIsErased() {
        assertEquals("This phone was signed out from another device.", SignedOut.notice(SignedOutRecord()) { "never" })
        val line = SignedOut.notice(SignedOutRecord(1, 2)) { "13 November 2026" }
        assertTrue(line.startsWith("This phone was signed out from another device."))
        assertTrue(line.contains("erased on 13 November 2026"))
        assertTrue(line.contains("linked to the same account again"))
    }

    @Test fun onlyADeviceLinkOfTheKeptAccountPicksItBackUp() {
        assertEquals(SignedOut.Enrol.Fresh, SignedOut.enrol(null, InvitePeek("system")))
        assertEquals(SignedOut.Enrol.Fresh, SignedOut.enrol(null, InvitePeek("device", "acc")))
        assertEquals(SignedOut.Enrol.PickUp, SignedOut.enrol("acc", InvitePeek("device", "acc")))
        assertEquals(SignedOut.Enrol.EraseFirst, SignedOut.enrol("acc", InvitePeek("device", "other")))
        // the server didn't say whose: can't be told from another account's
        assertEquals(SignedOut.Enrol.EraseFirst, SignedOut.enrol("acc", InvitePeek("device")))
        assertEquals(SignedOut.Enrol.EraseFirst, SignedOut.enrol("acc", InvitePeek("system")))
        assertEquals(SignedOut.Enrol.EraseFirst, SignedOut.enrol("acc", InvitePeek("person")))
        assertEquals(SignedOut.Enrol.EraseFirst, SignedOut.enrol("acc", InvitePeek("")))
        assertTrue(SignedOut.eraseFirstText(InvitePeek("device", "other")).contains("another account"))
        assertTrue(SignedOut.eraseFirstText(InvitePeek("system")).contains("new account"))
        assertTrue(SignedOut.eraseFirstText(null).contains("never synced"))
    }

    @Test fun thePeekCarriesTheDeviceInvitesAccount() {
        assertEquals(InvitePeek("device", "acc"), Api.parsePeek(JSONObject("""{"kind":"device","account_id":"acc"}""")))
        assertEquals(InvitePeek("device"), Api.parsePeek(JSONObject("""{"kind":"device"}""")))
        assertEquals(InvitePeek("device"), Api.parsePeek(JSONObject("""{"kind":"device","account_id":null}""")))
        // only a device invite says whose; an older server says nothing
        assertEquals(InvitePeek("system"), Api.parsePeek(JSONObject("""{"kind":"system","account_id":"acc"}""")))
        assertEquals(InvitePeek(""), Api.parsePeek(JSONObject("{}")))
    }

    @Test fun signingADeviceOutCanAskForItsCopyToBeErasedAtOnce() {
        assertEquals("""{"wipe":true}""", DeviceLinks.revokeBody(true).toString())
        assertEquals("{}", DeviceLinks.revokeBody(false).toString())
        assertTrue(DeviceLinks.revokeText(true).contains("next time it connects"))
        assertTrue(DeviceLinks.revokeText(false).contains("two days"))
    }
}
