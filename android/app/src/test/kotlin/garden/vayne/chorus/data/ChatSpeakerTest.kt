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
        val picked = ChatSpeaker.pick(model, "c", core = { input ->
            val context = JSONObject(input)
            assertEquals("latch", context.getString("mode"))
            assertEquals("moss", context.getJSONArray("last_authors").getString(0))
            assertEquals("kai", context.getJSONArray("fronting").getJSONObject(0).getString("member_id"))
            assertEquals(2, context.getJSONArray("members").length())
            JSONArray().put("moss").toString()
        })
        assertEquals("moss", picked)
        assertEquals("autoproxy:c", ChatSpeaker.payload("c", "member", "kai").getString("key"))
    }

    @Test fun channelFrontSelectsAuthorshipAndReadingPoolsSeparatelyFromAutoproxy() {
        val account = "account"
        val channel = ChatChannel("c", "s", "text", "general", null)
        fun pref(value: Any) = JSONObject().put("exists", true).put("fields", JSONObject().put("value", value))
        val projection = JSONObject().put("rows", JSONObject().put("pref", JSONObject()
            .put("||autoproxy:c", pref(JSONObject().put("mode", "latch")))
            .put("$account||authorship_front:c", pref("head"))))
        val choices = ChatSpeaker.preferences(projection, account, listOf(channel))
        assertEquals(SpeakerDefault("latch", null, "head"), choices["c"])
        val rin = FrontPresence("member", "rin", "quiet", false)
        fun resolved(id: String, reading: List<FrontPresence>) = ResolvedFront(id, id, emptyList(), emptyList(), null,
            "independent", null, false, emptyList(), emptyList(), mapOf("reading" to reading))
        val named = NamedFronts("{\"fronts\":{}}", emptyList(), emptyMap(),
            mapOf(account to resolved(account, emptyList()), "head" to resolved("head", listOf(rin))), account)
        val members = listOf("rin", "kai").map { Member(it, it, null, null, "#fff", emptyList(), null, false) }
        val model = Model(members, emptyList(), emptyMap(), emptyList(), null, emptyList(),
            speakerDefaults = choices, namedFronts = named)
        assertEquals("head", ChatSpeaker.frontOf(model, "c"))
        assertEquals(account, ChatSpeaker.frontOf(model, "other"))
        val picked = ChatSpeaker.pick(model, "c", { error("legacy rule is not used once configured") }) { context, system, body, front ->
            assertEquals("latch", JSONObject(context).getString("mode"))
            assertEquals(named.systemJson, system)
            assertEquals(account, body)
            assertEquals("head", front)
            JSONArray().put("rin").toString()
        }
        assertEquals("rin", picked)
        val readers = ReadTracking.readersFor(model, "c", true, { _, _ -> error("legacy readers") }) { per, pool, own ->
            assertEquals(true, per)
            assertEquals("rin", JSONArray(pool).getJSONObject(0).getString("subject_id"))
            assertEquals(2, JSONArray(own).length())
            JSONArray().put("").put("rin").toString()
        }
        assertEquals(listOf("", "rin"), readers)
        val payload = ChatSpeaker.frontPayload("c", "head")
        assertEquals("authorship_front:c", payload.getString("key"))
        assertEquals("head", payload.getString("value"))
    }
}
