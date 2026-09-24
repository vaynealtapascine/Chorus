package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedsTest {
    @Test fun definitionUsesTheCoreAstAndMakesSharingExplicit() {
        val payload = Feeds.definition(" Friends ", "", " kind:entry ", "followers") { query ->
            assertEquals("kind:entry", query)
            """{"op":"kind","kind":"entry"}"""
        }
        assertEquals("Friends", payload.getString("name"))
        assertEquals("kind:entry", payload.getString("query"))
        assertEquals("entry", payload.getJSONObject("query_ast").getString("kind"))
        assertEquals("followers", payload.getJSONObject("visibility").getString("mode"))
        assertTrue(Feeds.usesFronting("kind:note (fronting:kai or tag:day)"))
        assertFalse(Feeds.usesFronting("fronting is a word here"))
    }

    @Test fun sharedFeedAndReadablePageParseWithoutForeignReplicaRows() {
        val feeds = Feeds.parseList(JSONObject("""{"items":[{"id":"f","name":"Friends","description":null,"query":"kind:note","owner":{"handle":"alex","display_name":null},"shared":true}]}"""))
        assertEquals("@alex", feeds.single().ownerName)
        assertTrue(feeds.single().shared)
        val page = Feeds.parsePage(JSONObject("""{"items":[{"id":"p","kind":"note","title":null,"text":"hello","cw":null,"occurred_at":123,"author_cards":[]}],"next_cursor":"abc"}"""))
        assertEquals(listOf("p"), page.posts.map { it.id })
        assertEquals("abc", page.nextCursor)
    }
}
