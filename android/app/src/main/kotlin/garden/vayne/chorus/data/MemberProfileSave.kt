package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

data class ProfileSaveOp(val kind: String, val entityId: String?, val payload: JSONObject)

/** Pure save preflight. The entire plan must succeed before the caller emits any operation. */
object MemberProfileSave {
    private const val REOPEN = "A profile field changed. Cancel and reopen before editing it."
    fun plan(id: String, existed: Boolean, patch: JSONObject, fields: JSONArray, values: List<String>, latest: Model, account: String): List<ProfileSaveOp> {
        if (existed) require(latest.member(id)?.let { it.createdByAccountId == null || it.createdByAccountId == account } == true) {
            "This member is no longer available. Cancel and reopen the profile."
        }
        val changed = MemberCustomization.validateDetails(values, List(fields.length()) { fields.getJSONObject(it).getString("value") })
        val details = latest.profileDetails
        val writes = changed.map { i ->
            val opening = fields.getJSONObject(i)
            val fieldId = opening.getString("id")
            val type = opening.getString("type")
            val name = opening.getString("name")
            val current = details.definitions.find { it.id == fieldId }
            require(fieldId !in details.unavailableIds) { REOPEN }
            // Every reused live ID is checked, including definitions first observed after opening.
            require(current == null || (current.type == type && (opening.getBoolean("exists") || current.name.equals(name, true)))) { REOPEN }
            require(!opening.getBoolean("exists") || current != null) { REOPEN }
            val matching = details.definitions.filter { it.type == type && it.name.equals(name, true) }.minByOrNull { it.id }
            val target = current ?: matching
            if (current == null && target != null) {
                // An absent-at-open slot may adopt a concurrent matching definition only if no
                // existing value is rebound/overwritten. Established IDs never change targets.
                require(opening.getString("value").isEmpty() && details.values[id]?.get(fieldId).isNullOrEmpty() &&
                    details.value(id, target).isEmpty()) { REOPEN }
            }
            Triple(i, target?.id ?: fieldId, target == null)
        }
        val ops = mutableListOf<ProfileSaveOp>()
        if (!existed) ops.add(ProfileSaveOp("member.create", id, patch))
        else if (patch.length() > 0) ops.add(ProfileSaveOp("member.set", id, patch))
        for ((i, fieldId, define) in writes) {
            val opening = fields.getJSONObject(i)
            if (define) ops.add(ProfileSaveOp("field.define", fieldId, JSONObject().put("name", opening.getString("name"))
                .put("type", opening.getString("type")).put("default_visibility", JSONObject().put("mode", "private"))))
            ops.add(ProfileSaveOp("field.set_value", null, JSONObject().put("member_id", id).put("field_id", fieldId)
                .put("value", values[i].trim().ifEmpty { null } ?: JSONObject.NULL)))
        }
        return ops
    }
}
