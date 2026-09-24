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

    @Test fun profileFieldsRenderSimpleValuesAndLocalPinSurvivesOffline() {
        val bundle = ProfileApi.parse(JSONObject("""{"member":{"fields":[{"name":"Comfort food","value":"Soup"},{"name":"Available","value":true},{"name":"Hidden","value":null}]},"stats":{"posts":0,"entries":0,"notes":0},"relationships":[],"highlights":null}"""))
        assertEquals(listOf(ProfileField("Comfort food", "Soup"), ProfileField("Available", "Yes")), bundle.fields)
        val model = Model.parse("""{"rows":{"member":{"m":{"exists":true,"fields":{"name":"Kai","banner_blob":"banner-hash","pinned_post_id":"post"}}},"post":{"post":{"exists":true,"fields":{"kind":"note","authors":["m"],"text":"hello","occurred_at":1}}}}}""", "acct")
        assertEquals("banner-hash", model.member("m")?.bannerBlob)
        assertEquals("post", model.member("m")?.pinnedPostId)
        assertEquals("post", model.posts.single().id)
    }
}
