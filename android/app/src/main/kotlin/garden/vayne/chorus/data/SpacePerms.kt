package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared-space permissions and our own sharing in a space (D-133, D-160; docs/handoff/d133-clients.md,
 * Figma F5.11; web's space-perms.ts). The rule itself is core's (`spacePerms` over UniFFI): this builds
 * its input from the projection, turns the form's switches into ops, and says which composer controls
 * to show. The server stays the authority: a hidden control is a courtesy, a refusal still arrives.
 */
object SpacePerms {
    /** The composer controls a space can hide. */
    data class Gates(val attach: Boolean, val poll: Boolean, val aside: Boolean, val invite: Boolean)
    val OPEN = Gates(attach = true, poll = true, aside = true, invite = true)

    /** The override stored for one target, or null when nothing was ever written. */
    fun level(state: JSONObject, table: String, entity: String, type: String, target: String): JSONObject? {
        val fields = ChannelPermissions.fields(state, table, "$entity|$type|$target")
        if (!fields.has("allow") && !fields.has("deny")) return null
        return JSONObject().put("allow", JSONArray(ChannelPermissions.list(fields, "allow")))
            .put("deny", JSONArray(ChannelPermissions.list(fields, "deny")))
    }

    private fun place(state: JSONObject, table: String, entity: String, me: String, role: String?): JSONObject = JSONObject()
        .put("account", level(state, table, entity, "account", me) ?: JSONObject.NULL)
        .put("role", role?.let { level(state, table, entity, "role", it) } ?: JSONObject.NULL)
        .put("everyone", level(state, table, entity, "role", "everyone") ?: JSONObject.NULL)

    private fun roles(state: JSONObject, spaceId: String): JSONArray =
        ChannelPermissions.fields(state, "space", spaceId).optJSONArray("roles") ?: JSONArray()

    /**
     * The input core's rule takes. [role] is ours from `ChatManage.roleIn` (owner, admin, member, read_only, a
     * custom id, or null when we aren't in the space); [channelId] the channel to answer for (a thread: its
     * parent message's channel), none for a space-wide question.
     */
    fun levels(state: JSONObject, spaceId: String, channelId: String?, me: String, role: String?): JSONObject {
        val ruleRole = if (role == "owner") "admin" else role
        return JSONObject().put("manager", role == "owner" || role == "admin").put("role", ruleRole ?: JSONObject.NULL)
            .put("roles", roles(state, spaceId))
            .apply { if (channelId != null) put("channel", place(state, "channel_permission", channelId, me, ruleRole)) }
            .put("space", place(state, "space_permission", spaceId, me, ruleRole))
    }

    /** What we may do, by core's rule. */
    fun allowed(levels: JSONObject): List<String> {
        val out = JSONArray(uniffi.chorus_ffi.spacePerms(levels.toString()))
        return (0 until out.length()).map { out.getString(it) }
    }

    /**
     * What we may do in [space] (in [channel], a thread asking about [parentChannel]); null where nothing is
     * gated: only shared spaces and guest channels are, and only once the directory has loaded.
     */
    fun here(state: JSONObject, roles: Map<String, String>, space: ChatSpace?, info: SpaceInfo?, channel: ChatChannel?,
        parentChannel: ChatChannel?, me: String): List<String>? {
        if (space == null || info == null || (space.kind != "shared" && !info.guest)) return null
        val asking = if (channel?.kind == "thread") parentChannel else channel
        return runCatching { allowed(levels(state, space.id, asking?.id, me, ChatManage.roleIn(roles, space.id, info, me))) }.getOrNull()
    }

    /** Which controls to show. `send` is required for all but invite; null hides nothing, as before D-133. */
    fun gates(perms: List<String>?): Gates {
        if (perms == null) return OPEN
        val send = "send" in perms
        return Gates(attach = send && "attach" in perms, poll = send && "poll" in perms, aside = send && "aside" in perms, invite = "invite" in perms)
    }

    /** Without `history` the server sends nothing from before we joined: no paging back, and a marker where it starts. */
    fun historyOff(perms: List<String>?): Boolean = perms != null && "history" !in perms

    /** Whether the permissions part of the form is ours to change. */
    fun canSetPermissions(kind: String?, role: String?) = ChatManage.canEditSpace(kind, role)
    /** Our own sharing rows: any present member of a shared space. */
    fun canShare(kind: String?, role: String?) = kind == "shared" && role != null

    // the form's "everyone" switches

    /** Rows the form draws for everyone, in F5.11's order. */
    val EVERYONE_ROWS = listOf("send" to "Send messages", "attach" to "Send photos and files", "poll" to "Create polls",
        "pin" to "Pin messages", "invite" to "Invite other systems", "aside" to "Allow system asides")
    const val HISTORY = "history"

    /** What a plain member gets now (the switches show this); admins always have everything. */
    fun memberSwitches(state: JSONObject, spaceId: String): Map<String, Boolean> {
        val have = allowed(JSONObject().put("manager", false).put("role", "member").put("roles", roles(state, spaceId))
            .put("space", place(state, "space_permission", spaceId, "", "member")))
        return (EVERYONE_ROWS.map { it.first } + HISTORY).associateWith { it in have }
    }

    /**
     * The `space.set_permission` payload for the `everyone` role after the switches change, or null when nothing
     * did. LWW is per target, so the whole lists go, with permissions the form doesn't draw left as they were.
     */
    fun everyonePayload(state: JSONObject, spaceId: String, before: Map<String, Boolean>, after: Map<String, Boolean>): JSONObject? {
        val changed = after.keys.filter { after[it] != before[it] }
        if (changed.isEmpty()) return null
        val now = level(state, "space_permission", spaceId, "role", "everyone")
        val allow = (now?.let { ChannelPermissions.list(it, "allow") } ?: emptyList()).filterNot { it in changed }.toMutableList()
        val deny = (now?.let { ChannelPermissions.list(it, "deny") } ?: emptyList()).filterNot { it in changed }.toMutableList()
        for (p in changed) (if (after[p] == true) allow else deny).add(p)
        return JSONObject().put("target_type", "role").put("target_id", "everyone").put("allow", JSONArray(allow)).put("deny", JSONArray(deny))
    }

    /** What a new shared space starts with (F5.11): history from when people join, no inviting. */
    fun newSpacePayload(): JSONObject = JSONObject().put("target_type", "role").put("target_id", "everyone")
        .put("allow", JSONArray()).put("deny", JSONArray(listOf("invite", "history")))

    /** How many exceptions (per-person and per-role overrides with something in them) the space has. */
    fun exceptions(state: JSONObject, spaceId: String): Int {
        val rows = state.optJSONObject("space_permission") ?: return 0
        return rows.keys().asSequence().count { key ->
            key.startsWith("$spaceId|") && !key.endsWith("|role|everyone") && rows.optJSONObject(key)?.optBoolean("exists") == true &&
                rows.getJSONObject(key).optJSONObject("fields")?.let { ChannelPermissions.list(it, "allow").isNotEmpty() || ChannelPermissions.list(it, "deny").isNotEmpty() } == true
        }
    }

    /** Where the marker "History starts here" goes: history is off and the chat shows the oldest message we hold. */
    fun historyStarts(perms: List<String>?, channelId: String, shown: List<ChatMessage>, held: List<SearchDocument>): Boolean {
        if (!historyOff(perms) || shown.isEmpty()) return false
        val first = shown.first()
        val ids = shown.mapTo(HashSet()) { it.id }
        return held.none { it.channelId == channelId && it.id !in ids && it.occurredAt <= first.occurredAt }
    }
}

/** Our own sharing settings in a space (`space.set_sharing`, F5.11's "What they can see"). */
data class Sharing(val memberNames: Boolean = true, val fronts: Boolean = false, val frontsDelayMin: Int = 15) {
    companion object {
        /** Our settings in [spaceId]; what was never written is the default. */
        fun of(state: JSONObject, spaceId: String, account: String): Sharing {
            val f = ChannelPermissions.fields(state, "space_sharing", "$spaceId|$account")
            return Sharing(f.opt("member_names") as? Boolean ?: true, f.opt("fronts") as? Boolean ?: false,
                (f.opt("fronts_delay_min") as? Number)?.toInt() ?: 15)
        }

        /** Only the fields that changed (a field of ours another device set stays); null when none. */
        fun changes(before: Sharing, after: Sharing): JSONObject? = JSONObject().apply {
            if (after.memberNames != before.memberNames) put("member_names", after.memberNames)
            if (after.fronts != before.fronts) put("fronts", after.fronts)
            if (after.frontsDelayMin != before.frontsDelayMin) put("fronts_delay_min", after.frontsDelayMin)
        }.takeIf { it.length() > 0 }

        private val DELAYS = listOf(0, 15, 60, 360, 1440)
        fun delayLabel(min: Int): String = when {
            min == 0 -> "Now"
            min < 60 -> "$min min"
            min % 1440 == 0 -> if (min == 1440) "1 day" else "${min / 1440} days"
            min % 60 == 0 -> if (min == 60) "1 hour" else "${min / 60} hours"
            else -> "${min / 60} h ${min % 60} min"
        }

        /** The delay choices (minutes); a value another client set stays selectable as its own choice. */
        fun delayOptions(current: Int): List<Int> = (DELAYS + current).distinct().sorted()
    }
}
