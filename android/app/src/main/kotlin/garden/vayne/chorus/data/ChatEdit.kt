package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import uniffi.chorus_ffi.toMarkup

/** Rebuild an edit without changing which members authored each segment. Offsets are UTF-16. */
object ChatEdit {
    private fun segments(message: ChatMessage): List<ChatSegment> = message.segments.ifEmpty {
        listOf(ChatSegment(0, message.text.length, message.authors))
    }

    private fun rich(message: ChatMessage, segment: ChatSegment): JSONObject {
        require(segment.offset >= 0 && segment.length >= 0 && segment.offset + segment.length <= message.text.length)
        val entities = JSONArray()
        val source = JSONArray(message.entities)
        for (i in 0 until source.length()) {
            val entity = source.getJSONObject(i)
            val at = entity.getInt("offset")
            val end = at + entity.getInt("length")
            if (at >= segment.offset && end <= segment.offset + segment.length) {
                entities.put(JSONObject(entity.toString()).put("offset", at - segment.offset))
            }
        }
        return JSONObject().put("text", message.text.substring(segment.offset, segment.offset + segment.length))
            .put("entities", entities)
    }

    fun parts(message: ChatMessage, markup: (String) -> String = ::toMarkup): List<String> =
        segments(message).map { markup(rich(message, it).toString()) }

    /** Include names from the original entities so existing mentions and emoji still resolve. */
    fun names(message: ChatMessage, model: Model): String = names(message.text, message.entities, model)

    /** Also used by profile bios: editing nearby text must retain existing mention/emoji IDs. */
    fun names(text: String, entities: String, model: Model): String {
        val mentions = JSONObject()
        val emoji = JSONObject()
        // Only unambiguous legacy labels supplement the live directory for newly typed tokens.
        // Existing identities are retained separately by RichTextEdit's occurrence provenance.
        val candidates = mutableMapOf<String, MutableSet<String>>()
        val source = JSONArray(entities)
        for (i in 0 until source.length()) {
            val entity = source.getJSONObject(i)
            val at = entity.getInt("offset")
            val length = entity.getInt("length")
            if (at < 0 || length <= 0 || at.toLong() + length > text.length) continue
            val token = text.substring(at, at + length)
            if (entity.optString("type") == "mention" && token.startsWith("@")) {
                val value = JSONObject().put("target_type", entity.getString("target_type"))
                    .put("target_id", entity.opt("target_id") ?: JSONObject.NULL).toString()
                candidates.getOrPut("m:" + token.drop(1).lowercase()) { mutableSetOf() }.add(value)
            } else if (entity.optString("type") == "custom_emoji" && token.startsWith(":") && token.endsWith(":")) {
                candidates.getOrPut("e:" + token.drop(1).dropLast(1)) { mutableSetOf() }.add(entity.getString("emoji_id"))
            }
        }
        for ((label, targets) in candidates) if (targets.size == 1) {
            if (label.startsWith("m:")) mentions.put(label.drop(2), JSONObject(targets.single()))
            else emoji.put(label.drop(2), targets.single())
        }
        for (member in model.members.filter { !it.archived }.sortedByDescending { it.id }) {
            mentions.put(member.name.lowercase(), JSONObject().put("target_type", "member").put("target_id", member.id))
        }
        return JSONObject().put("mentions", mentions).put("emoji", emoji).toString()
    }

    fun drafts(message: ChatMessage): List<String> = segments(message).map { segment ->
        val source = rich(message, segment)
        RichTextEdit.open(source.getString("text"), source.getJSONArray("entities").toString(), toMarkup(source.toString()))
    }

    fun payload(message: ChatMessage, editedParts: List<String>, names: String,
        states: List<String>? = null,
        parse: ((String, String) -> String)? = null): JSONObject {
        val original = segments(message)
        require(editedParts.size == original.size) { "The message changed. Open Edit again." }
        val text = StringBuilder()
        val entities = JSONArray()
        val updated = JSONArray()
        for ((index, part) in editedParts.withIndex()) {
            if (index > 0) text.append('\n')
            val offset = text.length
            val rich = if (parse == null) RichTextEdit.parse((states ?: drafts(message))[index], part, names)
                else JSONObject(parse(part, names))
            val content = rich.getString("text")
            text.append(content)
            val fields = rich.getJSONArray("entities")
            for (i in 0 until fields.length()) {
                val entity = JSONObject(fields.getJSONObject(i).toString())
                entities.put(entity.put("offset", entity.getInt("offset") + offset))
            }
            updated.put(JSONObject().put("offset", offset).put("length", content.length)
                .put("authors", JSONArray(original[index].authors)))
        }
        require(text.isNotBlank()) { "Write a message first." }
        return JSONObject().put("message_id", message.id).put("text", text.toString())
            .put("entities", entities).put("segments", updated)
    }
}
