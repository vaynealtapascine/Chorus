package garden.vayne.chorus.data

import org.json.JSONArray

data class BodySpan(val start: Int, val end: Int, val type: String, val url: String?)
data class PreparedBody(val text: String, val spans: List<BodySpan>, val hidden: Set<Int>)

object RichTextPresentation {
    /** Accessibility and copy receive the same safe string as the screen. Nested links in a
     * hidden spoiler are removed. Imported malformed UTF-16 ranges cannot reach the renderer. */
    fun prepare(text: String, entities: String, revealed: List<Int>): PreparedBody {
        val array = runCatching { JSONArray(entities) }.getOrElse { JSONArray() }
        val ranges = (0 until array.length()).mapNotNull { n ->
            val e = array.optJSONObject(n) ?: return@mapNotNull null
            val start = e.optInt("offset", -1)
            val length = e.optInt("length")
            if (start < 0 || length <= 0 || start > text.length - length) return@mapNotNull null
            BodySpan(start, start + length, e.optString("type"), e.optString("url"))
        }
        val hidden = ranges.filter { it.type == "spoiler" && it.start !in revealed }
        val safe = text.toCharArray()
        for (r in hidden) for (i in r.start until r.end) if (!safe[i].isWhitespace()) safe[i] = '▪'
        val visible = ranges.filter { r -> r.type == "spoiler" || hidden.none { it.start < r.end && it.end > r.start } }
        return PreparedBody(String(safe), visible, hidden.map { it.start }.toSet())
    }
}
