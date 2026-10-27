package garden.vayne.chorus.data

/** One web link and the message it was shared in. */
data class ChatLink(val url: String, val message: ChatMessage)

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

    private val URL = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)

    /**
     * Web links, newest first: `text_link` targets, `url` entities and bare links in the text
     * (other clients may not mark them). Only http(s); trailing punctuation is not part of a link.
     */
    fun links(messages: List<ChatMessage>): List<ChatLink> =
        messages.sortedWith(compareByDescending<ChatMessage> { it.occurredAt }.thenByDescending { it.id }).flatMap { m ->
            val found = LinkedHashSet<String>()
            val entities = runCatching { org.json.JSONArray(m.entities) }.getOrNull()
            for (i in 0 until (entities?.length() ?: 0)) {
                val e = entities?.optJSONObject(i) ?: continue
                when (e.optString("type")) {
                    "text_link" -> e.optString("url").takeIf { it.isNotBlank() }?.let(found::add)
                    "url" -> {
                        val from = e.optInt("offset"); val to = from + e.optInt("length")
                        if (from >= 0 && to <= m.text.length && from < to) found.add(m.text.substring(from, to))
                    }
                }
            }
            for (texts in listOf(m.text) + m.forward.map { it.text }) URL.findAll(texts).forEach { found.add(it.value) }
            found.map { it.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '\'', '"') }
                .filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
                .distinct().map { ChatLink(it, m) }
        }

    fun domain(url: String): String =
        runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull()?.takeIf { it.isNotBlank() } ?: url

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
