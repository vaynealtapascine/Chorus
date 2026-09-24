package garden.vayne.chorus.data

import org.json.JSONObject

/** Follow rows and privacy-filtered views returned by the server for this signed-in device. */
data class FollowInfo(val id: String, val account: SpaceAccount, val status: String)
data class FollowList(val following: List<FollowInfo>, val followers: List<FollowInfo>)
data class SharedPost(val id: String, val kind: String, val title: String?, val text: String,
    val cw: String?, val occurredAt: Long, val authorNames: List<String>)

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
            SharedPost(post.getString("id"), post.getString("kind"), post.optionalText("title"),
                post.getString("text"), post.optionalText("cw"), post.getLong("occurred_at"), names)
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
                    f.getString("status"))
            }
        }
        return FollowList(rows("following"), rows("followers"))
    }

    /** Only names from the already-filtered follower view; no raw switch times escape it. */
    fun frontNames(j: JSONObject): List<String> {
        if (j.optBoolean("shared", true) == false) return emptyList()
        val a = j.optJSONArray("entries") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.takeIf { it.optString("level") == "front" }
                ?.optString("name")?.takeIf { it.isNotBlank() && it != "null" }
        }
    }

    suspend fun list(dev: DeviceRecord): FollowList = parse(Api.call("GET", dev.base, "/follows", null, dev.session))
    suspend fun request(dev: DeviceRecord, handle: String) {
        Api.post(dev.base, "/follows", JSONObject().put("target", handle.trim()), dev.session)
    }
    suspend fun unfollow(dev: DeviceRecord, id: String) {
        Api.call("DELETE", dev.base, "/follows/$id", null, dev.session)
    }
    suspend fun view(dev: DeviceRecord, accountId: String): JSONObject =
        Api.call("GET", dev.base, "/accounts/$accountId/view", null, dev.session)

    /** The server applies current post audience rules before returning these rows. */
    suspend fun posts(dev: DeviceRecord, accountId: String): List<SharedPost> =
        sharedPosts(Api.call("GET", dev.base, "/posts?account=$accountId&limit=5", null, dev.session))
}
