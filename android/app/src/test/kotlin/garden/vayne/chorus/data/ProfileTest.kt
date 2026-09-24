package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileTest {
    @Test fun profileUsesReadableHighlightsAndNullableRelationshipNames() {
        val bundle = ProfileApi.parse(JSONObject("""{"stats":{"posts":3,"entries":1,"notes":2},"relationships":[{"to_name":null,"to_label":"the stars","note":null,"type":{"name":"friend"}}],"highlights":[{"id":"p","kind":"note","title":null,"text":"hello","cw":null,"occurred_at":10,"author_cards":[]}]}"""))
        assertEquals(3, bundle.posts)
        assertEquals(1, bundle.entries)
        assertEquals("the stars", bundle.relations.single().target)
        assertEquals(null, bundle.relations.single().note)
        assertEquals("p", bundle.highlights.single().id)
    }

    @Test fun profileWithoutPostScopeHasNoHighlights() {
        val bundle = ProfileApi.parse(JSONObject("""{"stats":{"posts":0,"entries":0,"notes":0},"relationships":[],"highlights":null}"""))
        assertEquals(emptyList<SharedPost>(), bundle.highlights)
    }
}
