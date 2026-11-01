package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatJumpTest {
    private fun message(id: String, channel: String) =
        ChatMessage(id, channel, listOf("kai"), "hi", 1, null, "all", emptySet(), "acct", null, emptyList())

    @Test fun findsTheOriginalHereThenElsewhereAndNothingHidden() {
        val here = listOf(message("a", "general"))
        val everywhere = mapOf("b" to message("b", "plans"), "a" to message("a", "general"))
        assertEquals("general", ChatJump.channelOf("a", here, everywhere))
        assertEquals("plans", ChatJump.channelOf("b", here, everywhere))
        // a private chat that isn't shown now, or a message too old for the window: no jump
        assertNull(ChatJump.channelOf("gone", here, everywhere))
    }
}
