package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelDeltaTest {
    @Test fun namedBodyFromActualCoreOverridesLegacyCurrentAndSurvivesDeltaRemoval() {
        val account = "0192f8c2-0000-7000-8000-0000000000a1"
        val source = "0192f8c2-0000-7000-8000-0000000000a2"
        val member = "0192f8c2-0000-7000-8000-0000000000b1"
        val operations = org.json.JSONArray()
        fun op(n: Int, kind: String, entity: String, payload: String) {
            operations.put(JSONObject().put("id", "0192f8c2-0000-7000-8000-${n.toString().padStart(12,'0')}")
                .put("scope", "account:$account").put("kind", kind).put("entity_id", entity)
                .put("hlc", "${(1000+n).toString(16).padStart(12,'0')}-0000-00000001")
                .put("device_at", 1000+n).put("payload", JSONObject(payload)))
        }
        op(1,"member.create",member,"""{"name":"Kai"}""")
        op(2,"front.switch",account,"""{"entries":[]}""")
        op(3,"front.change",source,"""{"action":"switch","entries":[{"subject_type":"member","subject_id":"$member","layer_id":"quiet"}]}""")
        op(4,"front.define",account,"""{"follow_source":"$source","purpose_layers":{"reading":["quiet"]}}""")
        val p = JSONObject(uniffi.chorus_ffi.project(operations.toString()))
        val model = Model.parse(p,account)
        assertEquals(listOf(member),model.current.map { it.subjectId })
        assertEquals("quiet",model.current.single().level)
        assertEquals("Kai",model.frontLabel())
        assertEquals(null,model.since)
        assertEquals("following",model.namedFronts!!.resolved.getValue(account).state)
        assertEquals(listOf(member),model.namedFronts.resolved.getValue(account).purposeCandidates.getValue("reading").map { it.subjectId })
        Model.applyDelta(p,JSONObject("""{"rows":{},"sets":{},"fronts":{},"reviews":{},"front_systems":{"$account":null},"opaque":0,"full":false}"""))
        assertEquals(false,p.has("front_systems"))
        assertEquals(emptyList<Entry>(),Model.parse(p,account).current)
    }
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
