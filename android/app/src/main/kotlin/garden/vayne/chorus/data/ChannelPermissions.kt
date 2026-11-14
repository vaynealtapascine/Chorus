package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

/** Advanced channel controls; the server remains the authority (DATA_MODEL §4.4). */
object ChannelPermissions {
    val labels = linkedMapOf("view" to "See this chat", "send" to "Write messages", "react" to "React",
        "thread" to "Start threads", "pin" to "Pin messages", "manage" to "Manage this chat",
        "attach" to "Photos and files", "poll" to "Polls", "aside" to "System asides")
    /** What the space's own overrides can carry: the channel names plus the two that only mean the whole space (D-133). */
    val spaceLabels = labels + linkedMapOf("invite" to "Invite other systems", "history" to "History before they joined")
    enum class Choice { Inherit, Allow, Deny }
    data class Target(val type: String, val id: String, val label: String)

    fun snapshot(p: JSONObject): JSONObject = JSONObject().apply {
        val rows = p.optJSONObject("rows")
        for (table in listOf("space", "space_member", "channel", "channel_permission", "space_permission", "space_sharing"))
            put(table, JSONObject((rows?.optJSONObject(table) ?: JSONObject()).toString()))
        // who is in each space, `space|account`: joins and leaves are the projection's `space_member`
        // set, keyed `space|{"account_id":…}`; its `space_member` rows only carry `space.set_role`
        val joined = JSONObject()
        p.optJSONObject("sets")?.optJSONObject("space_member")?.let { set ->
            for (key in set.keys()) {
                val bar = key.indexOf('|')
                if (bar <= 0 || !set.optBoolean(key)) continue
                val account = runCatching { JSONObject(key.substring(bar + 1)).opt("account_id") as? String }.getOrNull()
                if (!account.isNullOrEmpty()) joined.put("${key.substring(0, bar)}|$account", true)
            }
        }
        put("joined", joined)
    }
    fun fields(state: JSONObject, table: String, id: String): JSONObject {
        val row = state.optJSONObject(table)?.optJSONObject(id)
        return if (row?.optBoolean("exists") == true) row.optJSONObject("fields") ?: JSONObject() else JSONObject()
    }
    internal fun list(j: JSONObject, key: String): List<String> = j.optJSONArray(key)?.let { a ->
        (0 until a.length()).mapNotNull { a.opt(it) as? String }
    }.orEmpty()
    fun override(state: JSONObject, channel: String, target: Target, table: String = "channel_permission") =
        fields(state, table, "$channel|${target.type}|${target.id}")
    fun choices(row: JSONObject, keys: Collection<String> = labels.keys): Map<String, Choice> = keys.associateWith {
        when (it) { in list(row, "deny") -> Choice.Deny; in list(row, "allow") -> Choice.Allow; else -> Choice.Inherit }
    }
    /** Change only edited permissions, rebasing on the newest lists. Unknown grants survive. */
    fun patch(latest: JSONObject, target: Target, baseline: Map<String, Choice>, draft: Map<String, Choice>): JSONObject? {
        val changed = draft.keys.filter { baseline[it] != draft[it] }
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
    fun canManage(state: JSONObject, channel: ChatChannel, account: String, owner: String? = null): Boolean = can(state,channel,account,"manage",owner)
    /** Whether [account] manages the whole [space]: its owner, an admin, or a participant of a DM. */
    fun manages(state: JSONObject, space: String, account: String, owner: String? = null): Boolean {
        if (account.isBlank()) return false
        val row = fields(state, "space", space)
        // the projection stamps a space's creator, which is the server's owner (core model.rs)
        if (owner == account || (row.opt("owner_account_id") ?: row.opt("created_by_account_id")) == account) return true
        val role = role(state, space, account)
        return role == "admin" || (role != null && row.optString("kind") == "dm")
    }
    fun canView(state: JSONObject, channel: ChatChannel, account: String): Boolean = can(state,channel,account,"view")
    /**
     * The account's role in [space] while it is in it, as the server's `space_member` has it
     * (perms.rs): a present join, with the role `space.set_role` gave it, else `member`. A role
     * without a join (set before joining, or kept after leaving) is no membership.
     */
    fun role(state: JSONObject, space: String, account: String): String? {
        if (state.optJSONObject("joined")?.optBoolean("$space|$account") != true) return null
        val row = state.optJSONObject("space_member")?.optJSONObject("$space|$account")?.optJSONObject("fields")
        return (row?.opt("role") as? String)?.takeIf { it.isNotEmpty() } ?: "member"
    }
    private fun can(state: JSONObject, channel: ChatChannel, account: String, permission: String, owner: String? = null): Boolean {
        // Threads use their parent's permissions; controls belong on the parent channel.
        if (channel.kind == "thread" || account.isBlank()) return false
        val space = fields(state, "space", channel.spaceId)
        if (manages(state, channel.spaceId, account, owner)) return true
        val role = role(state, channel.spaceId, account)
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
        return allowed("view") && allowed(permission)
    }
    /** Whose override can be edited: [entity] is a channel (table `channel_permission`) or the space itself (`space_permission`). */
    fun targets(state: JSONObject, spaceId: String, entity: String, table: String, people: List<SpaceAccount>): List<Target> {
        val targets = mutableListOf(Target("role", "everyone", "Everyone in this space"),
            Target("role", "member", "Members"), Target("role", "read_only", "Read-only members"))
        val roles = fields(state, "space", spaceId).optJSONArray("roles") ?: JSONArray()
        for (i in 0 until roles.length()) roles.optJSONObject(i)?.let {
            val id = it.optString("id")
            if (id.isNotBlank()) targets.add(Target("role", id, it.optString("name", id)))
        }
        targets.addAll(people.map { Target("account", it.id, it.shownName) })
        val rows = state.optJSONObject(table) ?: JSONObject()
        for (key in rows.keys()) if (key.startsWith("$entity|")) {
            val parts = key.split('|', limit = 3)
            if (parts.size == 3 && parts[1] in listOf("role", "account")) targets.add(Target(parts[1], parts[2], parts[2]))
        }
        return targets.distinctBy { "${it.type}|${it.id}" }
    }
    fun targets(state: JSONObject, channel: ChatChannel, people: List<SpaceAccount>): List<Target> =
        targets(state, channel.spaceId, channel.id, "channel_permission", people)
    fun settings(state: JSONObject, channel: String): JSONObject =
        fields(state, "channel", channel).optJSONObject("settings") ?: JSONObject()
    fun seconds(input: String): Int? = input.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        ?.toIntOrNull()?.takeIf { it in 0..21600 }
    fun slowModePatch(latest: JSONObject, seconds: Int): JSONObject {
        require(seconds in 0..21600)
        return JSONObject().put("settings", JSONObject(latest.toString()).put("slow_mode_s", seconds))
    }
}
