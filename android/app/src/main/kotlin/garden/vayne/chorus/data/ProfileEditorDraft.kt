package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

/** Immutable opening baseline and all staged values live together in one durable draft. */
data class ProfileEditorDraft(val id: String, val snapshot: String?, val fieldSnapshot: String,
    val values: List<String>, val fields: Map<String, String?>, val bio: RichEditHistory,
    val openingMember: Member? = parseMember(snapshot, id)) {
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
                    .put("banner_blob", m.bannerBlob).put("birthday", m.birthday).toString()
            }
            val fieldSnapshot = JSONArray().also { array ->
                for ((name, type) in ProfileDetails.slots) {
                    val def = model.profileDetails.field(account, name, type)
                    array.put(JSONObject().put("id", def.id).put("name", def.name).put("type", def.type)
                        .put("visibility", def.visibility).put("exists", model.profileDetails.definitions.any { it.id == def.id })
                        .put("value", model.profileDetails.value(member?.id, def)))
                }
            }
            return ProfileEditorDraft(id, snapshot, fieldSnapshot.toString(),
                List(fieldSnapshot.length()) { fieldSnapshot.getJSONObject(it).getString("value") },
                mapOf("name" to member?.name.orEmpty(), "display" to member?.displayName.orEmpty(), "pronouns" to member?.pronouns.orEmpty(),
                    "sigil" to member?.sigils?.firstOrNull().orEmpty(), "color" to (member?.color ?: "#C0694E"),
                    "birthday" to member?.birthday.orEmpty(), "avatar" to member?.avatarBlob, "banner" to member?.bannerBlob),
                RichEditHistory.open(member?.description.orEmpty(), member?.descriptionEntities ?: "[]", MemberCustomization.markup(member)))
        }
    }
}
