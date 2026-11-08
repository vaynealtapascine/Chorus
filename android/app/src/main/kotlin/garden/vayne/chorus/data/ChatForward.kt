package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

/** One forwarded message as its recipients see it: the snapshot, not the original (SPEC §5.3). */
data class ForwardItem(val messageId: String, val text: String, val channelName: String?,
    val authors: List<String>, val occurredAt: Long, val entities: String = "[]")

/**
 * Forwarding (D-048): a `message.forward` carries the origin id and a snapshot, so it reads the
 * same where the original can't be seen. Files are copied as new attachments in the target scope.
 */
object ChatForward {
    fun parse(a: JSONArray?): List<ForwardItem> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        val id = o.optString("message_id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val authors = o.optJSONArray("authors")?.let { list -> (0 until list.length()).map(list::optString).filter { it.isNotBlank() } }.orEmpty()
        ForwardItem(id, o.optString("text"), o.optString("channel_name").takeIf { it.isNotBlank() && it != "null" },
            authors, o.optLong("occurred_at"), o.optJSONArray("entities")?.toString() ?: "[]")
    }

    /** A message's `quote`: `{items: [...]}` (a bundle) or one range `{message_id, offset, length, text}`. */
    fun quoted(q: JSONObject?): List<ForwardItem> = when {
        q == null -> emptyList()
        q.has("items") -> parse(q.optJSONArray("items"))
        q.optString("message_id").isNotBlank() -> listOf(ForwardItem(q.getString("message_id"), q.optString("text"), null, emptyList(), 0))
        else -> emptyList()
    }

    /** Expand selections to complete Unicode scalar values while retaining UTF-16 wire offsets. */
    fun safeRange(text: String, part: IntRange?): IntRange {
        if (part == null || part.isEmpty() || part.first < 0 || part.last >= text.length) return text.indices
        var start = part.first
        var end = part.last + 1
        if (start > 0 && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) start--
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end++
        return start until end
    }

    /** Quote one whole message (a range over all of it) or several as a bundle, as web writes them. */
    fun quote(messages: List<ChatMessage>, channelName: String?, part: IntRange? = null): JSONObject = if (messages.size == 1) messages[0].let { m ->
        // part: a UTF-16 range of the text (Kotlin string indices are UTF-16, like message offsets)
        val range = safeRange(m.text, part)
        val text = if (m.text.isEmpty()) "" else m.text.substring(range.first, range.last + 1)
        JSONObject().put("message_id", m.id).put("offset", if (m.text.isEmpty()) 0 else range.first).put("length", text.length).put("text", text)
    } else JSONObject().put("items", JSONArray(messages.map { snapshot(it, channelName) }))

    /** Several messages forwarded together as one bundle (D-048), oldest first. */
    fun bundle(targetChannel: String, speaker: String, messages: List<ChatMessage>, channelName: String?,
        attachmentIds: List<String>, comment: String = "", showAuthors: Boolean = true): JSONObject = JSONObject()
        .put("channel_id", targetChannel).put("authors", JSONArray(listOf(speaker)))
        .put("text", comment.trim()).put("entities", JSONArray())
        .put("forward_of_id", messages.first().id)
        .put("forward_snapshot", JSONArray(messages.sortedBy { it.occurredAt }.map { snapshot(it, channelName, showAuthors) }))
        .apply { if (attachmentIds.isNotEmpty()) put("attachments", JSONArray(attachmentIds)) }

    private fun attachment(a: ChatAttachment): JSONObject = JSONObject().put("id", a.id).put("blob_hash", a.blobHash)
        .put("filename", a.filename).put("mime", a.mime).put("size", a.size).put("alt_text", a.altText)
        .put("is_spoiler", a.spoiler).apply { a.thumbHash?.let { put("thumb_blob_hash", it) } }

    /**
     * The source chat's name as a snapshot may carry it: only when the copy stays in the space it
     * was written in. Forwarded into another space, the original chat's name stays hidden (F5.20).
     */
    fun sourceName(source: ChatChannel?, targetSpaceId: String): String? = source?.takeIf { it.spaceId == targetSpaceId }?.name

    /**
     * The whole message as web `snapshot()` writes it; [channelName] null leaves the name out, and
     * [showAuthors] false leaves who wrote it out ("Show who originally wrote it", F5.20).
     */
    fun snapshot(message: ChatMessage, channelName: String?, showAuthors: Boolean = true): JSONObject = JSONObject()
        .put("message_id", message.id).apply { if (channelName != null) put("channel_name", channelName) }
        .put("authors", JSONArray(if (showAuthors) message.authors else emptyList())).put("text", message.text)
        .put("entities", runCatching { JSONArray(message.entities) }.getOrElse { JSONArray() })
        .put("occurred_at", message.occurredAt)
        .put("attachments", JSONArray(message.attachments.map(::attachment)))
        .put("offset", 0).put("length", message.text.length)

    /** The copied attachment record for the target scope (same blob, new id). */
    fun attachmentCopy(a: ChatAttachment): JSONObject = JSONObject().put("blob_hash", a.blobHash)
        .put("filename", a.filename).put("mime", a.mime).put("size", a.size).put("alt_text", a.altText)
        .put("is_spoiler", a.spoiler).apply { a.thumbHash?.let { put("thumb_blob_hash", it) } }

    /** One message forwarded, with an optional [comment] of the forwarder's own as its text (F5.20). */
    fun payload(targetChannel: String, speaker: String, message: ChatMessage, channelName: String?,
        attachmentIds: List<String>, comment: String = "", showAuthors: Boolean = true): JSONObject = JSONObject()
        .put("channel_id", targetChannel).put("authors", JSONArray(listOf(speaker)))
        .put("text", comment.trim()).put("entities", JSONArray())
        .put("forward_of_id", message.id)
        .put("forward_snapshot", JSONArray(listOf(snapshot(message, channelName, showAuthors))))
        .apply { if (attachmentIds.isNotEmpty()) put("attachments", JSONArray(attachmentIds)) }

    /**
     * Forwarding member-visible, locked or internal content outward asks once (SPEC §5.3):
     * internal → anywhere else, or any message that isn't visible to everyone where it was written.
     */
    fun asksFirst(source: ChatSpace, targetSpaceId: String, targetChannel: String, sourceChannel: String,
        message: ChatMessage, restrictedChannel: Boolean = false): Boolean = targetChannel != sourceChannel &&
        ((source.kind == "internal" && targetSpaceId != source.id) || message.visibilityMode != "all" || restrictedChannel)

    /**
     * Where a forward or a reply can go (F5.20): chats whose [names] match [query], newest first,
     * without [exclude] (the chat a reply is already in).
     */
    fun <T> destinations(items: List<T>, query: String, names: (T) -> List<String?>, latest: (T) -> Long,
        exclude: (T) -> Boolean = { false }): List<T> {
        val q = query.trim()
        return items.filter { !exclude(it) && (q.isEmpty() || names(it).any { n -> n?.contains(q, ignoreCase = true) == true }) }
            .sortedByDescending(latest)
    }

    /**
     * The red card when a forward leaves where it was written (F5.20): "This leaves The Lanterns" /
     * "The Junipers and Driftwood will see it." [audience] is who else is in the target, else [fallback].
     */
    fun warning(source: String, audience: List<String>, fallback: String): Pair<String, String> {
        val names = audience.filter { it.isNotBlank() }.distinct()
        val who = when (names.size) {
            0 -> fallback
            1 -> names[0]
            else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
        }
        return "This leaves $source" to "$who will see it."
    }
}
