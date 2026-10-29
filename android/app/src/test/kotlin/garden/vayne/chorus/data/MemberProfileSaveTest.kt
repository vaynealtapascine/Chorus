package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MemberProfileSaveTest {
    private val member = Member("m", "Kai", null, null, "#123456", emptyList(), null, false)
    private fun model(details: ProfileDetails, members: List<Member> = listOf(member)) = Model(members, emptyList(), emptyMap(), emptyList(), null, emptyList(), profileDetails = details)
    private fun opening(details: ProfileDetails = ProfileDetails()) = JSONArray().also { a ->
        for ((name, type) in ProfileDetails.slots) {
            val field = details.field("mine", name, type)
            a.put(JSONObject().put("id", field.id).put("name", field.name).put("type", field.type)
                .put("exists", details.definitions.any { it.id == field.id }).put("value", details.value("m", field)))
        }
    }
    private val url = "https://example.org"
    private fun plan(fields: JSONArray, details: ProfileDetails, values: List<String> = listOf("", url, "", "")) =
        MemberProfileSave.plan("m", true, JSONObject().put("name", "Edited"), fields, values, model(details), "mine")
    private fun website(fields: JSONArray) = fields.getJSONObject(1).getString("id")
    private fun rejects(fields: JSONArray, details: ProfileDetails, values: List<String> = listOf("", url, "", "")) {
        val emitted = mutableListOf<ProfileSaveOp>()
        val error = runCatching { emitted.addAll(plan(fields, details, values)) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("reopen"))
        assertTrue(emitted.isEmpty())
    }
    @Test fun absentAtOpenCompatibleNowReusesIdWithoutChangingMetadataOrPrivacy() {
        val fields = opening()
        val current = ProfileDetail(website(fields), "Website", "url", "buckets")
        val ops = plan(fields, ProfileDetails(listOf(current)))
        assertEquals(listOf("member.set", "field.set_value"), ops.map { it.kind })
        assertEquals(current.id, ops.last().payload.getString("field_id"))
        assertFalse(ops.any { it.kind == "field.define" || it.kind == "field.set_def" })
        assertEquals(setOf("name"), ops.first().payload.keys().asSequence().toSet())
    }
    @Test fun absentAtOpenIncompatibleNowPreflightsEverySlotBeforeAnyMemberOrFieldOps() {
        val fields = opening()
        for (i in ProfileDetails.slots.indices) {
            val def = fields.getJSONObject(i)
            val details = ProfileDetails(listOf(ProfileDetail(def.getString("id"), def.getString("name"), "number", "public")))
            val values = List(4) { if (it == 0) "Garden" else url }
            rejects(fields, details, values)
        }
        rejects(fields, ProfileDetails(listOf(ProfileDetail(website(fields), "Unrelated URL", "url", "public"))))
    }
    @Test fun deletedAndDeletedThenRedefinedDefinitionsCannotBeRecreatedOrWritten() {
        val initial = ProfileDetails(listOf(ProfileDetail("existing", "Website", "url", "public")))
        val fields = opening(initial)
        rejects(fields, ProfileDetails())
        // Projection leaves a tombstone even if field.define is received again for the same ID.
        val p = JSONObject("""{"rows":{"field_def":{"existing":{"exists":true,"fields":{"name":"Website","type":"url","deleted_at":42}}}}}""")
        val deleted = ProfileDetails.parse(p)
        assertTrue("existing" in deleted.unavailableIds)
        rejects(fields, deleted)
        val absent = opening()
        val tombstone = ProfileDetails(unavailableIds = setOf(website(absent)))
        rejects(absent, tombstone)
        rejects(fields, ProfileDetails(listOf(ProfileDetail("existing", "Website", "number", "private"))))
    }
    @Test fun simultaneousMatchingDefinitionsAtOtherIdsReuseDeterministicallyWithoutDuplication() {
        val fields = opening()
        val a = ProfileDetail("a", "WEBSITE", "url", "public")
        val z = ProfileDetail("z", "Website", "url", "private")
        for (defs in listOf(listOf(a, z), listOf(z, a))) {
            val ops = plan(fields, ProfileDetails(defs))
            assertEquals(listOf("member.set", "field.set_value"), ops.map { it.kind })
            assertEquals("a", ops.last().payload.getString("field_id"))
        }
        // Already bound IDs remain bound even when a lower-ID matching field appears.
        val established = opening(ProfileDetails(listOf(z), mapOf("m" to mapOf("z" to "https://old.example"))))
        val ops = plan(established, ProfileDetails(listOf(a, z)))
        assertEquals("z", ops.last().payload.getString("field_id"))
    }
    @Test fun adoptionNeverRebindsOrOverwritesExistingValues() {
        val fields = opening()
        val a = ProfileDetail("a", "Website", "url", "public")
        rejects(fields, ProfileDetails(listOf(a), mapOf("m" to mapOf("a" to "https://other.example"))))
        // An out-of-order value can arrive at the reserved ID after opening, before its definition.
        rejects(fields, ProfileDetails(listOf(a), mapOf("m" to mapOf(website(fields) to "https://concurrent.example"))))
        fields.getJSONObject(1).put("value", "orphan legacy URL")
        rejects(fields, ProfileDetails(listOf(a)))
    }
    @Test fun newDefinitionsStayPrivateAndEstablishedMetadataChangesStayUntouched() {
        val fields = opening()
        val ops = plan(fields, ProfileDetails())
        assertEquals(listOf("member.set", "field.define", "field.set_value"), ops.map { it.kind })
        assertEquals("private", ops[1].payload.getJSONObject("default_visibility").getString("mode"))
        val original = ProfileDetails(listOf(ProfileDetail("f", "Website", "url", "private")))
        val renamed = ProfileDetails(listOf(ProfileDetail("f", "My site", "url", "public")))
        val reused = plan(opening(original), renamed)
        assertEquals(listOf("member.set", "field.set_value"), reused.map { it.kind })
        assertEquals("f", reused.last().payload.getString("field_id"))
    }
    @Test fun untouchedInvalidLegacyFieldsRemainUnvalidatedAndUnwritten() {
        val details = ProfileDetails(listOf(ProfileDetail("f", "Website", "url", "public")), mapOf("m" to mapOf("f" to "not a URL")))
        val fields = opening(details)
        val ops = plan(fields, details, listOf("", "not a URL", "", ""))
        assertEquals(listOf("member.set"), ops.map { it.kind })
        assertEquals("not a URL", details.values["m"]!!["f"])
        // Removing a different field in sync does not force writes/validation of untouched values.
        assertEquals(listOf("member.set"), plan(fields, ProfileDetails(), listOf("", "not a URL", "", "")).map { it.kind })
    }
    @Test fun staleForeignAndNewMemberPlansStillPreflightBeforeOperations() {
        val fields = opening()
        for (latest in listOf(Model.Empty, model(ProfileDetails(), listOf(member.copy(createdByAccountId = "foreign")))))
            assertTrue(runCatching { MemberProfileSave.plan("m", true, JSONObject(), fields, listOf("", url, "", ""), latest, "mine") }.isFailure)
        val wrong = ProfileDetails(listOf(ProfileDetail(website(fields), "Website", "number", "private")))
        assertTrue(runCatching { MemberProfileSave.plan("m", false, JSONObject().put("name", "New"), fields, listOf("", url, "", ""), model(wrong), "mine") }.isFailure)
    }
}
