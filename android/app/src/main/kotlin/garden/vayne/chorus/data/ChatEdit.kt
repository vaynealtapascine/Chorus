package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import uniffi.chorus_ffi.parseMarkup
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
    fun names(message: ChatMessage, model: Model): String {
        val mentions = JSONObject()
        for (member in model.members.filter { !it.archived }) {
            mentions.put(member.name.lowercase(), JSONObject().put("target_type", "member").put("target_id", member.id))
        }
        val emoji = JSONObject()
        val source = JSONArray(message.entities)
        for (i in 0 until source.length()) {
            val entity = source.getJSONObject(i)
            val at = entity.getInt("offset")
            val end = at + entity.getInt("length")
            if (at < 0 || end > message.text.length) continue
            val token = message.text.substring(at, end)
            when (entity.optString("type")) {
                "mention" -> if (token.startsWith("@")) mentions.put(token.drop(1).lowercase(),
                    JSONObject().put("target_type", entity.getString("target_type"))
                        .put("target_id", entity.optString("target_id").takeIf { it.isNotEmpty() } ?: JSONObject.NULL))
                "custom_emoji" -> if (token.startsWith(":") && token.endsWith(":"))
                    emoji.put(token.drop(1).dropLast(1), entity.getString("emoji_id"))
            }
        }
        return JSONObject().put("mentions", mentions).put("emoji", emoji).toString()
    }

    fun payload(message: ChatMessage, editedParts: List<String>, names: String,
        parse: (String, String) -> String = ::parseMarkup): JSONObject {
        val original = segments(message)
        require(editedParts.size == original.size) { "The message changed. Open Edit again." }
        val text = StringBuilder()
        val entities = JSONArray()
        val updated = JSONArray()
        for ((index, part) in editedParts.withIndex()) {
            if (index > 0) text.append('\n')
            val offset = text.length
            val rich = JSONObject(parse(part, names))
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
