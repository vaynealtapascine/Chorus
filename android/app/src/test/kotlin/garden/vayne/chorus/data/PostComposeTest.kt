package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
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
}
