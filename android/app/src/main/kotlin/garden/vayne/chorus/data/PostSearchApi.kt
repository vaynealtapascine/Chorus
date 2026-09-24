package garden.vayne.chorus.data

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.json.JSONObject

data class PostSearchPage(val items: List<SearchDocument>, val nextCursor: String?)

/** Post pages already pass the server's audience check, including follow and bucket state. */
object PostSearchApi {
    fun path(query: LocalSearchQuery, cursor: String? = null): String {
        require(query.terms.isNotEmpty())
        val params = ArrayList<Pair<String, String>>()
        params.add("q" to query.terms.joinToString(" "))
        params.add("limit" to "25")
        query.before?.let { params.add("before" to it.toString()) }
        query.after?.let { params.add("after" to it.toString()) }
        cursor?.let { params.add("cursor" to it) }
        val encoded = params.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8.toString())}"
        }
        return "/search/posts?$encoded"
    }

    fun parse(j: JSONObject): PostSearchPage {
        val raw = j.getJSONArray("items")
        val items = PeopleApi.sharedPosts(j).mapIndexed { i, post ->
            val cards = raw.optJSONObject(i)?.optJSONArray("author_cards")
            val authors = if (cards == null) emptyList() else (0 until cards.length()).mapNotNull { n ->
                cards.optJSONObject(n)?.optString("id")?.takeIf { it.isNotBlank() && it != "null" }
            }
            SearchDocument(post.id, "Posts", post.occurredAt, post.text, title = post.title,
                cw = post.cw, authors = authors, authorNames = post.authorNames)
        }
        val cursor = j.optString("next_cursor").takeIf { j.has("next_cursor") && !j.isNull("next_cursor") && it.isNotBlank() }
        return PostSearchPage(items, cursor)
    }

    suspend fun page(dev: DeviceRecord, query: LocalSearchQuery, cursor: String? = null): PostSearchPage =
        parse(Api.call("GET", dev.base, path(query, cursor), null, dev.session))
}
