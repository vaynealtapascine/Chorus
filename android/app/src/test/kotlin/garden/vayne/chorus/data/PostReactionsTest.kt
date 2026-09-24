package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.json.JSONObject

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

    @Test fun localProjectionIncludesOnlyPresentMatchingPostReactions() {
        val sets = JSONObject().put("reaction", JSONObject()
            .put("p|{\"target_type\":\"post\",\"target_id\":\"p\",\"emoji\":\"💜\",\"member_id\":\"m\"}", true)
            .put("p|{\"target_type\":\"post\",\"target_id\":\"p\",\"emoji\":\"🎉\",\"member_id\":\"m\"}", false)
            .put("p|{\"target_type\":\"message\",\"target_id\":\"p\",\"emoji\":\"✅\",\"member_id\":\"m\"}", true)
            .put("p|not-json", true))
        assertEquals(listOf(PostReaction("💜", "m", "Kai")), PostReactions.fromProjection(sets, mapOf("m" to "Kai"))["p"])
    }

    @Test fun serverAndLocalReactionsMergeWithoutDuplicatingOwnMember() {
        val local = listOf(PostReaction("💜", "mine", "Kai"))
        val remote = listOf(PostReaction("💜", "mine", "Kai"), PostReaction("🎉", "other", "June"))
        assertEquals(remote, PostReactions.merge(local, remote))
    }
}
