package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RingsTest {
    private fun push(json: String) = JSONObject(json)

    @Test fun everyKindOfPushRingsAsOneChoice() {
        assertEquals("mention", Rings.keyOf(push("""{"t":"message","kind":"mention"}""")))
        assertEquals("dm", Rings.keyOf(push("""{"t":"message","kind":"member_dm"}""")))
        assertEquals("reply", Rings.keyOf(push("""{"t":"message","kind":"reply"}""")))
        assertEquals("reply", Rings.keyOf(push("""{"t":"post","kind":"post_reply"}""")))
        assertEquals("post", Rings.keyOf(push("""{"t":"post"}""")))
        assertEquals("switch", Rings.keyOf(push("""{"t":"switch","account_id":"a1"}""")))
        assertEquals("own_switch", Rings.keyOf(push("""{"t":"switch","account_id":"own"}""")))
        assertEquals("own_switch", Rings.keyOf(push("""{"t":"own_switch"}""")))
        assertEquals("poll_nudge", Rings.keyOf(push("""{"t":"poll"}""")))
        // a channel set to "all", a test and a request for alerts always ring
        assertNull(Rings.keyOf(push("""{"t":"message","kind":"message"}""")))
        assertNull(Rings.keyOf(push("""{"t":"test"}""")))
        assertNull(Rings.keyOf(push("""{"t":"alert"}""")))
    }

    @Test fun eachKindHasItsOwnChannel() {
        assertEquals(Rings.KINDS.size, Rings.KINDS.map { it.channel }.toSet().size)
        assertEquals("poll_nudges", Rings.channelOf(push("""{"t":"poll"}""")))
        assertNull(Rings.channelOf(push("""{"t":"test"}""")))
    }

    @Test fun agoReadsLikeAPerson() {
        val m = 60_000L
        assertEquals("just now", Rings.ago(0, 30_000))
        assertEquals("2 min ago", Rings.ago(0, 2 * m))
        assertEquals("3 h ago", Rings.ago(0, 3 * 60 * m))
        assertEquals("1 day ago", Rings.ago(0, 24 * 60 * m))
        assertEquals("2 days ago", Rings.ago(0, 48 * 60 * m))
    }
}
