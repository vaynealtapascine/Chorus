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

    @Test fun aReactionByAMemberWeMayNotSeeCountsAsSomeoneAndIsNeverOurs() {
        val post = JSONObject("""{"reactions":[{"emoji":"💜","member_id":null,"member_name":null},
            {"emoji":"💜","member_id":"mine","member_name":"Kai"},{"emoji":"🎉"},{"emoji":null,"member_id":"x"}]}""")
        val reactions = PostReactions.fromServer(post)
        assertEquals(listOf(PostReaction("💜", null, "Someone"), PostReaction("💜", "mine", "Kai"), PostReaction("🎉", null, "Someone")), reactions)
        // counted under its emoji
        assertEquals(2, reactions.count { it.emoji == PostReactions.HEART })
        // never matched as one of ours: toggling our heart touches only ours
        assertEquals(listOf(PostReaction("💜", null, "Someone"), PostReaction("🎉", null, "Someone")),
            PostReactions.toggle(reactions, "mine", "Kai"))
        // and our own local heart isn't taken for the hidden one
        val local = listOf(PostReaction("💜", "june", "June"))
        assertEquals(reactions + local, PostReactions.merge(local, reactions))
        // a name without a member isn't shown either
        assertEquals("Someone", PostReactions.fromServer(JSONObject("""{"reactions":[{"emoji":"💜","member_name":"Leak"}]}""")).single().memberName)
    }

    @Test fun serverAndLocalReactionsMergeWithoutDuplicatingOwnMember() {
        val local = listOf(PostReaction("💜", "mine", "Kai"))
        val remote = listOf(PostReaction("💜", "mine", "Kai"), PostReaction("🎉", "other", "June"))
        assertEquals(remote, PostReactions.merge(local, remote))
    }
}
