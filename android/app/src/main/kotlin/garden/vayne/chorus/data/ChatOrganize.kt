package garden.vayne.chorus.data

import org.json.JSONArray

/**
 * How the chat list is arranged (D-097), the same rules as web's chat-list.ts:
 * - pinned chats first, in the order they were arranged (`chat_pins`, an account pref);
 * - the rest by latest activity;
 * - a member's private chats (DMs between members, channels kept to some members) only while one
 *   of their members fronts or is co-conscious, or while reading as one of them (`chat.hide_private`,
 *   on unless turned off);
 * - a chat cleared "for us" (`chat_hidden:<channel>` = when) stays out of the list, and its older
 *   messages out of view, until something newer arrives;
 * - an archived chat (`chat_archived:<channel>` = when) is listed under Archived chats instead,
 *   until a message newer than that arrives; search still finds it.
 */
object ChatOrganize {
    const val PINS = "chat_pins"
    const val HIDE_PRIVATE = "chat.hide_private"
    const val HIDDEN = "chat_hidden:"
    const val ARCHIVED = "chat_archived:"

    /** Only DMs between members and channels limited to some members are anyone's private chats. */
    fun isPrivate(channel: ChatChannel, space: ChatSpace): Boolean =
        space.kind == "internal" && (channel.kind == "member_dm" || channel.memberIds.isNotEmpty())

    /**
     * A private chat in a DM kept to some of our members (D-098; [own] says who is ours) shows only
     * while one of them is here by the front exactly as switched ([switched]), never by "Read as",
     * whatever `chat.hide_private` says. A chat with a subsystem is everyone inside's too ([inside],
     * [Subsystems.including]).
     */
    fun privateVisible(channel: ChatChannel, space: ChatSpace, front: List<Entry>, viewingAs: String?, hidePrivate: Boolean,
        own: Set<String>? = null, switched: List<Entry> = front, inside: (List<String>) -> List<String> = { it }): Boolean {
        if (channel.invalidMembership) return false
        val mine = own?.let { ChatManage.sides(channel, space, it)?.mine }
        if (!mine.isNullOrEmpty()) return here(switched, inside(mine))
        if (!hidePrivate || !isPrivate(channel, space)) return true
        val members = inside(channel.memberIds)
        if (viewingAs != null && viewingAs in members) return true
        return here(front, members)
    }

    /**
     * Which chats may be looked at right now, by channel id (lists, pickers, search): [privateVisible]
     * bound to the model. A thread is as visible as the channel its parent message is in.
     */
    fun shown(model: Model, viewingAs: String? = null): (String?) -> Boolean {
        val own = model.knownMemberIds
        val spaces = model.spaces.associateBy { it.id }
        val channels = model.channels.associateBy { it.id }
        val messageChannel = model.searchMessages.associate { it.id to it.channelId }
        fun ok(id: String?, depth: Int): Boolean {
            val c = channels[id ?: return false] ?: return false
            if (c.invalidMembership) return false
            if (c.kind == "thread" && depth < 4) {
                val home = c.parentMessageId?.let { messageChannel[it] }
                return home != null && ok(home, depth + 1)
            }
            if (c.kind == "thread") return false
            val space = spaces[c.spaceId] ?: return false
            return privateVisible(c, space, model.current, viewingAs, model.accountPrefs.hidePrivate, own,
                inside = { Subsystems.including(model, it) })
        }
        return { ok(it, 0) }
    }

    /**
     * Whether a chat opens now: [shown], and its channel viewable by [account] as far as the
     * replica knows its permissions ([ChannelPermissions.canView]); a thread goes by its parent
     * message's channel. The server stays the authority; this keeps a revoked chat shut until its
     * messages are gone.
     */
    fun openable(model: Model, viewingAs: String?, account: String): (String?) -> Boolean {
        val base = shown(model, viewingAs)
        val byChannel = model.channels.associateBy { it.id }
        val parents = model.searchMessages.associate { it.id to it.channelId }
        fun permitted(id: String?, seen: MutableSet<String>): Boolean {
            val c = byChannel[id] ?: return false
            if (c.invalidMembership || seen.size > 256 || !seen.add(c.id)) return false
            return if (c.kind == "thread") permitted(parents[c.parentMessageId], seen)
                else ChannelPermissions.canView(model.channelPermissionState, c, account)
        }
        return { id -> base(id) && permitted(id, HashSet()) }
    }

    /** Revalidate retained message actions against today's channel, audience and clear cutoff. */
    fun actionMessage(model: Model, id: String?, viewingAs: String?, accountId: String): ChatMessage? {
        if (id == null) return null
        val message = model.chatMessages.values.asSequence().flatten().firstOrNull { it.id == id } ?: return null
        if (!shown(model, viewingAs)(message.channelId)) return null
        val channel = model.channels.firstOrNull { it.id == message.channelId } ?: return null
        val space = model.spaces.firstOrNull { it.id == channel.spaceId } ?: return null
        if (!memberVisible(message, space.kind, model.current, viewingAs) || !accountVisible(message, space.kind, accountId)) return null
        if (model.accountPrefs.hiddenChats[message.channelId]?.let { message.occurredAt <= it } == true) return null
        return message
    }

    /** Channel audience restrictions also inherit through threads for outward-forward confirmation. */
    fun restricted(model: Model, channelId: String): Boolean {
        var channel = model.channels.firstOrNull { it.id == channelId } ?: return true
        val seen = HashSet<String>()
        while (channel.kind == "thread" && seen.add(channel.id)) {
            val parent = model.searchMessages.firstOrNull { it.id == channel.parentMessageId } ?: return true
            channel = model.channels.firstOrNull { it.id == parent.channelId } ?: return true
        }
        return channel.kind == "thread" || channel.memberIds.isNotEmpty()
    }

    private fun here(front: List<Entry>, members: List<String>) =
        front.any { it.subjectType == "member" && (it.level == "front" || it.level == "cocon") && it.subjectId in members }

    /** Still archived: nothing newer than when it was archived (a new message brings it back). */
    fun archived(archivedAt: Long?, latestAt: Long?): Boolean = archivedAt != null && (latestAt ?: 0L) <= archivedAt

    /** When to archive a chat: now, or its latest message if that is (by clock skew) later. */
    fun archiveAt(now: Long, latestAt: Long?): Long = maxOf(now, latestAt ?: 0L)

    /** Still cleared: nothing newer than when it was cleared. */
    fun cleared(clearedAt: Long?, latestAt: Long?): Boolean = clearedAt != null && (latestAt ?: 0L) <= clearedAt

    /** Pinned (in pin order) then the rest by latest activity, newest first; ties by title. */
    fun <T> order(rows: List<T>, pins: List<String>, id: (T) -> String, at: (T) -> Long, title: (T) -> String): List<T> {
        val rank = pins.withIndex().associate { (i, p) -> p to i }
        val (pinned, rest) = rows.partition { id(it) in rank }
        return pinned.sortedBy { rank[id(it)] } + rest.sortedWith(compareByDescending(at).thenBy(title))
    }

    /** Pin or unpin, keeping the others' order; a new pin goes last among the pins. */
    fun togglePin(pins: List<String>, channelId: String): List<String> =
        if (channelId in pins) pins - channelId else pins + channelId

    /** The pins value, dropping chats that are gone (deleted, left) so the list doesn't grow forever. */
    fun pinsValue(pins: List<String>, existing: Set<String>): JSONArray = JSONArray(pins.filter { it in existing }.distinct())
}
