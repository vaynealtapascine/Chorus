package garden.vayne.chorus.data

import org.json.JSONObject

/** A post detail is always read through the server's current audience check. */
data class ThreadReply(val id: String, val authorNames: List<String>, val text: String,
    val title: String?, val cw: String?, val occurredAt: Long, val poll: Poll? = null, val pollTally: PollTally? = null,
    val entities: String = "[]",
    /** Replies to this one the reader may see (`reply_count`), for "N replies" and opening it as a thread. */
    val replyCount: Int = 0,
    /** The first author's look, from their author card. */
    val color: String? = null, val avatarBlob: String? = null,
    /** Member ids, so a reply by one of ours shows that member's own avatar. */
    val authorIds: List<String> = emptyList(),
    /** The post this one quotes, and what the server sent of it when this reader may read it. */
    val quotePostId: String? = null, val quoted: ThreadReply? = null)
/** A post as a thread view shows it: what it answers ([ancestors], oldest first), its replies,
 * the posts quoting it and its reactions. */
data class PostDetail(val replies: List<ThreadReply>, val reactions: List<PostReaction>, val root: ThreadReply? = null,
    val ancestors: List<ThreadReply> = emptyList(), val quotes: List<ThreadReply> = emptyList())

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
            val first = cards?.optJSONObject(0)
            fun JSONObject.text(k: String) = optString(k).takeIf { has(k) && !isNull(k) && it.isNotBlank() }
            ThreadReply(item.getString("id"), names, item.getString("text"),
                item.optString("title").takeIf { item.has("title") && !item.isNull("title") && it.isNotBlank() },
                item.optString("cw").takeIf { item.has("cw") && !item.isNull("cw") && it.isNotBlank() },
                item.getLong("occurred_at"), Polls.parse(item.optJSONObject("poll")), Polls.tally(item.optJSONObject("poll_tally")),
                item.optJSONArray("entities")?.toString() ?: "[]", item.optInt("reply_count", 0),
                first?.text("color"), first?.text("avatar_blob"),
                item.optJSONArray("authors")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) } }.orEmpty(),
                item.optJSONObject("quote")?.text("post_id"),
                item.optJSONObject("quoted")?.let { parse(JSONObject().put("replies", org.json.JSONArray().put(it))).firstOrNull() })
        } }
    }

    fun parseDetail(j: JSONObject): PostDetail = PostDetail(parse(j), PostReactions.fromServer(j),
        if (j.has("id") && j.has("text")) parse(JSONObject().put("replies", org.json.JSONArray().put(j))).firstOrNull() else null,
        j.optJSONArray("ancestors")?.let { parse(JSONObject().put("replies", it)) }.orEmpty(),
        j.optJSONArray("quotes")?.let { parse(JSONObject().put("replies", it)) }.orEmpty())

    suspend fun load(dev: DeviceRecord, id: String): PostDetail =
        parseDetail(Api.call("GET", dev.base, "/posts/$id?depth=1", null, dev.session))

    /** Own-account replies still appear offline and while their op is waiting to sync. */
    fun ownReplies(model: Model, id: String): List<ThreadReply> = model.posts.filter { it.replyTo == id }.map { local(model, it) }

    /** An own post as a thread shows it (offline, or while its op waits to sync). */
    fun local(model: Model, post: JournalPost): ThreadReply {
        val first = post.authors.firstOrNull()?.let(model::member)
        return ThreadReply(post.id, post.authors.mapNotNull { model.member(it)?.shownName }, post.text,
            post.title, post.cw, post.occurredAt, model.polls.polls[post.id], model.polls.tallies[post.id], post.entities,
            model.posts.count { it.replyTo == post.id }, first?.color, first?.avatarBlob, post.authors, post.quotePostId)
    }

    /** Own posts quoting [id] (offline, or waiting to sync); reposts aren't quotes. */
    fun ownQuotes(model: Model, id: String): List<ThreadReply> =
        model.posts.filter { it.quotePostId == id && it.repostOf == null }.map { local(model, it) }

    /** What a post answers, oldest first, from own posts on this device (the server's chain when online). */
    fun localAncestors(model: Model, id: String): List<ThreadReply> {
        val byId = model.posts.associateBy { it.id }
        val chain = ArrayList<ThreadReply>()
        // a looping chain never shows the focused post above itself
        val seen = hashSetOf(id)
        var next = byId[id]?.replyTo
        while (next != null && chain.size < 20 && seen.add(next)) {
            val parent = byId[next] ?: break
            chain.add(local(model, parent))
            next = parent.replyTo
        }
        return chain.asReversed()
    }

    fun merge(local: List<ThreadReply>, remote: List<ThreadReply>): List<ThreadReply> =
        (remote.map { r -> local.find { it.id == r.id }?.let { r.copy(replyCount = maxOf(r.replyCount, it.replyCount)) } ?: r } +
            local.filter { own -> remote.none { it.id == own.id } })
            .sortedWith(compareBy<ThreadReply> { it.occurredAt }.thenBy { it.id })
}
