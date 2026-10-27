package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TypingTest {
    private val a = TypingEntry("own", "kai", 7000)
    private val b = TypingEntry("other", "hidden", 7000)
    private fun model(): Model = Model.parse(JSONObject("""{"rows":{"member":{
        "kai":{"exists":true,"fields":{"name":"Kai","display_name":"Amarynthe"}},
        "hidden":{"exists":true,"fields":{"name":"Do not leak this by matching a foreign ID"}}
    }}}"""), "own", TypingNames("own", mapOf("other" to "Moon"),
        mapOf("visible" to ForeignAuthor("visible", "other", "Star"))))

    @Test fun labelUsesOnlyOwnOrVisibleMembersAndFallsBackToAccount() {
        val m = model()
        assertNull(TypingText.label(m, emptyList()))
        assertEquals("Amarynthe is typing…", TypingText.label(m, listOf(a, a)))
        assertEquals("Amarynthe and Moon are typing…", TypingText.label(m, listOf(a, b)))
        assertEquals("Star is typing…", TypingText.label(m, listOf(b.copy(member = "visible"))))
        assertEquals("Moon is typing…", TypingText.label(m, listOf(b.copy(member = null))))
        assertEquals("Someone is typing…", TypingText.label(m, listOf(b.copy(account = "unknown"))))
        assertEquals("Several people are typing…", TypingText.label(m, listOf(a, b, b.copy(member = null))))
    }

    @Test fun refreshStopAndExpiryArePerIdentityAndChannel() {
        var channels = TypingExpiry.receive(emptyMap(), "ch", a, true, 1000)
        channels = TypingExpiry.receive(channels, "ch", a.copy(until = 9000), true, 3000)
        channels = TypingExpiry.receive(channels, "ch", b, true, 3000)
        channels = TypingExpiry.receive(channels, "other", a, true, 3000)
        assertEquals(2, channels["ch"]!!.size)
        channels = TypingExpiry.receive(channels, "ch", b, false, 3001)
        assertEquals(listOf(a.copy(until = 9000)), channels["ch"])
        channels = TypingExpiry.expire(channels, 7000)
        assertFalse(channels.containsKey("other"))
        assertEquals(emptyMap<String, List<TypingEntry>>(), TypingExpiry.expire(channels, 9000))
        assertTrue(TypingExpiry.receive(emptyMap(), "ch", a, true, 7000).isEmpty())
    }

    @Test fun throttleIsPerChannelAndStopsNeverResetIt() {
        val gate = TypingThrottle()
        assertTrue(gate.allow("ch", true, 1000))
        assertTrue(gate.allow("ch", false, 1001))
        assertFalse(gate.allow("ch", true, 2999))
        assertTrue(gate.allow("other", true, 2999))
        assertTrue(gate.allow("ch", true, 3000))
    }

    @Test fun shareTypingDefaultsOffAndReadsOnlyTheAccountPref() {
        assertFalse(AccountPrefs.fromProjection(JSONObject(), "own").shareTyping)
        val p = JSONObject("""{"rows":{"pref":{"own||share_typing":{"exists":true,"fields":{"value":true}}}}}""")
        assertTrue(AccountPrefs.fromProjection(p, "own").shareTyping)
        p.getJSONObject("rows").getJSONObject("pref").put("||share_typing", JSONObject("""{"exists":true,"fields":{"value":false}}"""))
        assertFalse(AccountPrefs.fromProjection(p, "own").shareTyping)
    }
}
