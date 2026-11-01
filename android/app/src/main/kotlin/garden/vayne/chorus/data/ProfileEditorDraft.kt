package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

/** Immutable opening baseline and all staged values live together in one durable draft. */
data class ProfileEditorDraft(val id: String, val snapshot: String?, val fieldSnapshot: String,
    val values: List<String>, val fields: Map<String, String?>, val bio: RichEditHistory,
    val openingMember: Member? = parseMember(snapshot, id)) {
    /** A field defined while this draft is open (D-101), added as an empty row. */
    fun withField(def: ProfileDetail): ProfileEditorDraft {
        val rows = JSONArray(fieldSnapshot)
        for (i in 0 until rows.length()) if (rows.getJSONObject(i).getString("id") == def.id) return this
        rows.put(JSONObject().put("id", def.id).put("name", def.name).put("type", def.type).put("visibility", def.visibility)
            .put("exists", true).put("value", "").put("default_visibility", JSONObject(def.defaultVisibility)).put("choices", JSONArray(def.choices)))
        return copy(fieldSnapshot = rows.toString(), values = values + "")
    }

    fun encode(): String = JSONObject().put("id", id).put("snapshot", snapshot ?: JSONObject.NULL)
        .put("fieldSnapshot", fieldSnapshot).put("values", JSONArray(values)).put("fields", JSONObject(fields))
        .put("bio", bio.json()).toString()
    companion object {
        private fun parseMember(snapshot: String?, id: String): Member? = snapshot?.let {
            Model.parse(JSONObject().put("rows", JSONObject().put("member", JSONObject().put(id,
                JSONObject().put("exists", true).put("fields", JSONObject(it))))), "").member(id)
        }

        fun decode(text: String): ProfileEditorDraft = JSONObject(text).let { j ->
            val fields = j.getJSONObject("fields")
            ProfileEditorDraft(j.getString("id"), if (j.isNull("snapshot")) null else j.getString("snapshot"),
                j.getString("fieldSnapshot"), j.getJSONArray("values").let { a -> List(a.length()) { a.getString(it) } },
                fields.keys().asSequence().associateWith { if (fields.isNull(it)) null else fields.getString(it) },
                RichEditHistory.decode(j.getJSONObject("bio")))
        }
        fun open(id: String, member: Member?, model: Model, account: String): ProfileEditorDraft {
            val snapshot = member?.let { m ->
                JSONObject().put("name", m.name).put("display_name", m.displayName).put("pronouns", m.pronouns)
                    .put("color", m.color).put("sigils", JSONArray(m.sigils)).put("description", m.description)
                    .put("description_entities", JSONArray(m.descriptionEntities)).put("avatar_blob", m.avatarBlob)
                    .put("banner_blob", m.bannerBlob).put("birthday", m.birthday)
                    .put("visibility", ProfileAudience.parse(m.visibility) ?: JSONObject.NULL)
                    .put("field_visibility", ProfileAudience.parse(m.fieldVisibility) ?: JSONObject.NULL).toString()
            }
            val details = model.profileDetails
            fun row(def: ProfileDetail) = JSONObject().put("id", def.id).put("name", def.name).put("type", def.type)
                .put("visibility", def.visibility).put("exists", details.definitions.any { it.id == def.id })
                .put("value", details.value(member?.id, def)).put("default_visibility", JSONObject(def.defaultVisibility))
                .put("choices", JSONArray(def.choices))
            // Location and the websites first (D-090), then every other field the editor can show (D-101)
            val fieldSnapshot = JSONArray().also { array ->
                val slots = ProfileDetails.slots.map { (name, type) -> details.field(account, name, type) }
                for (def in slots) array.put(row(def))
                for (def in details.definitions) if (def.type in ProfileDetails.EDITABLE && slots.none { it.id == def.id }) array.put(row(def))
            }
            return ProfileEditorDraft(id, snapshot, fieldSnapshot.toString(),
                List(fieldSnapshot.length()) { fieldSnapshot.getJSONObject(it).getString("value") },
                mapOf("name" to member?.name.orEmpty(), "display" to member?.displayName.orEmpty(), "pronouns" to member?.pronouns.orEmpty(),
                    "sigil" to member?.sigils?.firstOrNull().orEmpty(), "color" to (member?.color ?: "#C0694E"),
                    "birthday" to member?.birthday.orEmpty(), "avatar" to member?.avatarBlob, "banner" to member?.bannerBlob,
                    "visibility" to member?.visibility, "field_visibility" to member?.fieldVisibility),
                RichEditHistory.open(member?.description.orEmpty(), member?.descriptionEntities ?: "[]", MemberCustomization.markup(member)))
        }
    }
}
