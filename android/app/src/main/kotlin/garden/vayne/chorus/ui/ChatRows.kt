package garden.vayne.chorus.ui

import garden.vayne.chorus.data.ChatManage
import garden.vayne.chorus.data.ChatOrganize
import garden.vayne.chorus.data.ChatPresentation
import garden.vayne.chorus.data.ForeignAuthor
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.ReadTracking
import garden.vayne.chorus.data.SpaceInfo
import garden.vayne.chorus.data.accountVisible
import garden.vayne.chorus.data.memberVisible

/** The chat list's rows (Chat, and Home's *Waiting for you*): every chat this reader may see, its
 * title, newest message and unread count, in the list's order. [directory], [foreignAuthors] and
 * [privateCards] are the server's names for other accounts and their members; without them a DM
 * shows its space name until they load. */
internal fun chatListRows(model: Model, viewingAs: String?, accountId: String, directory: Map<String, SpaceInfo>,
    foreignAuthors: Map<String, ForeignAuthor>, privateCards: Map<String, ForeignAuthor>): List<ChatListRow> {
    val ownIds = model.knownMemberIds
    val prefs = model.accountPrefs
    return model.channels.mapNotNull { candidate ->
        if (candidate.kind == "thread") return@mapNotNull null // threads live under their parent
        val home = model.spaces.find { it.id == candidate.spaceId } ?: return@mapNotNull null
        // a member's private chats only while one of them is here (D-097, D-098)
        if (!ChatOrganize.privateVisible(candidate, home, model.current, viewingAs, prefs.hidePrivate, ownIds,
                inside = { garden.vayne.chorus.data.Subsystems.including(model, it) })) return@mapNotNull null
        val clearedAt = prefs.hiddenChats[candidate.id]
        val visible = model.chatMessages[candidate.id].orEmpty().filter {
            memberVisible(it, home.kind, model.current, viewingAs) && accountVisible(it, home.kind, accountId) &&
                (clearedAt == null || it.occurredAt > clearedAt)
        }
        // cleared for us: gone until something newer arrives
        if (clearedAt != null && visible.isEmpty()) return@mapNotNull null
        val reader = if (!model.accountPrefs.readPerMember) "" else viewingAs ?: ReadTracking.readersFor(model, candidate.id, true).firstOrNull().orEmpty()
        val marks = model.readMarks[candidate.id].orEmpty()
        val unread = visible.count { ReadTracking.needsMark(it.occurredAt, it.id, marks, reader) }
        val partnerAccount = directory[home.id]?.accounts?.firstOrNull { it.id != accountId }
        val partner = partnerAccount?.shownName
        // a private chat (D-098): titled by their member on their side, by the friend on ours
        val sides = ChatManage.sides(candidate, home, ownIds)
        val siblings = model.channels.count { it.spaceId == home.id && it.kind != "thread" }
        val title = if (sides != null) sides.theirs.mapNotNull { privateCards[it]?.name ?: foreignAuthors[it]?.name }
                .joinToString(" & ").ifEmpty { partner ?: candidate.name }
            else if (home.kind == "dm") partner ?: home.name
            else if (home.kind == "shared" && siblings == 1) home.name else candidate.name
        val latest = visible.lastOrNull()
        val sender = latest?.authors?.joinToString(" & ") { id -> model.member(id)?.shownName ?: foreignAuthors[id]?.name
            ?: directory[home.id]?.accounts?.find { it.id == latest.accountId }?.shownName ?: "Someone" }
        ChatListRow(candidate, home, title, latest, unread, sender,
            muted = model.accountPrefs.channelNotify(candidate.id, home.kind) == "none", pinned = candidate.id in prefs.chatPins,
            private = sides != null || (home.kind == "internal" && candidate.kind == "text" && candidate.memberIds.isNotEmpty()),
            whose = sides?.mine?.mapNotNull { model.member(it)?.shownName }?.joinToString(" & ")?.ifEmpty { null },
            archived = ChatOrganize.archived(prefs.archivedChats[candidate.id], latest?.occurredAt),
            icon = ChatPresentation.rowIcon(sides, home.kind, partnerAccount, privateCards.values) { privateCards[it] ?: foreignAuthors[it] },
            grouped = sides == null && ChatOrganize.groupsInto(home.kind, siblings))
    }.let { rows -> ChatOrganize.order(rows, prefs.chatPins, { it.channel.id }, { it.latest?.occurredAt ?: 0L }, { it.title }) }
}
