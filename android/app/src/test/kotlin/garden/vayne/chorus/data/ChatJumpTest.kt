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

    @Test fun theDownArrowRetracesReplyJumpsOneAtATime() {
        // reading m9, its reply leads to m5; m5's reply leads to m2
        var trail = ChatJump.push(emptyList(), "m9", "m5")
        trail = ChatJump.push(trail, "m5", "m2")
        assertEquals(listOf("m9", "m5"), trail)
        val present = setOf("m2", "m5", "m9")
        val first = ChatJump.back(trail, present)!!
        assertEquals("m5", first.id)
        val second = ChatJump.back(first.rest, present)!!
        assertEquals("m9", second.id)
        // then the latest message
        assertNull(ChatJump.back(second.rest, present))
        // jumping again from a message already on the trail moves it to the top, once
        assertEquals(listOf("m5", "m9"), ChatJump.push(listOf("m9", "m5"), "m9", "m1"))
        assertEquals(listOf("m9"), ChatJump.push(listOf("m9"), null, "m1"))
        // a start that has since gone (deleted, out of the window) is skipped
        assertEquals("m9", ChatJump.back(listOf("m9", "gone"), present)!!.id)
    }
}
