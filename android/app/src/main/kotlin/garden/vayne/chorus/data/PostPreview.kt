package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

data class PostPreview(val text: String, val entities: String, val truncated: Boolean)
object PostPreviews {
    fun of(text: String, entities: String, limit: Int = 300): PostPreview {
        var end = 0
        var count = 0
        while (end < text.length && count < limit) {
            end += Character.charCount(text.codePointAt(end))
            count++
        }
        val clipped = JSONArray()
        val source = runCatching { JSONArray(entities) }.getOrElse { JSONArray() }
        for (i in 0 until source.length()) {
            val e = source.optJSONObject(i) ?: continue
            val start = e.optInt("offset", -1); val length = e.optInt("length")
            if (start < 0 || start >= end || length <= 0) continue
            if (e.optString("type") in setOf("mention", "custom_emoji") && start.toLong() + length > end) continue
            clipped.put(JSONObject(e.toString()).put("length", minOf(length, end - start)))
        }
        return PostPreview(text.substring(0, end), clipped.toString(), end < text.length)
    }
}
