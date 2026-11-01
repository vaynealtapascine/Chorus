package garden.vayne.chorus.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Display-only chat rules. Eligibility, authorship and read writes remain in the shared core. */
object ChatPresentation {
    enum class Folder(val label: String) { All("All"), Internal("Internal"), Shared("Shared"), Direct("Direct") }

    fun folder(channel: ChatChannel, space: ChatSpace): Folder = when {
        channel.isMemberDm || space.kind == "dm" -> Folder.Direct
        space.kind == "internal" -> Folder.Internal
        else -> Folder.Shared
    }

    /** Who is here for [channelId] (or the Body), with everyone inside a fronting subsystem added
     * as fronting with it (D-092, display only: reads, privacy and authorship use [recorded]). */
    fun presence(model: Model, channelId: String?): List<Entry> = Subsystems.expand(model, recorded(model, channelId)).entries

    /** The front exactly as switched. */
    fun recorded(model: Model, channelId: String?): List<Entry> {
        val named = model.namedFronts ?: return model.current
        val front = channelId?.let { ChatSpeaker.frontOf(model, it) } ?: named.bodyId
        return named.entries(front ?: named.bodyId)
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
