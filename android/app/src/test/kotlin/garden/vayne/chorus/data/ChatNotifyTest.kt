package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatNotifyTest {
    private fun row(v: String) = JSONObject().put("exists", true).put("fields", JSONObject().put("value", v))

    @Test fun channelLevelsAreAccountWideAndPendingWins() {
        val p = JSONObject().put("rows", JSONObject().put("pref", JSONObject()
            .put("acct||notify_channel:c1", row("none"))
            .put("||notify_channel:c1", row("all"))
            .put("acct||notify_channel:c2", row("none"))
            .put("other||notify_channel:c3", row("none"))
            .put("acct|phone|notify_channel:c4", row("none"))))
        val prefs = AccountPrefs.fromProjection(p, "acct")
        assertEquals(mapOf("c1" to "all", "c2" to "none"), prefs.notifyChannels)
        assertEquals("none", prefs.channelNotify("c2", "internal"))
        assertEquals("mentions", prefs.channelNotify("c9", "shared"))
        assertEquals("all", prefs.channelNotify("c9", "dm"))
    }
}
