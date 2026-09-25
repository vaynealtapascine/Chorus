package garden.vayne.chorus.data

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.json.JSONObject

data class MessageSearchPage(val items: List<SearchDocument>, val nextCursor: String?)

/** Server-filtered history for matches outside the local replica's current window. */
object MessageSearchApi {
    fun path(query: LocalSearchQuery, cursor: String? = null): String {
        require(query.terms.isNotEmpty())
        val params = ArrayList<Pair<String, String>>()
        params.add("q" to query.terms.joinToString(" "))
        params.add("limit" to "25")
        query.inChannel?.let { params.add("in" to it) }
        query.from?.let { params.add("from" to it) }
        query.has?.let { params.add("has" to it) }
        query.before?.let { params.add("before" to it.toString()) }
        query.after?.let { params.add("after" to it.toString()) }
        cursor?.let { params.add("cursor" to it) }
        val encoded = params.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8.toString())}"
        }
        return "/search/messages?$encoded"
    }

    fun parse(j: JSONObject): MessageSearchPage {
        val items = j.getJSONArray("items")
        val rows = (0 until items.length()).mapNotNull { i ->
            val row = items.optJSONObject(i) ?: return@mapNotNull null
            val id = row.optString("id").takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
            val authors = row.optJSONArray("authors")?.let { a -> (0 until a.length()).mapNotNull { n ->
                a.optString(n).takeIf { it.isNotBlank() && it != "null" }
            } } ?: emptyList()
            SearchDocument(id, "Messages", row.optLong("occurred_at"), row.optString("text"),
                cw = row.optString("cw").takeIf { row.has("cw") && !row.isNull("cw") && it.isNotBlank() },
                authors = authors, channelId = row.optString("channel_id").takeIf { it.isNotBlank() })
        }
        val cursor = j.optString("next_cursor").takeIf { j.has("next_cursor") && !j.isNull("next_cursor") && it.isNotBlank() }
        return MessageSearchPage(rows, cursor)
    }

    suspend fun page(dev: DeviceRecord, query: LocalSearchQuery, cursor: String? = null): MessageSearchPage =
        parse(Api.call("GET", dev.base, path(query, cursor), null, dev.session))
}
