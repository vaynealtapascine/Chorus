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
 *   messages out of view, until something newer arrives.
 */
object ChatOrganize {
    const val PINS = "chat_pins"
    const val HIDE_PRIVATE = "chat.hide_private"
    const val HIDDEN = "chat_hidden:"

    /** Only DMs between members and channels limited to some members are anyone's private chats. */
    fun isPrivate(channel: ChatChannel, space: ChatSpace): Boolean =
        space.kind == "internal" && (channel.kind == "member_dm" || channel.memberIds.isNotEmpty())

    fun privateVisible(channel: ChatChannel, space: ChatSpace, front: List<Entry>, viewingAs: String?, hidePrivate: Boolean): Boolean {
        if (!hidePrivate || !isPrivate(channel, space)) return true
        if (viewingAs != null && viewingAs in channel.memberIds) return true
        return front.any { it.subjectType == "member" && (it.level == "front" || it.level == "cocon") && it.subjectId in channel.memberIds }
    }

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
