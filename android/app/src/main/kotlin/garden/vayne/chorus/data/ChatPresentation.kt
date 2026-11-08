package garden.vayne.chorus.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Display-only chat rules. Eligibility, authorship and read writes remain in the shared core. */
object ChatPresentation {
    enum class Folder(val label: String) { All("All"), Internal("Internal"), Shared("Shared"), Direct("Direct") }

    /** Whom a DM with [partner] shows: a person by their own member's picture (from the DM's author
     * [cards], so only one we may read), else the account's own picture; nothing otherwise. */
    fun dmIcon(partner: SpaceAccount, cards: Collection<ForeignAuthor>): Member? =
        cards.firstOrNull { it.accountId == partner.id }?.takeIf { partner.kind == "person" }?.member
            ?: partner.avatarBlob?.let { ForeignAuthor(partner.id, partner.id, partner.shownName, avatarBlob = it).member }

    /** Whom a DM's row shows: the member a private chat is kept to on their side; with nobody listed
     * there (D-098: [sides] names only ours), the account at the other end, like any DM. */
    fun rowIcon(sides: ChatManage.Sides?, spaceKind: String, partner: SpaceAccount?, cards: Collection<ForeignAuthor>,
        card: (String) -> ForeignAuthor?): Member? =
        if (sides != null && sides.theirs.isNotEmpty()) sides.theirs.firstNotNullOfOrNull(card)?.member
        else if (spaceKind == "dm" && partner != null) dmIcon(partner, cards) else null

    fun folder(channel: ChatChannel, space: ChatSpace): Folder = when {
        channel.isMemberDm || space.kind == "dm" -> Folder.Direct
        space.kind == "internal" -> Folder.Internal
        else -> Folder.Shared
    }

    /** Who is here for [channelId] (or the Body), exactly as switched: a subsystem's members count as
     * fronting with it only for which side a bubble sits on (D-102, [aligned]). */
    fun presence(model: Model, channelId: String?): List<Entry> = recorded(model, channelId)

    /** The front exactly as switched. */
    fun recorded(model: Model, channelId: String?): List<Entry> {
        val named = model.namedFronts ?: return model.current
        val front = channelId?.let { ChatSpeaker.frontOf(model, it) } ?: named.bodyId
        return named.entries(front ?: named.bodyId)
    }

    data class FrontEvent(val at: Long, val text: String)

    data class TimelineMarker(val at: Long, val event: FrontEvent? = null)
    data class TimelinePlan(val before: List<TimelineMarker>, val after: List<TimelineMarker>)

    /** Column children stay chronological even in a reverseLayout LazyColumn. */
    fun timelinePlan(older: Long?, message: Long, newest: Boolean, events: List<FrontEvent>): TimelinePlan {
        var previous = older
        fun markers(lines: List<FrontEvent>, includeMessage: Boolean): List<TimelineMarker> = buildList {
            for (line in lines) {
                if (previous == null || day(previous!!) != day(line.at)) add(TimelineMarker(line.at))
                add(TimelineMarker(line.at, line))
                previous = line.at
            }
            if (includeMessage) {
                if (previous == null || day(previous!!) != day(message)) add(TimelineMarker(message))
                previous = message
            }
        }
        val before = markers(events.filter { older != null && it.at > older && it.at <= message }, true)
        val after = markers(if (newest) events.filter { it.at > message } else emptyList(), false)
        return TimelinePlan(before, after)
    }

    /**
     * "Foxglove started fronting" lines for our own chats (web's chat-list.ts frontEvents): switches
     * that began after [from] and by [to]. Starts in the same minute and layer read as one line.
     */
    fun frontEvents(intervals: List<FrontInterval>, from: Long, to: Long, nameOf: (String, String) -> String?,
        layerName: (String) -> String?): List<FrontEvent> {
        data class G(val at: Long, val level: String, val names: MutableList<String>)
        val groups = LinkedHashMap<String, G>()
        for (iv in intervals.sortedBy { it.startAt }) {
            if (iv.startAt <= from || iv.startAt > to) continue
            val name = nameOf(iv.subjectType, iv.subjectId) ?: continue
            val g = groups.getOrPut("${iv.startAt / 60_000}|${iv.level}") { G(iv.startAt, iv.level, mutableListOf()) }
            if (name !in g.names) g.names.add(name)
        }
        return groups.values.sortedBy { it.at }.map { g ->
            val layer = (layerName(g.level) ?: g.level).lowercase()
            val who = if (g.names.size > 2) g.names.dropLast(1).joinToString(", ") + " & " + g.names.last() else g.names.joinToString(" & ")
            val verb = when {
                g.level == "cocon" || layer == "co-conscious" -> "became co-conscious"
                g.level == "present" -> "came near"
                layer.endsWith("ing") -> "started $layer"
                g.level == "front" -> "started fronting"
                else -> "joined $layer"
            }
            FrontEvent(g.at, "$who $verb")
        }
    }

    fun layer(model: Model, entry: Entry?): String? = entry?.let {
        if (model.namedFronts != null) model.namedFronts.liveLayers.find { layer -> layer.id == it.level }?.name
        else when (it.level) { "front" -> "fronting"; "cocon" -> "co-conscious"; "present" -> "nearby"; else -> null }
    }

    fun preview(message: ChatMessage?, poll: Poll? = null): String = when {
        message == null -> "No messages yet"
        message.cw != null -> "Content warning: ${message.cw}"
        poll != null -> "Poll" + (poll.question?.takeIf { it.isNotBlank() }
            ?: message.text.takeIf { it.isNotBlank() }?.let { previewText(it, message.entities) })?.let { " · " + it.replace('\n', ' ') }.orEmpty()
        message.forward.isNotEmpty() -> message.forward.first().let { "Forwarded · " + previewText(it.text, it.entities) }
        message.text.isNotBlank() -> previewText(message.text, message.entities)
        message.attachments.isNotEmpty() -> "Attachment · ${message.attachments.first().filename}"
        else -> "Message"
    }

    private fun previewText(text: String, entities: String): String =
        RichTextPresentation.prepare(text, entities, emptyList()).text.replace('\n', ' ')

    /** Device-local draft wins over received ephemeral typing. Call only for visibility-filtered rows. */
    fun activityPreview(draft: String?, typing: String?): String? = when {
        !draft.isNullOrBlank() -> "Draft: " + draft.replace('\n', ' ').trim()
        !typing.isNullOrBlank() -> typing
        else -> null
    }

    fun own(message: ChatMessage, accountId: String, speakerId: String?): Boolean =
        speakerId != null && speakerId in message.authors &&
            (message.accountId == accountId || message.accountId == null)

    /** Sits on our side (D-103): ours, and by one of [aligned] (the room's speakers, whoever speaks now
     * and everyone inside any of them that is a subsystem, D-102). */
    fun own(message: ChatMessage, accountId: String, aligned: Set<String>): Boolean =
        (message.accountId == accountId || message.accountId == null) && message.authors.any { it in aligned }

    /** [ids] and the members inside any of them that is a subsystem, recursively (D-092 links). */
    fun aligned(model: Model, ids: Collection<String>): Set<String> =
        Subsystems.expand(model, ids.distinct().map { Entry("member", it, "front", false) }).entries
            .filter { it.subjectType == "member" }.mapTo(HashSet()) { it.subjectId }

    /** Never join across an audience, warning, reply, segment, sender, account or date boundary. */
    fun grouped(older: ChatMessage?, newer: ChatMessage?, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (older == null || newer == null) return false
        return older.channelId == newer.channelId && older.authors == newer.authors && older.authors.isNotEmpty() &&
            older.accountId == newer.accountId && older.visibilityMode == newer.visibilityMode &&
            older.visibleMemberIds == newer.visibleMemberIds && older.cw == null && newer.cw == null &&
            older.replyTo == null && newer.replyTo == null &&
            older.segments.all { it.authors == older.authors } && newer.segments.all { it.authors == newer.authors } &&
            newer.occurredAt - older.occurredAt in 0..300_000 && day(older.occurredAt, zone) == day(newer.occurredAt, zone)
    }

    fun day(at: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

    fun dateLabel(at: Long, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String =
        when (val date = day(at, zone)) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        }

    /** Complete only the target already resolved by ChatCompose's existing core names resolver. */
    fun frontMentionStart(draft: String): Int? {
        val token = Regex("(^|\\s)@(\\w*)$").find(draft) ?: return null
        val prefix = token.groupValues[2]
        return if ("front".startsWith(prefix, ignoreCase = true)) draft.lastIndexOf('@') else null
    }

    data class CatchUp(val mark: ReadMark?, val unreadIds: Set<String>) {
        val firstUnread: String? get() = unreadIds.firstOrNull()
    }

    /** Snapshot a loaded, visibility-filtered window before the existing read writer advances it. */
    fun catchUp(messages: List<ChatMessage>, marks: List<ReadMark>, reader: String): CatchUp =
        CatchUp(marks.firstOrNull { it.member == reader }, messages.filter {
            ReadTracking.needsMark(it.occurredAt, it.id, marks, reader)
        }.mapTo(linkedSetOf()) { it.id })

    /** Only explicit member entities; a historical @front cannot be inferred from today's front. */
    fun mentionsMember(message: ChatMessage, member: String): Boolean = runCatching {
        val entities = org.json.JSONArray(message.entities)
        (0 until entities.length()).any { index ->
            val entity = entities.getJSONObject(index)
            entity.optString("type") == "mention" && entity.optString("target_type") == "member" && entity.optString("target_id") == member
        }
    }.getOrDefault(false)

    fun listTimeLabel(at: Long, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val date = day(at, zone)
        return when {
            date == today -> Instant.ofEpochMilli(at).atZone(zone).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
            date == today.minusDays(1) -> "Yesterday"
            date < today && date >= today.minusDays(6) -> date.format(DateTimeFormatter.ofPattern("EEE"))
            else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))
        }
    }

    fun subtitle(offline: Boolean, reader: String?, unread: Int, context: String): String = when {
        offline -> "Waiting for network…"
        reader != null -> "reading as $reader" + if (unread > 0) " · $unread unread" else ""
        else -> context
    }
}
