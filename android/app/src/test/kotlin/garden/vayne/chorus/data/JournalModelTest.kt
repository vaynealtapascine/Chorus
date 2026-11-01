package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class JournalModelTest {
    @Test fun malformedStoredAudienceFailsClosed() {
        for (value in listOf("null", "[]", "42", "{\"mode\":\"unknown\"}", "{\"mode\":true}")) {
            val projection = JSONObject("""{"rows":{"post":{"p":{"exists":true,"fields":{"kind":"note","text":"x","visibility":$value}}}}}""")
            assertEquals(Audience.PRIVATE, Model.parse(projection, "mine").posts.single().audience)
        }
    }

    @Test fun localProjectionKeepsOfflinePostAndDropsTombstone() {
        val p = JSONObject("""{"rows":{"member":{"m":{"exists":true,"fields":{"name":"Kai","created_by_account_id":"mine"}}},"post":{"new":{"exists":true,"fields":{"kind":"entry","authors":["m"],"title":"Day","text":"Hello","occurred_at":120,"cw":"Sensitive","visibility":{"mode":"followers"},"tags":["notes"]}},"old":{"exists":true,"fields":{"kind":"note","text":"Gone","occurred_at":100,"deleted_at":150}}}}}""")
        val model = Model.parse(p, "mine")
        assertEquals(listOf("new"), model.posts.map { it.id })
        assertEquals("followers", model.posts.single().visibility)
        assertEquals("Kai", model.member("m")?.shownName)
        assertFalse(model.members.single().createdByAccountId != "mine")
    }

    @Test fun repostAndQuoteLinksAreReadFromTheProjectionAndTolerateAbsence() {
        val p = JSONObject("""{"rows":{"member":{"m":{"exists":true,"fields":{"name":"Kai"}}},"post":{
            "plain":{"exists":true,"fields":{"kind":"note","authors":["m"],"text":"Hi","occurred_at":10}},
            "boost":{"exists":true,"fields":{"kind":"note","authors":["m"],"text":"","occurred_at":20,"repost_of":"plain"}},
            "quote":{"exists":true,"fields":{"kind":"note","authors":["m"],"text":"So true","occurred_at":30,"quote":{"post_id":"plain"}}},
            "odd":{"exists":true,"fields":{"kind":"note","authors":["m"],"text":"x","occurred_at":40,"quote":"not an object","repost_of":null}}}}}""")
        val posts = Model.parse(p, "mine").posts.associateBy { it.id }
        assertEquals(null, posts.getValue("plain").repostOf)
        assertEquals(null, posts.getValue("plain").quotePostId)
        assertEquals("plain", posts.getValue("boost").repostOf)
        assertEquals("plain", posts.getValue("quote").quotePostId)
        assertEquals(null, posts.getValue("quote").repostOf)
        assertEquals(null, posts.getValue("odd").quotePostId)
        assertEquals(null, posts.getValue("odd").repostOf)
    }

    @Test fun bookmarksReachTheModelThroughAccountPrefs() {
        val p = JSONObject("""{"rows":{"pref":{"mine||bookmark:plain":{"exists":true,"fields":{"value":true}}}}}""")
        assertEquals(setOf("plain"), Model.parse(p, "mine").accountPrefs.bookmarks)
    }
}
