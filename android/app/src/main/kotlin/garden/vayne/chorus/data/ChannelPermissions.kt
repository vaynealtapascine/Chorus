package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

/** Advanced channel controls; the server remains the authority (DATA_MODEL §4.4). */
object ChannelPermissions {
    val labels = linkedMapOf("view" to "See this chat", "send" to "Write messages", "react" to "React",
        "thread" to "Start threads", "pin" to "Pin messages", "manage" to "Manage this chat")
    enum class Choice { Inherit, Allow, Deny }
    data class Target(val type: String, val id: String, val label: String)

    fun snapshot(p: JSONObject): JSONObject = JSONObject().apply {
        val rows = p.optJSONObject("rows")
        for (table in listOf("space", "space_member", "channel", "channel_permission"))
            put(table, JSONObject((rows?.optJSONObject(table) ?: JSONObject()).toString()))
    }
    fun fields(state: JSONObject, table: String, id: String): JSONObject {
        val row = state.optJSONObject(table)?.optJSONObject(id)
        return if (row?.optBoolean("exists") == true) row.optJSONObject("fields") ?: JSONObject() else JSONObject()
    }
    private fun list(j: JSONObject, key: String): List<String> = j.optJSONArray(key)?.let { a ->
        (0 until a.length()).mapNotNull { a.opt(it) as? String }
    }.orEmpty()
    fun override(state: JSONObject, channel: String, target: Target) =
        fields(state, "channel_permission", "$channel|${target.type}|${target.id}")
    fun choices(row: JSONObject): Map<String, Choice> = labels.keys.associateWith {
        when (it) { in list(row, "deny") -> Choice.Deny; in list(row, "allow") -> Choice.Allow; else -> Choice.Inherit }
    }
    /** Change only edited permissions, rebasing on the newest lists. Unknown grants survive. */
    fun patch(latest: JSONObject, target: Target, baseline: Map<String, Choice>, draft: Map<String, Choice>): JSONObject? {
        val changed = labels.keys.filter { baseline[it] != draft[it] }
        if (changed.isEmpty()) return null
        val allow = list(latest, "allow").filterNot { it in changed }.toMutableList()
        val deny = list(latest, "deny").filterNot { it in changed }.toMutableList()
        for (permission in changed) when (draft[permission]) {
            Choice.Allow -> allow.add(permission)
            Choice.Deny -> deny.add(permission)
            else -> Unit
        }
        return JSONObject().put("target_type", target.type).put("target_id", target.id)
            .put("allow", JSONArray(allow)).put("deny", JSONArray(deny))
    }
    /** [owner] is the space's owner from the server's directory (`GET /spaces`): the replica doesn't carry it. */
    fun canManage(state: JSONObject, channel: ChatChannel, account: String, owner: String? = null): Boolean {
        // Threads use their parent's permissions; controls belong on the parent channel.
        if (channel.kind == "thread" || account.isBlank()) return false
        val space = fields(state, "space", channel.spaceId)
        if (owner == account || space.optString("owner_account_id") == account) return true
        val member = fields(state, "space_member", "${channel.spaceId}|$account")
        val role = member.optString("role").takeIf { it.isNotEmpty() }
        if (role == "admin" || (role != null && space.optString("kind") == "dm")) return true
        fun allowed(permission: String): Boolean {
            val levels = mutableListOf(Target("account", account, account))
            if (role != null) levels.addAll(listOf(Target("role", role, role), Target("role", "everyone", "Everyone")))
            for (target in levels) {
                val row = override(state, channel.id, target)
                if (permission in list(row, "deny")) return false
                if (permission in list(row, "allow")) return true
            }
            if (role == "member") return permission in listOf("view", "send", "react", "thread", "pin")
            if (role == "read_only") return permission in listOf("view", "react")
            val roles = space.optJSONArray("roles") ?: return false
            return (0 until roles.length()).mapNotNull { roles.optJSONObject(it) }
                .any { it.optString("id") == role && permission in list(it, "perms") }
        }
        return allowed("view") && allowed("manage")
    }
    fun targets(state: JSONObject, channel: ChatChannel, people: List<SpaceAccount>): List<Target> {
        val targets = mutableListOf(Target("role", "everyone", "Everyone in this space"),
            Target("role", "member", "Members"), Target("role", "read_only", "Read-only members"))
        val roles = fields(state, "space", channel.spaceId).optJSONArray("roles") ?: JSONArray()
        for (i in 0 until roles.length()) roles.optJSONObject(i)?.let {
            val id = it.optString("id")
            if (id.isNotBlank()) targets.add(Target("role", id, it.optString("name", id)))
        }
        targets.addAll(people.map { Target("account", it.id, it.shownName) })
        val table = state.optJSONObject("channel_permission") ?: JSONObject()
        for (key in table.keys()) if (key.startsWith("${channel.id}|")) {
            val parts = key.split('|', limit = 3)
            if (parts.size == 3 && parts[1] in listOf("role", "account")) targets.add(Target(parts[1], parts[2], parts[2]))
        }
        return targets.distinctBy { "${it.type}|${it.id}" }
    }
    fun settings(state: JSONObject, channel: String): JSONObject =
        fields(state, "channel", channel).optJSONObject("settings") ?: JSONObject()
    fun seconds(input: String): Int? = input.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        ?.toIntOrNull()?.takeIf { it in 0..21600 }
    fun slowModePatch(latest: JSONObject, seconds: Int): JSONObject {
        require(seconds in 0..21600)
        return JSONObject().put("settings", JSONObject(latest.toString()).put("slow_mode_s", seconds))
    }
}
