package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

private fun text(j: JSONObject, key: String): String? =
    if (j.has(key) && !j.isNull(key)) j.optString(key).takeIf { it.isNotBlank() } else null

/** Server directory data that is intentionally absent from this account's sync projection. */
data class SpaceAccount(val id: String, val handle: String?, val displayName: String?) {
    val shownName: String get() = displayName?.takeIf { it.isNotBlank() }
        ?: handle?.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "Someone"
}

data class SpaceInfo(val id: String, val kind: String, val ownerAccountId: String, val accounts: List<SpaceAccount>)
data class ForeignAuthor(val id: String, val accountId: String, val name: String) {
    companion object {
        fun fromJson(j: JSONObject): ForeignAuthor = ForeignAuthor(
            j.getString("id"), j.getString("account_id"),
            text(j, "display_name") ?: text(j, "name") ?: "Someone",
        )
    }
}

object Spaces {
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    fun directory(j: JSONObject): Map<String, SpaceInfo> = j.getJSONArray("items").objects().associate { s ->
        val id = s.getString("id")
        id to SpaceInfo(id, s.getString("kind"), s.getString("owner_account_id"),
            s.getJSONArray("accounts").objects().map(::account))
    }

    fun connected(j: JSONObject): List<SpaceAccount> = sequenceOf("following", "followers")
        .flatMap { direction -> j.getJSONArray(direction).objects().asSequence() }
        .filter { it.optString("status") == "active" }
        .map { account(it.getJSONObject("account")) }
        .distinctBy { it.id }
        .toList()

    fun authors(j: JSONObject): Map<String, ForeignAuthor> = j.getJSONArray("members").objects()
        .map(ForeignAuthor::fromJson).associateBy { it.id }

    private fun account(j: JSONObject) = SpaceAccount(j.getString("id"), text(j, "handle"), text(j, "display_name"))

    suspend fun list(dev: DeviceRecord): Map<String, SpaceInfo> = directory(Api.call("GET", dev.base, "/spaces", null, dev.session))
    suspend fun connections(dev: DeviceRecord): List<SpaceAccount> = connected(Api.call("GET", dev.base, "/follows", null, dev.session))
    suspend fun authorCards(dev: DeviceRecord, spaceId: String): Map<String, ForeignAuthor> =
        authors(Api.call("GET", dev.base, "/spaces/$spaceId/authors", null, dev.session))

    suspend fun createShared(dev: DeviceRecord, name: String, accounts: List<String>): String =
        Api.post(dev.base, "/spaces", JSONObject().put("kind", "shared").put("name", name.trim())
            .put("accounts", JSONArray(accounts)), dev.session).getString("id")

    suspend fun openDm(dev: DeviceRecord, accountId: String): String =
        Api.post(dev.base, "/spaces", JSONObject().put("kind", "dm")
            .put("accounts", JSONArray(listOf(accountId))), dev.session).getString("id")

    suspend fun leave(dev: DeviceRecord, spaceId: String) {
        Api.call("DELETE", dev.base, "/spaces/$spaceId/members/me", null, dev.session)
    }
}
