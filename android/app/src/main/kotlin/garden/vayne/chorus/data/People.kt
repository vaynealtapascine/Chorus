package garden.vayne.chorus.data

import java.text.DateFormat
import java.util.Date
import org.json.JSONObject

/** Follow rows and privacy-filtered views returned by the server for this signed-in device. */
data class FollowInfo(val id: String, val account: SpaceAccount, val status: String,
    val prefs: JSONObject = JSONObject())
data class FollowList(val following: List<FollowInfo>, val followers: List<FollowInfo>)
data class PostReaction(val emoji: String, val memberId: String, val memberName: String)
data class SharedPost(val id: String, val kind: String, val title: String?, val text: String,
    val cw: String?, val occurredAt: Long, val authorNames: List<String>, val reactions: List<PostReaction> = emptyList(),
    val attachments: List<ChatAttachment> = emptyList(), val poll: Poll? = null, val pollTally: PollTally? = null,
    val entities: String = "[]", val tags: List<String> = emptyList(),
    /** Whose post it is and its authors, with the first one's look and whose profiles open for us. */
    val accountId: String = "", val looks: AuthorLooks = AuthorLooks())

/**
 * A post's authors as its reader gets them (D-101): member ids, the first one's colour and picture,
 * and [profiles], those whose profile opens for this reader (the server's `profile` flag, the same
 * rule that lets the picture load). Tapping any other author does nothing.
 */
data class AuthorLooks(val ids: List<String> = emptyList(), val color: String? = null, val avatarBlob: String? = null,
    val profiles: List<String> = emptyList(),
    /** A lone author's handle as we may see it (D-126); joint posts show only names. */
    val handle: String? = null) {
    /** The first author whose profile opens, if any. */
    val profile: String? get() = profiles.firstOrNull()

    companion object {
        fun of(post: JSONObject): AuthorLooks {
            val cards = post.optJSONArray("author_cards") ?: return AuthorLooks()
            val rows = (0 until cards.length()).mapNotNull { cards.optJSONObject(it) }
            fun JSONObject.text(k: String) = optString(k).takeIf { has(k) && !isNull(k) && it.isNotBlank() }
            return AuthorLooks(rows.mapNotNull { it.text("id") }, rows.firstOrNull()?.text("color"),
                rows.firstOrNull()?.text("avatar_blob"), rows.filter { it.optBoolean("profile") }.mapNotNull { it.text("id") },
                rows.singleOrNull()?.text("handle"))
        }
    }
}
data class SharedFront(val names: List<String>, val time: String)
data class SharedStats(val days: Int, val members: List<Pair<String, Int>>)
/** Someone a followed account shows as fronting, with their colour, sigil and picture when shared. */
data class SharedMember(val name: String, val color: String?, val glyph: String? = null, val avatarBlob: String? = null)
/** [fronts]: additional fronts the followed account granted (D-084), as (name, revealed names). */
data class FollowerView(val frontNames: List<String>, val history: List<SharedFront>?, val stats: SharedStats?,
    val fronts: List<Pair<String, List<String>>> = emptyList(), val front: List<SharedMember> = emptyList())

object PeopleApi {
    fun sharedPosts(j: JSONObject): List<SharedPost> {
        val items = j.getJSONArray("items")
        fun JSONObject.optionalText(key: String): String? =
            optString(key).takeIf { has(key) && !isNull(key) && it.isNotBlank() }
        return (0 until items.length()).map { i ->
            val post = items.getJSONObject(i)
            val cards = post.optJSONArray("author_cards")
            val names = if (cards == null) emptyList() else (0 until cards.length()).mapNotNull { n ->
                cards.getJSONObject(n).let { it.optionalText("display_name") ?: it.optionalText("name") }
            }
            val reactions = PostReactions.fromServer(post)
            SharedPost(post.getString("id"), post.getString("kind"), post.optionalText("title"),
                post.getString("text"), post.optionalText("cw"), post.getLong("occurred_at"), names, reactions, attachments(post),
                Polls.parse(post.optJSONObject("poll")), Polls.tally(post.optJSONObject("poll_tally")),
                post.optJSONArray("entities")?.toString() ?: "[]", tags(post), post.optionalText("account_id").orEmpty(),
                AuthorLooks.of(post))
        }
    }

    /** A post's files as the server sent them; one without a blob is skipped. */
    fun attachments(post: JSONObject): List<ChatAttachment> {
        fun JSONObject.optionalText(key: String): String? = optString(key).takeIf { has(key) && !isNull(key) && it.isNotBlank() }
        return post.optJSONArray("attachments")?.let { rows -> (0 until rows.length()).mapNotNull { n ->
            val row = rows.optJSONObject(n) ?: return@mapNotNull null
            val id = row.optionalText("id") ?: return@mapNotNull null
            val hash = row.optionalText("blob_hash") ?: return@mapNotNull null
            ChatAttachment(id, hash, row.optionalText("thumb_blob_hash"), row.optionalText("filename") ?: "file",
                row.optionalText("mime") ?: "application/octet-stream", row.optLong("size"),
                row.optionalText("alt_text").orEmpty(), row.optBoolean("is_spoiler"))
        } } ?: emptyList()
    }

    /** A post's tags, without blanks. */
    fun tags(post: JSONObject): List<String> = post.optJSONArray("tags")?.let { a ->
        (0 until a.length()).mapNotNull { (a.opt(it) as? String)?.trim()?.takeIf(String::isNotEmpty) } }.orEmpty()

    fun parse(j: JSONObject): FollowList {
        fun rows(key: String): List<FollowInfo> {
            val a = j.getJSONArray(key)
            return (0 until a.length()).map { i ->
                val f = a.getJSONObject(i)
                val p = f.getJSONObject("account")
                FollowInfo(f.getString("id"), SpaceAccount(p.getString("id"),
                    p.optString("handle").takeIf { p.has("handle") && !p.isNull("handle") && it.isNotBlank() },
                    p.optString("display_name").takeIf { p.has("display_name") && !p.isNull("display_name") && it.isNotBlank() },
                    p.optString("kind").takeIf { p.has("kind") && !p.isNull("kind") && it.isNotBlank() }),
                    f.getString("status"), f.optJSONObject("prefs") ?: JSONObject())
            }
        }
        return FollowList(rows("following"), rows("followers"))
    }

    /** Consume only the server's reveal-time follower view; absent history/stats remain absent. */
    fun followerView(j: JSONObject): FollowerView {
        if (!j.optBoolean("shared", true)) return FollowerView(emptyList(), null, null)
        fun names(entries: org.json.JSONArray?, frontOnly: Boolean): List<String> = if (entries == null) emptyList() else
            (0 until entries.length()).mapNotNull { i ->
                entries.optJSONObject(i)?.takeIf { !frontOnly || it.optString("level") == "front" }
                    ?.optString("name")?.takeIf { it.isNotBlank() && it != "null" }
            }
        val history = j.optJSONArray("history")?.let { items -> (0 until items.length()).mapNotNull { i ->
            items.optJSONObject(i)?.let { item -> SharedFront(names(item.optJSONArray("entries"), false),
                shownTime(item.optJSONObject("time"))) }
        } }
        val stats = j.optJSONObject("stats")?.let { row ->
            val members = row.optJSONArray("members")
            SharedStats(row.optInt("days"), if (members == null) emptyList() else
                (0 until members.length()).mapNotNull { i -> members.optJSONObject(i)?.let { member ->
                    member.optString("name").takeIf { it.isNotBlank() && it != "null" }
                        ?.let { it to member.optInt("share_pct") }
                } })
        }
        val fronts = j.optJSONArray("fronts")?.let { items -> (0 until items.length()).mapNotNull { i ->
            items.optJSONObject(i)?.let { front ->
                (front.optString("name").takeIf { front.has("name") && !front.isNull("name") && it.isNotBlank() }
                    ?: "Another front") to names(front.optJSONArray("entries"), false)
            }
        } } ?: emptyList()
        val front = j.optJSONArray("entries")?.let { entries -> (0 until entries.length()).mapNotNull { i ->
            entries.optJSONObject(i)?.takeIf { it.optString("level") == "front" }?.let { e ->
                e.optString("name").takeIf { it.isNotBlank() && it != "null" }?.let { name ->
                    fun shared(key: String) = e.optString(key).takeIf { e.has(key) && !e.isNull(key) && it.isNotBlank() }
                    SharedMember(name, shared("color"), shared("glyph"), shared("avatar_blob"))
                }
            }
        } } ?: emptyList()
        return FollowerView(front.map { it.name }, history, stats, fronts, front)
    }

    /** Keep time at the precision already chosen by the server; never render hidden clock data. */
    internal fun shownTime(time: JSONObject?): String {
        val at = time?.optLong("at")?.takeIf { time.has("at") && !time.isNull("at") } ?: return ""
        return when (time.optString("precision")) {
            "exact" -> DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at))
            "approx" -> "Around " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at))
            "part_of_day" -> {
                val day = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(at))
                val part = time.optString("part").takeIf { it in setOf("night", "morning", "afternoon", "evening") }
                if (part == null) day else "$day · $part"
            }
            else -> ""
        }
    }

    suspend fun list(dev: DeviceRecord): FollowList = parse(Api.call("GET", dev.base, "/follows", null, dev.session))
    suspend fun request(dev: DeviceRecord, handle: String) {
        Api.post(dev.base, "/follows", JSONObject().put("target", handle.trim()), dev.session)
    }
    suspend fun unfollow(dev: DeviceRecord, id: String) {
        Api.call("DELETE", dev.base, "/follows/$id", null, dev.session)
    }
    suspend fun setPrefs(dev: DeviceRecord, id: String, prefs: JSONObject) {
        Api.call("PUT", dev.base, "/follows/$id/prefs", prefs, dev.session)
    }
    suspend fun view(dev: DeviceRecord, accountId: String): JSONObject =
        Api.call("GET", dev.base, "/accounts/$accountId/view", null, dev.session)

    /** A followed system's members shared with us (D-101); the server decides what's in each. */
    suspend fun members(dev: DeviceRecord, accountId: String): JSONObject =
        Api.call("GET", dev.base, "/accounts/$accountId/members", null, dev.session)

    /** One shared member profile, or `null` when it isn't shared with us (or doesn't exist). */
    suspend fun memberProfile(dev: DeviceRecord, accountId: String, memberId: String): JSONObject? = try {
        Api.call("GET", dev.base, "/accounts/$accountId/members/$memberId", null, dev.session)
    } catch (e: ApiException) {
        if (e.code == "not_found") null else throw e
    }

    /** Post alerts (D-106): ask to hear about one shared member's posts, or stop. The new status. */
    suspend fun setPostAlerts(dev: DeviceRecord, accountId: String, memberId: String, on: Boolean): String =
        Api.call(if (on) "PUT" else "DELETE", dev.base, "/accounts/$accountId/members/$memberId/alerts", null, dev.session)
            .optString("post_alerts", "none")

    /** A followed person's post alerts, by account (a singlet has no member pages); null if not followed. */
    suspend fun personAlerts(dev: DeviceRecord, accountId: String): String? = try {
        Api.call("GET", dev.base, "/accounts/$accountId/alerts", null, dev.session).optString("post_alerts", "none")
    } catch (e: ApiException) {
        if (e.code == "not_found") null else throw e
    }

    suspend fun setPersonAlerts(dev: DeviceRecord, accountId: String, on: Boolean): String =
        Api.call(if (on) "PUT" else "DELETE", dev.base, "/accounts/$accountId/alerts", null, dev.session)
            .optString("post_alerts", "none")

    /** Who asked about, or hears about, our members' posts (requested or active). */
    suspend fun postAlerts(dev: DeviceRecord): List<JSONObject> {
        val items = Api.call("GET", dev.base, "/alerts", null, dev.session).optJSONArray("items") ?: return emptyList()
        return List(items.length()) { items.getJSONObject(it) }
    }

    /** Say yes to a request, or no (also stops someone's alerts; they aren't told). */
    suspend fun decidePostAlert(dev: DeviceRecord, memberId: String, accountId: String, approve: Boolean) {
        Api.call(if (approve) "PUT" else "DELETE", dev.base, "/alerts/$memberId/$accountId", null, dev.session)
    }

    /** One member's posts and entries this reader may read, for their profile (owner, 2026-11-06):
     * the same audience rules, top level only (no replies or reposts). */
    suspend fun memberPosts(dev: DeviceRecord, accountId: String, memberId: String): List<SharedPost> =
        sharedPosts(topLevel(Api.call("GET", dev.base, "/posts?account=$accountId&author=$memberId&limit=50", null, dev.session)))

    /** Only the rows that answer nothing and repeat nothing. */
    fun topLevel(j: JSONObject): JSONObject {
        val items = j.optJSONArray("items") ?: org.json.JSONArray()
        val kept = org.json.JSONArray()
        for (i in 0 until items.length()) {
            val post = items.getJSONObject(i)
            if ((!post.has("reply_to") || post.isNull("reply_to")) && (!post.has("repost_of") || post.isNull("repost_of"))) kept.put(post)
        }
        return JSONObject().put("items", kept)
    }

    /** The server applies current post audience rules before returning these rows. */
    /** One page of what [accountId] shares with us, newest first, older than [before] when given. */
    suspend fun posts(dev: DeviceRecord, accountId: String, before: Long? = null): List<SharedPost> =
        sharedPosts(Api.call("GET", dev.base, "/posts?account=$accountId&limit=${FriendsFeed.PAGE}" +
            (before?.let { "&before=$it" } ?: ""), null, dev.session))
}
