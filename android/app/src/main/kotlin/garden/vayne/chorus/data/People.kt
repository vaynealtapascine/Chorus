package garden.vayne.chorus.data

import org.json.JSONObject

/** Follow rows and privacy-filtered views returned by the server for this signed-in device. */
data class FollowInfo(val id: String, val account: SpaceAccount, val status: String)
data class FollowList(val following: List<FollowInfo>, val followers: List<FollowInfo>)

object PeopleApi {
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
}
