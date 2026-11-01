package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Making and editing chats (D-098, D-099; web's chat-manage.ts): whose a private chat in a DM is
 * on each side, finding one before making another, and who may change a space.
 */
object ChatManage {
    /** The sides of a private chat: our members it is kept to, and the other account's. */
    data class Sides(val mine: List<String>, val theirs: List<String>)

    /** A channel outside our own space whose `member_ids` name someone; null when it isn't one. */
    fun sides(channel: ChatChannel, space: ChatSpace?, own: Set<String>): Sides? {
        if (space == null || space.kind == "internal" || channel.memberIds.isEmpty()) return null
        val (mine, theirs) = channel.memberIds.partition { it in own }
        return Sides(mine, theirs)
    }

    /** Threads inherit the parent channel's speaker restriction as well as visibility. */
    fun inheritedSides(channel: ChatChannel, model: Model, own: Set<String>): Sides? {
        var current = channel
        val seen = HashSet<String>()
        while (current.kind == "thread" && seen.add(current.id)) {
            val parent = model.searchMessages.firstOrNull { it.id == current.parentMessageId } ?: return null
            current = model.channels.firstOrNull { it.id == parent.channelId } ?: return null
        }
        return sides(current, model.spaces.firstOrNull { it.id == current.spaceId }, own)
    }

    /** Our private chat in this DM as [member] alone, if there is one. */
    fun existingPrivate(channels: List<ChatChannel>, spaceId: String, member: String, own: Set<String>): ChatChannel? =
        channels.filter { it.spaceId == spaceId && it.kind == "member_dm" }
            .firstOrNull { c -> c.memberIds.filter { it in own } == listOf(member) }

    /** A new private chat in a DM, kept to [member] (named after them, so the other side sees who). */
    fun privatePayload(spaceId: String, member: Member): JSONObject = JSONObject().put("space_id", spaceId)
        .put("kind", "member_dm").put("name", member.shownName).put("member_ids", JSONArray(listOf(member.id)))

    /** Our role in a space: "owner", "admin", "member", "read_only", a custom one, or null. */
    fun roleIn(roles: Map<String, String>, spaceId: String, info: SpaceInfo?, me: String): String? = when {
        info?.ownerAccountId == me -> "owner"
        else -> roles["$spaceId|$me"] ?: if (info?.accounts?.any { it.id == me } == true) "member" else null
    }

    /** Rename, describe, set roles (`space.*`): the owner and admins of a shared space (perms.rs). */
    fun canEditSpace(kind: String?, role: String?) = kind == "shared" && (role == "owner" || role == "admin")

    /** Add and remove people: the owner only (spaces.rs, project::space_access). */
    fun canInvite(kind: String?, role: String?) = kind == "shared" && role == "owner"

    /** A group's name and description, only what changed; empty when nothing did. */
    fun spaceChanges(space: ChatSpace, name: String, description: String): JSONObject = JSONObject().apply {
        val n = name.trim().take(80)
        if (n.isNotEmpty() && n != space.name) put("name", n)
        val d = description.trim()
        if (d != space.description.orEmpty()) put("description", d.ifEmpty { null } ?: JSONObject.NULL)
    }

    /**
     * Who speaks in a chat kept to [mine] (D-098; the server refuses anyone else): the [preferred]
     * choice or default when it is one of them, else one of them who is [here], else the first.
     * Outside a private chat, [preferred] as it is.
     */
    fun keptSpeaker(mine: List<String>?, preferred: String?, here: List<String>): String? =
        if (mine.isNullOrEmpty()) preferred else preferred?.takeIf { it in mine } ?: mine.firstOrNull { it in here } ?: mine.first()

    val ROLES = listOf("member" to "Member", "read_only" to "Read-only", "admin" to "Admin")
    fun roleLabel(role: String) = if (role == "owner") "Owner" else ROLES.firstOrNull { it.first == role }?.second ?: role
}
