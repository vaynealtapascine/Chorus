package garden.vayne.chorus.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** Our post that something happened to, as `GET /posts/activity` names it (a short excerpt). */
data class ActivityTarget(val id: String, val kind: String?, val title: String?, val text: String, val cw: String?)

/** What another account did with one of our posts (D-127): `reply` · `quote` · `repost` carry
 * their [post]; `reaction` carries who reacted and with what. */
data class ActivityItem(val kind: String, val at: Long, val post: SharedPost? = null, val emoji: String? = null,
    val memberId: String? = null, val memberName: String? = null, val accountId: String? = null,
    val target: ActivityTarget? = null)

/** One page of activity, plus every own post's count of readable replies from other accounts and
 * their reactions, for our own cards. */
data class JournalActivity(val items: List<ActivityItem> = emptyList(), val replyCounts: Map<String, Int> = emptyMap(),
    val reactions: Map<String, List<PostReaction>> = emptyMap()) {
    /** Other accounts' replies to our posts, newest first. */
    val replies: List<SharedPost> get() = items.filter { it.kind == "reply" }.mapNotNull { it.post }
}

object JournalActivityApi {
    const val PAGE = 50
    private val latest = MutableStateFlow<Pair<String, JournalActivity>?>(null)

    /** The newest first page read on this device, with the account it belongs to. */
    val state: StateFlow<Pair<String, JournalActivity>?> = latest

    /** The latest activity for [accountId], or nothing if it was read for another account. */
    fun of(accountId: String?): JournalActivity? = latest.value?.takeIf { it.first == accountId }?.second

    fun parse(j: JSONObject): JournalActivity {
        fun JSONObject.text(k: String) = optString(k).takeIf { has(k) && !isNull(k) && it.isNotBlank() }
        val rows = j.optJSONArray("items")
        val items = if (rows == null) emptyList() else (0 until rows.length()).mapNotNull { i ->
            val item = rows.optJSONObject(i) ?: return@mapNotNull null
            val target = item.optJSONObject("target")?.let { t ->
                ActivityTarget(t.optString("id"), t.text("kind"), t.text("title"), t.optString("text"), t.text("cw"))
            }
            ActivityItem(item.optString("kind"), item.optLong("at"), item.optJSONObject("post")?.let(PeopleApi::sharedPost),
                item.text("emoji"), item.text("member_id"), item.text("member_name"), item.text("account_id"), target)
        }
        val counts = j.optJSONObject("reply_counts")?.let { c -> c.keys().asSequence().associateWith { c.optInt(it) } }.orEmpty()
        val reactions = j.optJSONObject("reactions")?.let { r ->
            r.keys().asSequence().associateWith { id ->
                PostReactions.fromServer(JSONObject().put("reactions", r.optJSONArray(id) ?: org.json.JSONArray()))
            }
        }.orEmpty()
        return JournalActivity(items, counts, reactions)
    }

    suspend fun load(dev: DeviceRecord, before: Long? = null): JournalActivity =
        parse(Api.call("GET", dev.base, "/posts/activity?limit=$PAGE" + (before?.let { "&before=$it" } ?: ""), null, dev.session))

    /** Read the first page again and share it with every screen showing our posts. */
    suspend fun refresh(dev: DeviceRecord): JournalActivity = load(dev).also { latest.value = dev.accountId to it }
}

/** Other accounts' posts read once and kept for this process: what our own posts quote. */
object RemotePosts {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, ThreadReply>()

    fun cached(id: String): ThreadReply? = cache[id]

    /** The post as this reader may read it now, or null (unreadable, gone or offline). */
    suspend fun get(dev: DeviceRecord, id: String): ThreadReply? = cache[id] ?: try {
        PostThreads.parseDetail(Api.call("GET", dev.base, "/posts/$id?depth=0", null, dev.session)).root?.also { cache[id] = it }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
}
