package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReactionsTest {
    private fun key(message: String, emoji: String, member: String, type: String = "message", target: String = message) =
        "$message|" + JSONObject().put("emoji", emoji).put("member_id", member).put("target_id", target).put("target_type", type)

    @Test fun groupsPresentMessageReactionsMostChosenFirst() {
        val sets = JSONObject().put("reaction", JSONObject()
            .put(key("m1", "👍", "kai"), true)
            .put(key("m1", "❤️", "rin"), true)
            .put(key("m1", "❤️", "kai"), true)
            .put(key("m1", "😂", "kai"), false)
            .put(key("p1", "💜", "kai", type = "post"), true)
            .put(key("m2", "👀", "kai", target = "elsewhere"), true))
        val all = ChatReactions.fromProjection(sets)
        assertEquals(setOf("m1"), all.keys)
        assertEquals(listOf(ChatReaction("❤️", listOf("kai", "rin")), ChatReaction("👍", listOf("kai"))), all["m1"])
        assertTrue(ChatReactions.chosen(all["m1"]!!, "❤️", "rin"))
        assertFalse(ChatReactions.chosen(all["m1"]!!, "👍", "rin"))
        assertFalse(ChatReactions.chosen(all["m1"]!!, "❤️", null))
    }

    @Test fun addAndRemoveShareOneElement() {
        val add = ChatReactions.payload("m1", "❤️", "kai")
        // core keys the element by its canonical (sorted) payload, so field order does not matter
        assertEquals(listOf("message", "m1", "❤️", "kai"), listOf("target_type", "target_id", "emoji", "member_id").map(add::getString))
        assertEquals(4, add.length())
    }
}
