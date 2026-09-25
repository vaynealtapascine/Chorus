package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSpeakerTest {
    @Test fun pendingChannelPreferenceWinsAndCoreReceivesFullContext() {
        val account = "account"
        val channel = ChatChannel("c", "s", "text", "general", null)
        fun row(mode: String, member: String? = null) = JSONObject().put("exists", true)
            .put("fields", JSONObject().put("value", JSONObject().put("mode", mode).apply {
                if (member != null) put("member", member)
            }))
        val projection = JSONObject().put("rows", JSONObject().put("pref", JSONObject()
            .put("$account||autoproxy:c", row("front"))
            .put("||autoproxy:c", row("latch"))))
        val choices = ChatSpeaker.preferences(projection, account, listOf(channel))
        assertEquals(SpeakerDefault("latch"), choices["c"])
        val members = listOf("kai", "moss").map {
            Member(it, it, null, null, "#fff", emptyList(), null, false)
        }
        val model = Model(members, emptyList(), emptyMap(), listOf(Entry("member", "kai", "front", true)),
            null, emptyList(), speakerDefaults = choices, lastAuthorsByChannel = mapOf("c" to listOf("moss")))
        val picked = ChatSpeaker.pick(model, "c") { input ->
            val context = JSONObject(input)
            assertEquals("latch", context.getString("mode"))
            assertEquals("moss", context.getJSONArray("last_authors").getString(0))
            assertEquals("kai", context.getJSONArray("fronting").getJSONObject(0).getString("member_id"))
            assertEquals(2, context.getJSONArray("members").length())
            JSONArray().put("moss").toString()
        }
        assertEquals("moss", picked)
        assertEquals("autoproxy:c", ChatSpeaker.payload("c", "member", "kai").getString("key"))
    }
}
