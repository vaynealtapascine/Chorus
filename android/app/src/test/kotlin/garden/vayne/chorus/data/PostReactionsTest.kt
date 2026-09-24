package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PostReactionsTest {
    @Test fun addAndRemoveUseTheSameSetElement() {
        val payload = PostReactions.payload("post", "member")
        assertEquals("post", payload.getString("target_id"))
        assertEquals("post", payload.getString("target_type"))
        assertEquals("member", payload.getString("member_id"))
        val added = PostReactions.toggle(emptyList(), "member", "Kai")
        assertEquals(listOf(PostReaction("💜", "member", "Kai")), added)
        assertEquals(emptyList<PostReaction>(), PostReactions.toggle(added, "member", "Kai"))
    }
}
