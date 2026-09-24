package garden.vayne.chorus.data

import java.net.URLEncoder
import org.json.JSONObject
import uniffi.chorus_ffi.feedParse

data class FeedInfo(val id: String, val name: String, val description: String?, val query: String,
    val ownerName: String, val shared: Boolean)
data class FeedPage(val posts: List<SharedPost>, val nextCursor: String?)

object Feeds {
    fun definition(name: String, description: String, query: String, audience: String,
        parse: (String) -> String = ::feedParse): JSONObject {
        require(name.isNotBlank()) { "Name this feed first." }
        require(query.isNotBlank()) { "Write a filter first." }
        require(audience in setOf("private", "followers"))
        val trimmed = query.trim()
        return JSONObject().put("name", name.trim()).put("description", description.trim().ifBlank { null } ?: JSONObject.NULL)
            .put("query", trimmed).put("query_ast", JSONObject(parse(trimmed)))
            .put("visibility", JSONObject().put("mode", audience))
    }

    fun usesFronting(query: String): Boolean = Regex("(^|[\\s(-])fronting:", RegexOption.IGNORE_CASE).containsMatchIn(query)

    fun parseList(j: JSONObject): List<FeedInfo> {
        val items = j.getJSONArray("items")
        return (0 until items.length()).mapNotNull { i -> items.optJSONObject(i)?.let { row ->
            val owner = row.optJSONObject("owner")
            val display = owner?.optString("display_name")?.takeIf { owner.has("display_name") && !owner.isNull("display_name") && it.isNotBlank() }
            val handle = owner?.optString("handle")?.takeIf { owner.has("handle") && !owner.isNull("handle") && it.isNotBlank() }
            FeedInfo(row.getString("id"), row.optString("name").takeIf { row.has("name") && !row.isNull("name") && it.isNotBlank() } ?: "Untitled",
                row.optString("description").takeIf { row.has("description") && !row.isNull("description") && it.isNotBlank() },
                row.optString("query"), display ?: handle?.let { "@$it" } ?: "Someone", row.optBoolean("shared"))
        } }
    }

    fun parsePage(j: JSONObject): FeedPage = FeedPage(PeopleApi.sharedPosts(j),
        j.optString("next_cursor").takeIf { j.has("next_cursor") && !j.isNull("next_cursor") && it.isNotBlank() })

    suspend fun list(dev: DeviceRecord): List<FeedInfo> =
        parseList(Api.call("GET", dev.base, "/feeds", null, dev.session))

    suspend fun items(dev: DeviceRecord, id: String, cursor: String? = null): FeedPage {
        val encoded = cursor?.let { "&cursor=${URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
        return parsePage(Api.call("GET", dev.base, "/feeds/$id/items?limit=20$encoded", null, dev.session))
    }
}
