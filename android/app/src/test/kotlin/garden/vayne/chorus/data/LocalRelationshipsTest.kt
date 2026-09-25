package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalRelationshipsTest {
    @Test fun projectionFiltersDeletedLinksAndUsesInverseLabels() {
        val p = JSONObject("""{"rows":{
            "member":{"kai":{"exists":true,"fields":{"name":"Kai"}},"rin":{"exists":true,"fields":{"name":"Rin"}}},
            "relationship_type":{
                "sibling":{"exists":true,"fields":{"name":"Sibling","is_symmetric":true}},
                "mentor":{"exists":true,"fields":{"name":"Mentor","inverse_name":"Mentee"}},
                "gone":{"exists":true,"fields":{"name":"Old","deleted_at":1}}},
            "relationship":{
                "one":{"exists":true,"fields":{"from_member_id":"kai","to_kind":"member","to_id":"rin","type_id":"mentor","note":"Helpful"}},
                "two":{"exists":true,"fields":{"from_member_id":"rin","to_kind":"member","to_id":"kai","type_id":"sibling"}},
                "three":{"exists":true,"fields":{"from_member_id":"kai","to_kind":"external","to_label":"Sam","type_id":"sibling"}},
                "deleted":{"exists":true,"fields":{"from_member_id":"kai","to_kind":"member","to_id":"rin","type_id":"mentor","deleted_at":5}},
                "broken":{"exists":true,"fields":{"from_member_id":"kai","to_kind":"member","type_id":"mentor"}}
            }}}""")
        val model = Model.parse(p, "acct")
        assertEquals(listOf("mentor", "sibling"), model.relationshipTypes.map { it.id })
        assertEquals(setOf(ProfileLink("one", "Mentor", "Rin", "Helpful"),
            ProfileLink("two", "Sibling", "Rin", null), ProfileLink("three", "Sibling", "Sam", null)),
            LocalRelationships.forMember(model, "kai").toSet())
        assertEquals("Mentee", LocalRelationships.forMember(model, "rin").first().type)
    }

    @Test fun queuedPayloadsStayPrivateAndIdentifyTheTarget() {
        val link = LocalRelationships.linkPayload("kai", "mentor", "rin", null, "Helpful")
        assertEquals("member", link.getString("to_kind"))
        assertEquals("rin", link.getString("to_id"))
        assertEquals("private", link.getJSONObject("visibility").getString("mode"))
        val external = LocalRelationships.linkPayload("kai", "mentor", null, "Sam", null)
        assertEquals("external", external.getString("to_kind"))
        assertEquals("Sam", external.getString("to_label"))
        assertEquals("Mentor", LocalRelationships.typePayload("Mentor", null, true).getString("inverse_name"))
    }
}
