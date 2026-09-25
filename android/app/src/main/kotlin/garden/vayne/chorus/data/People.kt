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
    val attachments: List<ChatAttachment> = emptyList())
data class SharedFront(val names: List<String>, val time: String)
data class SharedStats(val days: Int, val members: List<Pair<String, Int>>)
data class FollowerView(val frontNames: List<String>, val history: List<SharedFront>?, val stats: SharedStats?)

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
            val files = post.optJSONArray("attachments")?.let { rows -> (0 until rows.length()).mapNotNull { n ->
                val row = rows.optJSONObject(n) ?: return@mapNotNull null
                val id = row.optionalText("id") ?: return@mapNotNull null
                val hash = row.optionalText("blob_hash") ?: return@mapNotNull null
                ChatAttachment(id, hash, row.optionalText("thumb_blob_hash"), row.optionalText("filename") ?: "file",
                    row.optionalText("mime") ?: "application/octet-stream", row.optLong("size"),
                    row.optionalText("alt_text").orEmpty(), row.optBoolean("is_spoiler"))
            } } ?: emptyList()
            SharedPost(post.getString("id"), post.getString("kind"), post.optionalText("title"),
                post.getString("text"), post.optionalText("cw"), post.getLong("occurred_at"), names, reactions, files)
        }
    }

    fun parse(j: JSONObject): FollowList {
        fun rows(key: String): List<FollowInfo> {
            val a = j.getJSONArray(key)
            return (0 until a.length()).map { i ->
                val f = a.getJSONObject(i)
                val p = f.getJSONObject("account")
                FollowInfo(f.getString("id"), SpaceAccount(p.getString("id"),
                    p.optString("handle").takeIf { p.has("handle") && !p.isNull("handle") && it.isNotBlank() },
                    p.optString("display_name").takeIf { p.has("display_name") && !p.isNull("display_name") && it.isNotBlank() }),
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
        return FollowerView(names(j.optJSONArray("entries"), true), history, stats)
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

    /** The server applies current post audience rules before returning these rows. */
    suspend fun posts(dev: DeviceRecord, accountId: String): List<SharedPost> =
        sharedPosts(Api.call("GET", dev.base, "/posts?account=$accountId&limit=5", null, dev.session))
}
