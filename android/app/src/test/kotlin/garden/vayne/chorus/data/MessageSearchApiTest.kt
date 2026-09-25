package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSearchApiTest {
    @Test fun rawRequestKeepsCoreSyntaxAndAllowsFilterOnlySearch() {
        val path = MessageSearchApi.pathRaw("is:pinned from:@Kai has:link", "opaque+/=", 480)
        assertTrue(path.startsWith("/search/messages?q=is%3Apinned+from%3A%40Kai+has%3Alink&limit=25&tz=480"))
        assertTrue("cursor=opaque%2B%2F%3D" in path)
    }

    @Test fun requestKeepsFiltersAndOpaqueCursorBoundToTheQuery() {
        val q = LocalSearchQuery(listOf("garden", "room"), from = "Kai Vale", inChannel = "general",
            has = "image", before = 200, after = 100)
        val path = MessageSearchApi.path(q, "opaque+/=")
        assertTrue(path.startsWith("/search/messages?q=garden+room&limit=25"))
        assertTrue("from=Kai+Vale" in path)
        assertTrue("in=general" in path)
        assertTrue("has=image" in path)
        assertTrue("before=200" in path)
        assertTrue("after=100" in path)
        assertTrue("cursor=opaque%2B%2F%3D" in path)
        assertFalse(path.contains("opaque+/="))
    }

    @Test fun parserOnlyUsesServerReturnedRowsAndDoesNotExpandCw() {
        val page = MessageSearchApi.parse(JSONObject("""{"items":[{"id":"m1","channel_id":"c","occurred_at":123,"text":"Private body","cw":"Heavy topic","authors":["kai"],"visibility":{"mode":"members"}},{"id":"m2","channel_id":"c","occurred_at":124,"text":"Hello","cw":null,"authors":[]}],"next_cursor":"next"}"""))
        assertEquals(listOf("m1", "m2"), page.items.map { it.id })
        assertEquals("Heavy topic", page.items[0].cw)
        assertEquals(listOf("kai"), page.items[0].authors)
        assertEquals(null, page.items[1].cw)
        assertEquals("next", page.nextCursor)
    }
}
