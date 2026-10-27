package garden.vayne.chorus.data

/** One shared file and the message that carries it. */
data class ChatFileItem(val attachment: ChatAttachment, val message: ChatMessage)

/**
 * The chat info tabs (F5.26): what a channel has shared, newest first, from the messages the
 * reader can already see. Content behind a content warning or spoiler stays covered in the UI.
 */
object ChatLibrary {
    fun isMedia(a: ChatAttachment): Boolean = a.mime.startsWith("image/") || a.mime.startsWith("video/")

    fun media(messages: List<ChatMessage>): List<ChatFileItem> = items(messages) { isMedia(it) }

    fun files(messages: List<ChatMessage>): List<ChatFileItem> = items(messages) { !isMedia(it) }

    private fun items(messages: List<ChatMessage>, keep: (ChatAttachment) -> Boolean): List<ChatFileItem> =
        messages.sortedWith(compareByDescending<ChatMessage> { it.occurredAt }.thenByDescending { it.id })
            .flatMap { m -> m.attachments.filter(keep).map { ChatFileItem(it, m) } }

    /** Covered until revealed: a spoiler, or anything on a message with a content warning. */
    fun covered(item: ChatFileItem): Boolean = item.attachment.spoiler || item.message.cw != null

    fun who(message: ChatMessage, model: Model): String =
        message.authors.joinToString(" & ") { model.member(it)?.shownName ?: "Someone" }.ifEmpty { "Someone" }

    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
        else -> "%.1f MB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024.0))
    }
}
