package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelDeltaTest {
    @Test
    fun selfMemberIdentifiesPersonAccount() {
        val person = Model.parse("""{"rows":{"member":{"self":{"exists":true,"fields":{"name":"Me","is_self":true}}}}}""", "acct")
        assertEquals(true, person.isPerson)
        val system = Model.parse("""{"rows":{"member":{"a":{"exists":true,"fields":{"name":"Alex"}}}}}""", "acct")
        assertEquals(false, system.isPerson)
    }

    @Test
    fun deltasAddReplaceAndRemove() {
        val p = JSONObject("""{"rows":{"member":{"a":{"exists":true,"fields":{"name":"Kai"}}}},"sets":{},"fronts":{},"opaque":0}""")
        Model.applyDelta(p, JSONObject("""{"rows":{"member":{"b":{"exists":true,"fields":{"name":"June"}},"a":null}},"sets":{"group_membership":{"g|{\"member_id\":\"b\"}":true}},"fronts":{},"reviews":{},"opaque":1,"full":false}"""))
        val m = Model.parse(p, "acct")
        assertEquals(listOf("June"), m.members.map { it.name })
        assertEquals(setOf("b"), m.membership["g"])
        assertEquals(1, p.getInt("opaque"))
        Model.applyDelta(p, JSONObject("""{"rows":{"member":{"b":null}},"sets":{},"fronts":{},"reviews":{},"opaque":1,"full":false}"""))
        assertEquals(false, p.getJSONObject("rows").has("member"))
    }
}
