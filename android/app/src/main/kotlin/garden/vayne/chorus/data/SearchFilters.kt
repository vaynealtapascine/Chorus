package garden.vayne.chorus.data

/**
 * The search page's filter chips (Figma F5.9: "From: any member · In: all chats · Any date"):
 * each one writes the matching core operator (`from:`, `in:`, `after:`) into the query, so typing
 * and tapping mean the same thing and the parser stays the only judge (SPEC §5.3).
 */
object SearchFilters {
    /** The date chip's choices: an `after:` age, null for any date. */
    val DATES: List<Pair<String, String?>> = listOf(
        "Any date" to null, "Past day" to "1d", "Past week" to "7d", "Past month" to "30d", "Past year" to "1y")

    /** The query's parts as the core splits them: words, `key:value`, `key:"quoted value"`. */
    private fun parts(query: String): List<String> {
        val out = ArrayList<String>()
        val part = StringBuilder()
        var quoted = false
        for (c in query) {
            if (c == '"') quoted = !quoted
            if (c.isWhitespace() && !quoted) { if (part.isNotEmpty()) out += part.toString(); part.clear() }
            else part.append(c)
        }
        if (part.isNotEmpty()) out += part.toString()
        return out
    }

    private fun keyOf(part: String): String? = part.substringBefore(':', "").lowercase().takeIf { part.contains(':') && it.isNotEmpty() }

    /** The value of the first `key:` in [query], unquoted; null when there is none. */
    fun value(query: String, key: String): String? =
        parts(query).firstOrNull { keyOf(it) == key }?.substringAfter(':')?.replace("\"", "")?.takeIf { it.isNotEmpty() }

    /** [query] with every `key:` replaced by one `key:value` at the end (quoted when it has spaces), or removed for null. */
    fun set(query: String, key: String, value: String?): String {
        val kept = parts(query).filter { keyOf(it) != key }
        val clean = value?.replace("\"", "")?.trim()?.takeIf { it.isNotEmpty() }
        val token = clean?.let { if (it.any(Char::isWhitespace)) "$key:\"$it\"" else "$key:$it" }
        return (kept + listOfNotNull(token)).joinToString(" ")
    }

    /** What the date chip says for [query]. */
    fun dateLabel(query: String): String = value(query, "after")?.let { v -> DATES.firstOrNull { it.second == v }?.first ?: "After $v" } ?: "Any date"

    /** [query] without its operators: what a tab that isn't messages (Chats, Media, Files, Links) matches by. */
    fun words(query: String): String = parts(query).filter { keyOf(it) !in setOf("from", "in", "has", "before", "after", "is") }
        .joinToString(" ") { it.replace("\"", "") }

    /** Whether every word of [words] is in one of [names], ignoring case. */
    fun matches(words: String, vararg names: String?): Boolean =
        words.split(' ').filter { it.isNotBlank() }.all { w -> names.any { it?.contains(w, ignoreCase = true) == true } }
}
