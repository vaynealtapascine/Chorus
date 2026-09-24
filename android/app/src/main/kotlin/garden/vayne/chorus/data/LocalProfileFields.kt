package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

/** Own-account custom fields from the replica, available before a profile API read. */
object LocalProfileFields {
    fun fromProjection(p: JSONObject): Map<String, List<ProfileField>> {
        val defs = p.optJSONObject("rows")?.optJSONObject("field_def") ?: return emptyMap()
        val values = p.optJSONObject("rows")?.optJSONObject("field_value") ?: return emptyMap()
        val ordered = defs.keys().asSequence().mapNotNull { id ->
            val row = defs.optJSONObject(id)?.takeIf { it.optBoolean("exists") }?.optJSONObject("fields")
                ?: return@mapNotNull null
            if (row.has("deleted_at") && !row.isNull("deleted_at")) return@mapNotNull null
            val name = row.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Triple(id, name, row.optString("sort_key").takeIf { it.isNotBlank() } ?: name)
        }.sortedWith(compareBy<Triple<String, String, String>> { it.third }.thenBy { it.first }).toList()
        val byField = HashMap<String, MutableList<Pair<String, Any>>>()
        for (key in values.keys()) {
            val bar = key.lastIndexOf('|')
            if (bar <= 0 || bar == key.lastIndex) continue
            val row = values.optJSONObject(key)?.takeIf { it.optBoolean("exists") }?.optJSONObject("fields")
                ?: continue
            val raw = row.opt("value") ?: continue
            byField.getOrPut(key.substring(bar + 1)) { ArrayList() }.add(key.substring(0, bar) to raw)
        }
        val byMember = HashMap<String, MutableList<ProfileField>>()
        for ((fieldId, name) in ordered) {
            for ((memberId, raw) in byField[fieldId].orEmpty()) {
                val value = display(raw) ?: continue
                byMember.getOrPut(memberId) { ArrayList() }.add(ProfileField(name, value))
            }
        }
        return byMember
    }

    private fun display(raw: Any?): String? = when (raw) {
        null, JSONObject.NULL -> null
        is Boolean -> if (raw) "Yes" else "No"
        is JSONArray -> (0 until raw.length()).mapNotNull { display(raw.opt(it)) }.joinToString(", ")
            .takeIf { it.isNotBlank() }
        else -> raw.toString().takeIf { it.isNotBlank() }
    }
}
