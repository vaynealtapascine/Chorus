package garden.vayne.chorus.data

import org.json.JSONObject

data class ProfileRelation(val name: String, val target: String, val note: String?)
data class ProfileField(val name: String, val value: String)
data class ProfileBundle(val posts: Int, val entries: Int, val notes: Int,
    val relations: List<ProfileRelation>, val highlights: List<SharedPost>,
    val fields: List<ProfileField> = emptyList())

/** Own-account profile details; local member and post rows remain usable without this read. */
object ProfileApi {
    fun parse(j: JSONObject): ProfileBundle {
        val stats = j.getJSONObject("stats")
        val rows = j.getJSONArray("relationships")
        val relations = (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            val kind = row.optJSONObject("type")?.optString("name").orEmpty()
            val target = listOf("to_name", "to_label").firstNotNullOfOrNull { key ->
                row.optString(key).takeIf { row.has(key) && !row.isNull(key) && it.isNotBlank() }
            }.orEmpty()
            val note = row.optString("note").takeIf { row.has("note") && !row.isNull("note") && it.isNotBlank() }
            ProfileRelation(kind, target, note)
        }
        val highlights = j.optJSONArray("highlights")?.let {
            PeopleApi.sharedPosts(JSONObject().put("items", it))
        } ?: emptyList()
        val fieldRows = j.optJSONObject("member")?.optJSONArray("fields")
        val fields = if (fieldRows == null) emptyList() else (0 until fieldRows.length()).mapNotNull { i ->
            fieldRows.optJSONObject(i)?.let { row ->
                val name = row.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: return@let null
                val raw = row.opt("value")
                val value = when (raw) {
                    null, JSONObject.NULL -> null
                    is Boolean -> if (raw) "Yes" else "No"
                    is org.json.JSONArray -> (0 until raw.length()).joinToString(", ") { n -> raw.optString(n) }
                    else -> raw.toString().takeIf { it.isNotBlank() }
                }
                value?.let { ProfileField(name, it) }
            }
        }
        return ProfileBundle(stats.getInt("posts"), stats.getInt("entries"), stats.getInt("notes"),
            relations, highlights, fields)
    }

    suspend fun load(dev: DeviceRecord, memberId: String): ProfileBundle =
        parse(Api.call("GET", dev.base, "/profiles/$memberId", null, dev.session))
}
