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
        channel.kind == "dm" || space.kind == "dm" -> Folder.Direct
        space.kind == "internal" -> Folder.Internal
        else -> Folder.Shared
    }

    fun presence(model: Model, channelId: String?): List<Entry> {
        val named = model.namedFronts ?: return model.current
        val front = channelId?.let { ChatSpeaker.frontOf(model, it) } ?: named.bodyId
        return named.entries(front ?: named.bodyId)
    }

    fun layer(model: Model, entry: Entry?): String? = entry?.let {
        if (model.namedFronts != null) model.namedFronts.liveLayers.find { layer -> layer.id == it.level }?.name
        else when (it.level) { "front" -> "fronting"; "cocon" -> "co-conscious"; "present" -> "nearby"; else -> null }
    }

    fun preview(message: ChatMessage?): String = when {
        message == null -> "No messages yet"
        message.cw != null -> "Content warning: ${message.cw}"
        message.text.isNotBlank() -> message.text.replace('\n', ' ')
        message.attachments.isNotEmpty() -> "Attachment · ${message.attachments.first().filename}"
        else -> "Message"
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
            older.replyTo == null && newer.replyTo == null && older.segments.isEmpty() && newer.segments.isEmpty() &&
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

    fun subtitle(offline: Boolean, reader: String?, unread: Int, context: String): String = when {
        offline -> "Waiting for network…"
        reader != null -> "reading as $reader" + if (unread > 0) " · $unread unread" else ""
        else -> context
    }
}
