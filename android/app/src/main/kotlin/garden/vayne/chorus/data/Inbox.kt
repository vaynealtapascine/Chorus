package garden.vayne.chorus.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Notifications screen (D-127, Figma F11): what `GET /notifications` delivered, what other
 * accounts did with our posts (`GET /posts/activity`), and what waits on us (follow and alert
 * requests, front reviews). Nothing here is new information: each source already reached this
 * account. Read marks are per reader (a member; a person's own member) in the account pref
 * `inbox.read:<member>` = `{until, ids}`; what shows is `inbox.shows`. The web's `inbox.ts` agrees.
 */
data class InboxItem(
    val id: String, val kind: String,
    /** `mentions` · `friends` · `system` · `app`: the chips. */
    val category: String,
    /** Which "What shows here" switch governs it ([Inbox.SHOWS]); null: always shown. */
    val shows: String?,
    val at: Long,
    /** Who did it (bold at the start of [title] when it starts with it). */
    val actor: String?, val title: String,
    /** A grey line under the title, and a quoted bubble (what was said). */
    val detail: String? = null, val quote: String? = null,
    val color: String? = null, val avatarBlob: String? = null,
    val postId: String? = null, val channelId: String? = null, val messageId: String? = null,
    val accountId: String? = null, val memberId: String? = null,
    /** Follow request to accept (its follow id), alert request (member + account), or a review. */
    val followId: String? = null, val needsYou: Boolean = false,
)

/** A reader's read marks: everything at or before [until], and [ids] read one by one after it. */
data class InboxRead(val until: Long = 0, val ids: Set<String> = emptySet()) {
    fun read(item: InboxItem): Boolean = !item.needsYou && (item.at <= until || item.id in ids)

    fun json(): JSONObject = JSONObject().put("until", until).put("ids", JSONArray(ids.toList()))

    /** Mark one read; old single marks fall away once `until` passes them. */
    fun mark(item: InboxItem): InboxRead = if (read(item)) this else copy(ids = (ids + item.id).toList().takeLast(Inbox.MAX_IDS).toSet())

    /** Mark one unread again: it leaves [ids], and if `until` covers it, `until` moves back before it
     * while every other item between keeps its mark. */
    fun unmark(item: InboxItem, all: List<InboxItem>): InboxRead {
        if (item.at > until) return copy(ids = ids - item.id)
        val keep = all.filter { it.at in item.at..until && it.id != item.id }.map { it.id }
        return InboxRead(item.at - 1, (ids - item.id + keep).toList().takeLast(Inbox.MAX_IDS).toSet())
    }

    companion object {
        fun parse(value: Any?): InboxRead {
            val o = value as? JSONObject ?: return InboxRead()
            val ids = o.optJSONArray("ids")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) } }.orEmpty()
            return InboxRead(o.optLong("until", 0), ids.toSet())
        }
    }
}

object Inbox {
    const val READ = "inbox.read:"
    const val SHOWS_PREF = "inbox.shows"
    const val MAX_IDS = 200

    /** "What shows here": key, label, on unless turned off (own switches start off). */
    val SHOWS = listOf("mentions" to "Mentions and replies", "friend_switches" to "Friends’ switches",
        "posts" to "New posts you asked for", "likes" to "Likes, quotes and reposts", "own_switches" to "Our own switches")

    fun shows(pref: JSONObject?, key: String): Boolean = pref?.optBoolean(key, key != "own_switches") ?: (key != "own_switches")

    /** The chips: all, then each category (a person has no "our system"). */
    fun chips(person: Boolean): List<Pair<String, String>> = listOfNotNull("all" to "All", "mentions" to "Mentions",
        "friends" to "Friends", ("system" to "Our system").takeIf { !person }, "app" to "App")

    private fun JSONObject.text(k: String) = optString(k).takeIf { has(k) && !isNull(k) && it.isNotBlank() }

    /** `GET /notifications` items as rows. Unknown kinds, and plain messages, are skipped. */
    fun fromNotifications(items: JSONArray?): List<InboxItem> {
        if (items == null) return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val n = items.optJSONObject(i) ?: return@mapNotNull null
            val id = n.text("id") ?: return@mapNotNull null
            val kind = n.optString("kind")
            val at = n.optLong("delivered_at", n.optJSONObject("time")?.optLong("at") ?: 0)
            val sender = n.optJSONObject("sender")
            val who = sender?.text("name")
            val title = n.text("title") ?: "Chorus"
            val text = n.text("text")
            fun chat(verb: String) = InboxItem(id, kind, "mentions", "mentions", at, who, if (who != null) "$who $verb" else title,
                detail = if (who != null) title.takeIf { it != who } else null, quote = text, color = sender?.text("color"),
                avatarBlob = sender?.text("avatar_blob"), channelId = n.text("channel_id"), messageId = n.text("message_id"))
            when (kind) {
                "mention" -> chat("mentioned you")
                "reply" -> chat("replied to you")
                // plain messages (DMs, member DMs, a channel set to "all") are pushed but not listed:
                // the chat list already holds them (owner, 2026-11-07); a mention or reply in a DM is listed
                "dm", "message", "member_dm" -> null
                "post_reply" -> InboxItem(id, kind, "mentions", "mentions", at, who, title, quote = text,
                    color = sender?.text("color"), avatarBlob = sender?.text("avatar_blob"), postId = n.text("post_id"),
                    accountId = n.text("account_id"))
                "poll_nudge" -> InboxItem(id, kind, "mentions", "mentions", at, who, title, quote = text,
                    color = sender?.text("color"), avatarBlob = sender?.text("avatar_blob"), postId = n.text("post_id"),
                    channelId = n.text("channel_id"), messageId = n.text("message_id"))
                "post" -> InboxItem(id, kind, "friends", "posts", at, who, title, detail = text, color = sender?.text("color"),
                    avatarBlob = sender?.text("avatar_blob"), postId = n.text("post_id"), accountId = n.text("account_id"))
                "switch" -> {
                    val account = n.optJSONObject("account")
                    val name = account?.text("display_name") ?: account?.text("handle")?.let { "@$it" } ?: "A friend"
                    InboxItem(id, kind, "friends", "friend_switches", at, name, "$name: ${text ?: "switched"}",
                        accountId = account?.text("id"))
                }
                "own_switch" -> InboxItem(id, kind, "system", "own_switches", at, null, title, detail = text)
                "alert_request", "alert_accepted" -> InboxItem(id, kind, "friends", null, at, null, title, detail = text,
                    memberId = n.text("member_id"), accountId = n.text("account_id"))
                "export_ready" -> InboxItem(id, kind, "app", null, at, null, title, detail = text)
                else -> null
            }
        }
    }

    /** Hearts, quotes and reposts of our posts by other accounts (replies arrive as notifications).
     * One row per post and emoji: "Kit and Juniper liked “A quiet afternoon”". */
    fun fromActivity(activity: JournalActivity?): List<InboxItem> {
        if (activity == null) return emptyList()
        fun about(t: ActivityTarget?) = t?.let { it.title ?: it.cw?.let { cw -> "CW: $cw" } ?: it.text }
            ?.let { if (it.length > 60) it.take(60).substringBeforeLast(' ').trimEnd('.', ',', ' ') + "…" else it }?.let { "“$it”" } ?: "your post"
        val out = ArrayList<InboxItem>()
        for ((key, group) in activity.items.filter { it.kind == "reaction" }.groupBy { "${it.target?.id}:${it.emoji}" }) {
            val names = group.mapNotNull { it.memberName }.distinct()
            val who = when (names.size) { 0 -> "Someone"; 1 -> names[0]; 2 -> "${names[0]} and ${names[1]}"; else -> "${names[0]} and ${names.size - 1} others" }
            val first = group.first()
            val verb = if (first.emoji == PostReactions.HEART) "liked" else "reacted ${first.emoji} to"
            out += InboxItem("react:$key:${group.mapNotNull { it.memberId }.sorted().joinToString(",").hashCode()}", "reaction", "friends", "likes",
                group.maxOf { it.at }, who, "$who $verb ${about(first.target)}", postId = first.target?.id)
        }
        for (a in activity.items.filter { it.kind == "quote" || it.kind == "repost" }) {
            val post = a.post ?: continue
            val who = post.authorNames.joinToString(" & ").ifBlank { "Someone" }
            out += InboxItem("${a.kind}:${post.id}", a.kind, "friends", "likes", a.at, who,
                "$who ${if (a.kind == "quote") "quoted" else "reposted"} ${about(a.target)}",
                quote = post.text.takeIf { a.kind == "quote" && it.isNotBlank() }, color = post.looks.color,
                avatarBlob = post.looks.avatarBlob, postId = if (a.kind == "quote") post.id else a.target?.id, accountId = post.accountId)
        }
        return out
    }

    /** What waits on us: follow requests, alert requests and front reviews. Always unread. */
    fun needsYou(follows: FollowList?, alerts: List<JSONObject>, reviews: List<FrontReview>, person: Boolean,
        memberName: (String) -> String?, now: Long): List<InboxItem> {
        val out = ArrayList<InboxItem>()
        for (f in follows?.followers.orEmpty().filter { it.status == "requested" }) out += InboxItem("follow:${f.id}", "follow_request",
            "friends", null, now, f.account.shownName, "${f.account.shownName} wants to follow ${if (person) "you" else "your system"}",
            accountId = f.account.id, followId = f.id, needsYou = true)
        for (a in alerts.filter { it.optString("status") == "requested" }) {
            val account = a.optJSONObject("account")
            val who = account?.text("display_name") ?: account?.text("handle")?.let { "@$it" } ?: "Someone"
            val member = a.optString("member_id")
            out += InboxItem("alert:$member:${account?.text("id")}", "alert_ask", "friends", null, a.optLong("created_at", now), who,
                "$who wants to hear when ${memberName(member) ?: "a member"} posts", memberId = member, accountId = account?.text("id"),
                needsYou = true)
        }
        for (r in reviews) out += InboxItem("review:${r.id}", "review", "system", null, now, null,
            "Two devices switched within a minute", detail = "Check which one is right", needsYou = true)
        return out
    }

    /** Rows to show for one chip, newest first, with what "What shows here" hides left out. */
    fun visible(items: List<InboxItem>, chip: String, shows: JSONObject?): List<InboxItem> =
        items.filter { (chip == "all" || it.category == chip) && (it.shows == null || shows(shows, it.shows)) }
            .sortedWith(compareByDescending<InboxItem> { it.needsYou }.thenByDescending { it.at }.thenBy { it.id })

    fun unread(items: List<InboxItem>, read: InboxRead, shows: JSONObject?, chip: String = "all"): Int =
        visible(items, chip, shows).count { !read.read(it) }

    /** "Needs you", "Today", "Earlier": the screen's sections, in order. */
    fun section(item: InboxItem, startOfToday: Long): String = when {
        item.needsYou -> "Needs you"
        item.at >= startOfToday -> "Today"
        else -> "Earlier"
    }

    fun readPayload(member: String, read: InboxRead): JSONObject = AccountPrefs.payload(READ + member, read.json())

    fun showsPayload(current: JSONObject?, key: String, on: Boolean): JSONObject =
        AccountPrefs.payload(SHOWS_PREF, JSONObject((current ?: JSONObject()).toString()).put(key, on))

    private val latest = MutableStateFlow<Pair<String, List<InboxItem>>?>(null)

    /** The last read of every source, with the account it belongs to: the bar's badge and the screen. */
    val state: StateFlow<Pair<String, List<InboxItem>>?> = latest

    fun of(accountId: String?): List<InboxItem>? = latest.value?.takeIf { it.first == accountId }?.second

    /** Read every source again. One failing source leaves the others standing. */
    suspend fun refresh(dev: DeviceRecord, model: Model): List<InboxItem> {
        suspend fun <T> soft(read: suspend () -> T): T? = try { read() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        val notes = soft { Api.call("GET", dev.base, "/notifications", null, dev.session).optJSONArray("items") }
        val activity = soft { JournalActivityApi.refresh(dev) }
        val follows = soft { PeopleApi.list(dev) }
        val alerts = soft { PeopleApi.postAlerts(dev) }.orEmpty()
        val items = fromNotifications(notes) + fromActivity(activity) +
            needsYou(follows, alerts, model.reviews, model.isPerson, { model.member(it)?.shownName }, System.currentTimeMillis())
        // with nothing read at all (offline), keep what was there
        if (notes == null && activity == null && follows == null) return of(dev.accountId).orEmpty()
        latest.value = dev.accountId to items
        return items
    }
}
