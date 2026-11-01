package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotifyCheckTest {
    @Test fun listedNotificationsBecomeThePushesTheyWouldHaveBeen() {
        val switch = NotifyCheck.asPush(JSONObject("""{"id":"n1","kind":"switch","text":"Kai is fronting",
            "time":{"at":5,"precision":"exact"},"delivered_at":9,"account":{"id":"a1","handle":"stars","display_name":null}}"""))!!
        assertEquals("switch", switch.getString("t"))
        assertEquals("a1", switch.getString("account_id"))
        assertEquals("@stars", switch.getString("title"))
        assertEquals("Kai is fronting", switch.getString("text"))

        val dm = NotifyCheck.asPush(JSONObject("""{"id":"n2","kind":"dm","title":"Robin","text":"hi",
            "channel_id":"c1","message_id":"m1","scope":"space:s1","reply_as":null,"delivered_at":10}"""))!!
        assertEquals("message", dm.getString("t"))
        assertEquals("space:s1", dm.getString("scope"))
        assertEquals("m1", dm.getString("message_id"))
        assertEquals(false, dm.has("reply_as"))

        assertNull(NotifyCheck.asPush(JSONObject("""{"id":"n3","kind":"export_ready","delivered_at":11}""")))
    }

    @Test fun postAlertsOpenThePostOrFriends() {
        val post = NotifyCheck.asPush(JSONObject("""{"id":"n4","kind":"post","title":"Kai posted","text":"hello",
            "post_id":"p1","account_id":"a1","member_id":"k1","delivered_at":12}"""))!!
        assertEquals("post", post.getString("t"))
        assertEquals("p1", post.getString("post_id"))
        assertEquals("Kai posted", post.getString("title"))

        val asked = NotifyCheck.asPush(JSONObject("""{"id":"n5","kind":"alert_request","title":"alex asked for post alerts",
            "text":"They'd like to hear when Kai posts.","member_id":"k1","account_id":"a2","delivered_at":13}"""))!!
        assertEquals("alert", asked.getString("t"))
        assertEquals("alert_request", asked.getString("kind"))
        assertEquals("k1", asked.getString("member_id"))
        assertEquals("alert", NotifyCheck.asPush(JSONObject("""{"id":"n6","kind":"alert_accepted","delivered_at":14}"""))!!.getString("t"))
    }
}
