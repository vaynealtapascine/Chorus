package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostSearchApiTest {
    @Test fun queryAndCursorUseTheServerAudienceFilteredEndpoint() {
        val path = PostSearchApi.path(LocalSearchQuery(listOf("violet", "garden"), before = 200, after = 100), "next+/=")
        assertTrue(path.startsWith("/search/posts?q=violet+garden&limit=25"))
        assertTrue("before=200" in path)
        assertTrue("after=100" in path)
        assertTrue("cursor=next%2B%2F%3D" in path)
    }

    @Test fun parsedCardsKeepCwAndForeignAuthorsForFiltering() {
        val page = PostSearchApi.parse(JSONObject("""{"items":[{"id":"p","kind":"entry","title":"Title","text":"Hidden body","cw":"Heavy topic","occurred_at":123,"author_cards":[{"id":"kai","name":"Kai","display_name":"Kestrel"}]}],"next_cursor":"next"}"""))
        assertEquals("p", page.items.single().id)
        assertEquals("Heavy topic", page.items.single().cw)
        assertEquals(listOf("kai"), page.items.single().authors)
        assertEquals(listOf("Kestrel"), page.items.single().authorNames)
        assertEquals("next", page.nextCursor)
    }
}
