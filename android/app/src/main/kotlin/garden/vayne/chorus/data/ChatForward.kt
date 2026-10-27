package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

/** One forwarded message as its recipients see it: the snapshot, not the original (SPEC §5.3). */
data class ForwardItem(val messageId: String, val text: String, val channelName: String?,
    val authors: List<String>, val occurredAt: Long)

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
            authors, o.optLong("occurred_at"))
    }

    private fun attachment(a: ChatAttachment): JSONObject = JSONObject().put("id", a.id).put("blob_hash", a.blobHash)
        .put("filename", a.filename).put("mime", a.mime).put("size", a.size).put("alt_text", a.altText)
        .put("is_spoiler", a.spoiler).apply { a.thumbHash?.let { put("thumb_blob_hash", it) } }

    /** The whole message as web `snapshot()` writes it. */
    fun snapshot(message: ChatMessage, channelName: String): JSONObject = JSONObject()
        .put("message_id", message.id).put("channel_name", channelName)
        .put("authors", JSONArray(message.authors)).put("text", message.text)
        .put("entities", runCatching { JSONArray(message.entities) }.getOrElse { JSONArray() })
        .put("occurred_at", message.occurredAt)
        .put("attachments", JSONArray(message.attachments.map(::attachment)))
        .put("offset", 0).put("length", message.text.length)

    /** The copied attachment record for the target scope (same blob, new id). */
    fun attachmentCopy(a: ChatAttachment): JSONObject = JSONObject().put("blob_hash", a.blobHash)
        .put("filename", a.filename).put("mime", a.mime).put("size", a.size).put("alt_text", a.altText)
        .put("is_spoiler", a.spoiler).apply { a.thumbHash?.let { put("thumb_blob_hash", it) } }

    fun payload(targetChannel: String, speaker: String, message: ChatMessage, channelName: String,
        attachmentIds: List<String>): JSONObject = JSONObject()
        .put("channel_id", targetChannel).put("authors", JSONArray(listOf(speaker)))
        .put("text", "").put("entities", JSONArray())
        .put("forward_of_id", message.id)
        .put("forward_snapshot", JSONArray(listOf(snapshot(message, channelName))))
        .apply { if (attachmentIds.isNotEmpty()) put("attachments", JSONArray(attachmentIds)) }

    /**
     * Forwarding member-visible, locked or internal content outward asks once (SPEC §5.3):
     * internal → anywhere else, or any message that isn't visible to everyone where it was written.
     */
    fun asksFirst(source: ChatSpace, targetSpaceId: String, targetChannel: String, sourceChannel: String,
        message: ChatMessage): Boolean = targetChannel != sourceChannel &&
        ((source.kind == "internal" && targetSpaceId != source.id) || message.visibilityMode != "all")
}
