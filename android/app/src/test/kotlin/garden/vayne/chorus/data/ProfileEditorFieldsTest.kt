package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** D-101: the editor shows every custom field and an audience per part. */
class ProfileEditorFieldsTest {
    private val kai = Member("m", "Kai", null, null, "#123456", emptyList(), null, false,
        visibility = """{"mode":"followers"}""", fieldVisibility = """{"pronouns":{"mode":"private"}}""")

    private fun projection(): JSONObject = JSONObject("""{"rows":{
        "member":{"m":{"exists":true,"fields":{"name":"Kai","color":"#123456","visibility":{"mode":"followers"},"field_visibility":{"pronouns":{"mode":"private"}}}}},
        "field_def":{
          "roles":{"exists":true,"fields":{"name":"Roles","type":"multi_select","options":{"choices":["Host","Protector"]},"default_visibility":{"mode":"followers"},"sort_key":"b"}},
          "age":{"exists":true,"fields":{"name":"Age","type":"number","sort_key":"a"}},
          "ref":{"exists":true,"fields":{"name":"Partner","type":"member_ref"}},
          "gone":{"exists":true,"fields":{"name":"Gone","type":"text","deleted_at":5}}},
        "field_value":{
          "m|roles":{"exists":true,"fields":{"value":["Host"]}},
          "m|age":{"exists":true,"fields":{"value":27}}}}}""")

    @Test fun membersCarryTheirAudiences() {
        val m = Model.parse(projection(), "acc").member("m")!!
        assertEquals("followers", ProfileAudience.of(ProfileAudience.parse(m.visibility)).mode)
        assertEquals("private", ProfileAudience.own(ProfileAudience.parse(m.fieldVisibility), "pronouns")!!.mode)
    }

    @Test fun theDraftHoldsEveryEditableFieldAfterTheSlots() {
        val model = Model.parse(projection(), "acc")
        val d = ProfileEditorDraft.open("m", model.member("m"), model, "acc")
        val rows = JSONArray(d.fieldSnapshot)
        val names = List(rows.length()) { rows.getJSONObject(it).getString("name") }
        // slots first, then fields in their sort order; member_ref and deleted ones are left out
        assertEquals(listOf("Location", "Website", "Website 2", "Website 3", "Age", "Roles"), names)
        assertEquals(listOf("", "", "", "", "27", """["Host"]"""), d.values)
        assertEquals(listOf("Host", "Protector"), rows.getJSONObject(5).getJSONArray("choices").let { a -> List(a.length()) { a.getString(it) } })
        assertEquals("followers", rows.getJSONObject(5).getJSONObject("default_visibility").getString("mode"))
        assertEquals("""{"pronouns":{"mode":"private"}}""", d.fields["field_visibility"])
        // round trip, and a field defined mid-draft joins as an empty row once
        val back = ProfileEditorDraft.decode(d.encode())
        assertEquals(d.fieldSnapshot, back.fieldSnapshot)
        // (the draft has always dropped null entries on the way through)
        assertEquals(d.fields.filterValues { it != null }, back.fields)
        val added = back.withField(ProfileDetail("food", "Favourite food", "text", "private"))
        assertEquals(7, JSONArray(added.fieldSnapshot).length())
        assertEquals(7, added.values.size)
        assertSame(added, added.withField(ProfileDetail("food", "Favourite food", "text", "private")))
    }

    @Test fun valuesAreCheckedAndStoredByType() {
        val rows = listOf("Age" to "number", "Roles" to "multi_select", "Drives" to "boolean", "Colour" to "color", "Since" to "date", "Notes" to "text")
        val empty = List(rows.size) { "" }
        assertEquals(listOf(0, 1, 2, 3, 4, 5), MemberCustomization.validateValues(rows, listOf("27", """["Host"]""", "true", "#AABBCC", "2020-02-29", "hi"), empty))
        for ((i, bad) in listOf(0 to "lots", 1 to "Host", 2 to "maybe", 3 to "pink", 4 to "Feb 2020", 5 to "x".repeat(501))) {
            val values = empty.toMutableList().apply { set(i, bad) }
            assertTrue(bad, runCatching { MemberCustomization.validateValues(rows, values, empty) }.isFailure)
        }
        assertEquals(27L, MemberCustomization.jsonValue("number", " 27 "))
        assertEquals(2.5, MemberCustomization.jsonValue("number", "2.5"))
        assertEquals(true, MemberCustomization.jsonValue("boolean", "true"))
        assertEquals("""["Host"]""", MemberCustomization.jsonValue("multi_select", """["Host"]""").toString())
        assertEquals(JSONObject.NULL, MemberCustomization.jsonValue("multi_select", "[]"))
        assertEquals(JSONObject.NULL, MemberCustomization.jsonValue("text", "  "))
    }

    @Test fun saveWritesChoiceValuesAndOnlyChangedAudiences() {
        val model = Model.parse(projection(), "acc")
        val d = ProfileEditorDraft.open("m", model.member("m"), model, "acc")
        val values = d.values.toMutableList().apply { set(5, """["Host","Protector"]""") }
        val ops = MemberProfileSave.plan("m", true, JSONObject(), JSONArray(d.fieldSnapshot), values, model, "acc")
        assertEquals(listOf("field.set_value"), ops.map { it.kind })
        assertEquals("""["Host","Protector"]""", ops[0].payload.get("value").toString())

        fun patch(visibility: String?, parts: String?) = MemberCustomization.patch(kai, "Kai", "", "", "", "#123456", "", "", null, null, "",
            visibility = visibility, fieldVisibility = parts)
        assertEquals(0, patch(kai.visibility, kai.fieldVisibility).length())
        assertEquals(0, patch(null, null).length(), "an old draft without audiences changes nothing")
        val p = patch("""{"mode":"buckets","bucket_ids":["b"]}""", """{"pronouns":{"mode":"followers"}}""")
        assertEquals(setOf("visibility", "field_visibility"), p.keys().asSequence().toSet())
        assertEquals("b", p.getJSONObject("visibility").getJSONArray("bucket_ids").getString(0))
    }

    private fun assertEquals(expected: Int, actual: Int, message: String) = assertEquals(message, expected.toLong(), actual.toLong())
}
