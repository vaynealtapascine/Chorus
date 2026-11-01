package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostComposeTest {
    private val model = Model(listOf(Member("own", "Kai", null, null, "#fff", emptyList(), null, false,
        createdByAccountId = "mine"), Member("foreign", "Alex", null, null, "#fff", emptyList(), null, false,
        createdByAccountId = "theirs")), emptyList(), emptyMap(), emptyList(), null, emptyList())
    private val markup: (String, String) -> String = { body, _ ->
        assertEquals("**hello**", body)
        """{"text":"hello","entities":[{"type":"bold","offset":0,"length":5}]}"""
    }

    @Test fun entryKeepsCoreRichTextAudienceReplyAndTags() {
        val p = PostCompose.payload(model, "mine", "entry", "own", "**hello**", "Title", "Sensitive", "followers",
            "bright", "#day, notes", "parent", markup, listOf("photo", "notes-file"))
        assertEquals("hello", p.getString("text"))
        assertEquals("bold", p.getJSONArray("entities").getJSONObject(0).getString("type"))
        assertEquals("followers", p.getJSONObject("visibility").getString("mode"))
        assertEquals("parent", p.getString("reply_to"))
        assertEquals("day", p.getJSONArray("tags").getString(0))
        assertEquals(listOf("photo", "notes-file"), (0 until p.getJSONArray("attachments").length()).map {
            p.getJSONArray("attachments").getString(it)
        })
    }

    @Test fun createPreservesBucketAudienceAndQuoteMediaReply() {
        val p = PostCompose.payload(Model(model.members, emptyList(), emptyMap(), emptyList(), null, emptyList(), sharingBuckets = listOf("a" to "Friends")), "mine", "entry", "own",
            "**hello**", "Title", "CW", "buckets", "mood", "tag", "parent", markup,
            listOf("photo"), "quoted", listOf("a"))
        assertEquals("a", p.getJSONObject("visibility").getJSONArray("bucket_ids").getString(0))
        assertEquals("buckets", p.getJSONObject("visibility").getString("mode"))
        assertEquals("quoted", p.getJSONObject("quote").getString("post_id"))
        assertEquals("parent", p.getString("reply_to"))
        assertEquals("photo", p.getJSONArray("attachments").getString(0))
    }

    @Test fun foreignMemberCannotBeUsedAsOwnPostAuthor() {
        var rejected = false
        try { PostCompose.payload(model, "mine", "note", "foreign", "**hello**", "", "", "server", "", "", markup = markup) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }

    @Test fun replyToAnotherAccountUsesOwnAuthorAndReadableDefaultAudience() {
        val p = PostCompose.payload(model, "mine", "note", "own", "**hello**", "", "", "server",
            "", "", "foreign-post", markup)
        assertEquals("foreign-post", p.getString("reply_to"))
        assertEquals("own", p.getJSONArray("authors").getString(0))
        assertEquals("server", p.getJSONObject("visibility").getString("mode"))
    }

    @Test fun attachmentOnlyShareCanBecomeAPostButAnEmptyPostCannot() {
        val plain: (String, String) -> String = { body, _ ->
            assertEquals("", body)
            """{"text":"","entities":[]}"""
        }
        val post = PostCompose.payload(model, "mine", "note", "own", "", "", "", "private",
            "", "", markup = plain, attachmentIds = listOf("photo"))
        assertEquals("", post.getString("text"))
        assertEquals("photo", post.getJSONArray("attachments").getString(0))
        var rejected = false
        try { PostCompose.payload(model, "mine", "note", "own", "", "", "", "private", "", "", markup = plain) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }

    @Test fun quotePostLinksTheQuotedPostAndKeepsItsOwnText() {
        val p = PostCompose.payload(model, "mine", "note", "own", "**hello**", "", "", "followers",
            "", "", quotePostId = "quoted", markup = markup)
        assertEquals("quoted", p.getJSONObject("quote").getString("post_id"))
        assertEquals("hello", p.getString("text"))
        assertFalse(p.has("repost_of"))
        assertFalse(PostCompose.payload(model, "mine", "note", "own", "**hello**", "", "", "private", "", "", markup = markup).has("quote"))
    }

    @Test fun repostIsATextlessPrivateNoteByTheActingMember() {
        val p = PostCompose.repostPayload(model, "mine", "own", "original")
        assertEquals("original", p.getString("repost_of"))
        assertEquals("note", p.getString("kind"))
        assertEquals("", p.getString("text"))
        assertEquals(0, p.getJSONArray("entities").length())
        assertEquals("own", p.getJSONArray("authors").getString(0))
        assertEquals("private", p.getJSONObject("visibility").getString("mode"))
        assertTrue(p.isNull("title") && p.isNull("cw") && p.isNull("mood"))
        assertEquals("followers", PostCompose.repostPayload(model, "mine", "own", "original", "followers")
            .getJSONObject("visibility").getString("mode"))
    }

    @Test fun visibilityChangeCarriesOnlyTheNewAudience() {
        val p = PostCompose.visibilityPayload("followers")
        assertEquals(listOf("visibility"), p.keys().asSequence().toList())
        assertEquals("followers", p.getJSONObject("visibility").getString("mode"))
        var rejected = false
        try { PostCompose.visibilityPayload("everyone") } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }

    @Test fun repostRejectsForeignMembersAndBadAudiences() {
        for (bad in listOf<() -> Any>(
            { PostCompose.repostPayload(model, "mine", "foreign", "original") },
            { PostCompose.repostPayload(model, "mine", "own", "original", "everyone") },
            { PostCompose.repostPayload(model, "mine", "own", " ") },
        )) {
            var rejected = false
            try { bad() } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        }
    }
}

