package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatQuotesTest {
    private fun message(id: String, text: String, at: Long) = ChatMessage(id, "c", listOf("kai"), text, at, null, "all", emptySet(), "me", null, emptyList())

    @Test fun quotesReadBothShapesAndWriteThemAsWebDoes() {
        val one = ChatForward.quote(listOf(message("m", "hello there", 1)), "general")
        assertEquals(listOf("m", "0", "11", "hello there"), listOf("message_id", "offset", "length", "text").map { one.get(it).toString() })
        assertEquals(listOf(ForwardItem("m", "hello there", null, emptyList(), 0)), ChatForward.quoted(one))
        val bundle = ChatForward.quote(listOf(message("a", "one", 1), message("b", "two", 2)), "general")
        assertEquals(listOf("a", "b"), ChatForward.quoted(bundle).map { it.messageId })
        assertEquals("general", ChatForward.quoted(bundle).first().channelName)
        assertTrue(ChatForward.quoted(JSONObject()).isEmpty())
        assertTrue(ChatForward.quoted(null).isEmpty())
    }

    @Test fun aForwardedBundleGoesOldestFirst() {
        val p = ChatForward.bundle("t", "kai", listOf(message("b", "two", 2), message("a", "one", 1)), "general", emptyList())
        assertEquals("b", p.getString("forward_of_id"))
        assertEquals("a", p.getJSONArray("forward_snapshot").getJSONObject(0).getString("message_id"))
    }

    @Test fun mentionsSuggestAndResolveOnlyUnambiguousNames() {
        val members = listOf(Member("k", "Kai", null, null, "#fff", emptyList(), null, false),
            Member("r", "Rin Ash", null, null, "#fff", emptyList(), null, false),
            Member("k2", "kai", "Kai Two", null, "#fff", emptyList(), null, false))
        val model = Model(members, listOf(Group("g", "Garden", "group", null, null)), emptyMap(), emptyList(), null, emptyList())
        assertEquals("ga", ChatMentions.typing("hi @ga"))
        assertNull(ChatMentions.typing("hi @ga "))
        assertEquals("hi @Rin_Ash ", ChatMentions.insert("hi @ri", ChatMentions.matches(model, "ri").first { it.id == "r" }))
        val names = ChatMentions.names(model).getJSONObject("mentions")
        assertEquals("r", names.getJSONObject("rin_ash").getString("target_id"))
        assertEquals("group", names.getJSONObject("garden").getString("target_type"))
        assertTrue(!names.has("kai")) // two members answer to Kai: neither is guessed
        assertEquals("k2", names.getJSONObject("kai_two").getString("target_id"))
    }
}
