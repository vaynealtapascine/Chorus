package garden.vayne.chorus.data

import org.json.JSONObject

/** A post detail is always read through the server's current audience check. */
data class ThreadReply(val id: String, val authorNames: List<String>, val text: String,
    val title: String?, val cw: String?, val occurredAt: Long)

object PostThreads {
    fun parse(j: JSONObject): List<ThreadReply> {
        val items = j.optJSONArray("replies") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i -> items.optJSONObject(i)?.let { item ->
            val cards = item.optJSONArray("author_cards")
            val names = if (cards == null) emptyList() else (0 until cards.length()).mapNotNull { n ->
                cards.optJSONObject(n)?.let { card ->
                    card.optString("display_name").takeIf { card.has("display_name") && !card.isNull("display_name") && it.isNotBlank() }
                        ?: card.optString("name").takeIf { card.has("name") && !card.isNull("name") && it.isNotBlank() }
                }
            }
            ThreadReply(item.getString("id"), names, item.getString("text"),
                item.optString("title").takeIf { item.has("title") && !item.isNull("title") && it.isNotBlank() },
                item.optString("cw").takeIf { item.has("cw") && !item.isNull("cw") && it.isNotBlank() },
                item.getLong("occurred_at"))
        } }
    }

    suspend fun load(dev: DeviceRecord, id: String): List<ThreadReply> =
        parse(Api.call("GET", dev.base, "/posts/$id?depth=1", null, dev.session))

    /** Own-account replies still appear offline and while their op is waiting to sync. */
    fun ownReplies(model: Model, id: String): List<ThreadReply> = model.posts.filter { it.replyTo == id }.map { post ->
        ThreadReply(post.id, post.authors.mapNotNull { model.member(it)?.shownName }, post.text,
            post.title, post.cw, post.occurredAt)
    }

    fun merge(local: List<ThreadReply>, remote: List<ThreadReply>): List<ThreadReply> =
        (remote + local.filter { own -> remote.none { it.id == own.id } })
            .sortedWith(compareBy<ThreadReply> { it.occurredAt }.thenBy { it.id })
}
