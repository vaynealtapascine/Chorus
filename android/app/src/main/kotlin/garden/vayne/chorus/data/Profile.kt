package garden.vayne.chorus.data

import org.json.JSONObject

data class ProfileRelation(val name: String, val target: String, val note: String?)
data class ProfileBundle(val posts: Int, val entries: Int, val notes: Int,
    val relations: List<ProfileRelation>, val highlights: List<SharedPost>)

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
        return ProfileBundle(stats.getInt("posts"), stats.getInt("entries"), stats.getInt("notes"),
            relations, highlights)
    }

    suspend fun load(dev: DeviceRecord, memberId: String): ProfileBundle =
        parse(Api.call("GET", dev.base, "/profiles/$memberId", null, dev.session))
}
