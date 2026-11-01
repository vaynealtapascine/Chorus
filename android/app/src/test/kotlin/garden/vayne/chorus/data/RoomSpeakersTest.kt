package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomSpeakersTest {
    private val members = listOf("kai", "june", "rin", "moss").map { Member(it, it, null, null, "#fff", emptyList(), null, false) }
    private val front = listOf(Entry("member", "june", "front", false), Entry("member", "kai", "front", true),
        Entry("member", "rin", "cocon", false), Entry("member", "moss", "present", false))
    private fun model(setting: SpeakerDefault) = Model(members, emptyList(), emptyMap(), front, null, emptyList(),
        speakerDefaults = mapOf("c" to setting), lastAuthorsByChannel = mapOf("c" to listOf("moss")))
    private val all = setOf("kai", "june", "rin", "moss")

    @Test fun speakersFollowTheFrontAChosenSetOneMemberOrTheLastSpeaker() {
        assertEquals(listOf("kai", "june", "rin"), ChatSpeaker.speakers(model(SpeakerDefault()), "c", all))
        assertEquals(listOf("rin", "june"), ChatSpeaker.speakers(model(SpeakerDefault("members", members = listOf("rin", "gone", "june"))), "c", all))
        assertEquals(listOf("moss"), ChatSpeaker.speakers(model(SpeakerDefault("latch")), "c", all))
        assertEquals(emptyList<String>(), ChatSpeaker.speakers(model(SpeakerDefault("off")), "c", all))
        assertEquals(listOf("kai"), ChatSpeaker.speakers(model(SpeakerDefault()), "c", setOf("kai")))
    }

    @Test fun severalChosenSpeakersArePrefsAndTheFirstIsTheDefault() {
        val payload = ChatSpeaker.payload("c", "members", members = listOf("rin", "june", "rin"))
        assertEquals("""["rin","june"]""", payload.getJSONObject("value").getJSONArray("members").toString())
        val projection = JSONObject().put("rows", JSONObject().put("pref", JSONObject()
            .put("a||autoproxy:c", JSONObject().put("exists", true).put("fields", JSONObject().put("value", payload.getJSONObject("value"))))))
        val setting = ChatSpeaker.preferences(projection, "a", listOf(ChatChannel("c", "s", "text", "general", null)))["c"]!!
        assertEquals(listOf("rin", "june"), setting.members)
        val picked = ChatSpeaker.pick(model(setting), "c", core = { input ->
            val context = JSONObject(input)
            assertEquals("member", context.getString("mode"))
            assertEquals("rin", context.getString("member"))
            """["rin"]"""
        })
        assertEquals("rin", picked)
    }

    @Test fun ourMessagesByAnySpeakerSitOnOurSide() {
        fun message(account: String?, vararg authors: String) = ChatMessage("m", "c", authors.toList(), "hi", 1, null, "all", emptySet(), account, null, emptyList())
        val aligned = setOf("kai", "leaf")
        assertTrue(ChatPresentation.own(message("me", "leaf"), "me", aligned))
        assertTrue(ChatPresentation.own(message(null, "june", "kai"), "me", aligned))
        assertFalse(ChatPresentation.own(message("me", "june"), "me", aligned))
        assertFalse(ChatPresentation.own(message("them", "kai"), "me", aligned))
    }
}
