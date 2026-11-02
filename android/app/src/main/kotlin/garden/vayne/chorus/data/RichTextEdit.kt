package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import uniffi.chorus_ffi.parseMarkupMapped

/** Editor-local character provenance. IDs belong to occurrences, never to visible labels.
 * State is JSON so the exact provenance survives the same saved-state lifecycle as the draft.
 * Parser source maps use UTF-16 in both directions, including escaped and formatted text.
 */
object RichTextEdit {
    // EntityKind's canonical order in core/text.rs, after offset and descending length.
    private val kinds = listOf("blockquote", "expandable_blockquote", "pre", "text_link", "bold", "italic",
        "underline", "strikethrough", "spoiler", "code", "url", "mention", "custom_emoji")
    private fun atomic(e: JSONObject) = e.optString("type") in setOf("mention", "custom_emoji")
    fun open(text: String, entities: String, markup: String): String {
        val parsed = JSONObject(parseMarkupMapped(markup, ""))
        require(parsed.getString("text") == text) { "This rich text could not be opened safely. Cancel and reopen it." }
        val origins = IntArray(markup.length) { -1 }
        val sources = parsed.getJSONArray("sources")
        for (i in 0 until sources.length()) origins[sources.getInt(i)] = i
        return JSONObject().put("markup", markup).put("text", text).put("entities", JSONArray(entities))
            .put("origins", JSONArray(origins.toList())).toString()
    }

    /** Selection disambiguates equal-text insertions/deletions; selected input is new text.
     * Explicit formatting carries original characters through its selected region. Myers handles
     * nearby changes; a linear-space LCS fallback also preserves bulk line formatting.
     */
    fun update(state: String, markup: String, selectionStart: Int? = null, selectionEnd: Int? = null, newCaret: Int? = null, preserveReplacement: Boolean = false): String {
        val draft = JSONObject(state)
        val old = draft.getString("markup")
        if (old == markup) return state
        val mapping = matches(old, markup, selectionStart, selectionEnd, newCaret, preserveReplacement)
        val origins = draft.getJSONArray("origins")
        return draft.put("markup", markup).put("origins", JSONArray(mapping.map { if (it < 0) -1 else origins.getInt(it) })).toString()
    }

    fun parse(state: String, markup: String, names: String): JSONObject {
        val draft = JSONObject(update(state, markup))
        val rich = JSONObject(parseMarkupMapped(markup, names))
        val text = rich.getString("text")
        val origins = draft.getJSONArray("origins")
        val sources = rich.getJSONArray("sources")
        val positions = mutableMapOf<Int, MutableList<Int>>()
        for (i in 0 until sources.length()) {
            val origin = origins.getInt(sources.getInt(i))
            if (origin >= 0) positions.getOrPut(origin) { mutableListOf() }.add(i)
        }
        val result = rich.getJSONArray("entities")
        val parsedEntities = List(result.length()) { result.getJSONObject(it) }
        val atoms = parsedEntities.filter(::atomic).sortedBy { it.getInt("offset") }
        val literals = parsedEntities.filter { it.optString("type") in setOf("code", "pre") }.sortedBy { it.getInt("offset") }
        fun overlaps(rows: List<JSONObject>, start: Int, end: Int): List<JSONObject> {
            // Parser atoms and literal code/pre spans are disjoint within their own lists.
            var low = 0; var high = rows.size
            while (low < high) {
                val middle = (low + high) / 2
                val row = rows[middle]
                if (row.getInt("offset") + row.getInt("length") <= start) low = middle + 1 else high = middle
            }
            val found = mutableListOf<JSONObject>()
            while (low < rows.size && rows[low].getInt("offset") < end) found.add(rows[low++])
            return found
        }
        val retained = mutableListOf<JSONObject>()
        val original = draft.getJSONArray("entities")
        val originalText = draft.getString("text")
        for (i in 0 until original.length()) {
            val entity = original.getJSONObject(i)
            if (!atomic(entity)) continue
            val at = entity.getInt("offset")
            val length = entity.getInt("length")
            if (at < 0 || length <= 0 || at.toLong() + length > originalText.length) continue
            fun boundary(s: String, index: Int) = index == 0 || index == s.length ||
                !(s[index - 1].isHighSurrogate() && s[index].isLowSurrogate())
            if (!boundary(originalText, at) || !boundary(originalText, at + length)) continue
            val start = positions[at]?.singleOrNull() ?: continue
            if ((0 until length).any { positions[at + it]?.singleOrNull() != start + it }) continue
            if (!boundary(text, start) || !boundary(text, start + length)) continue
            if (text.substring(start, start + length) != originalText.substring(at, at + length)) continue
            val rawStart = sources.getInt(start)
            var escapes = 0
            while (rawStart - escapes - 1 >= 0 && markup[rawStart - escapes - 1] == '\\') escapes++
            if (escapes % 2 == 1) continue
            if (entity.optString("type") == "mention") {
                fun word(at: Int): Boolean {
                    if (at !in text.indices) return false
                    val unit = if (text[at].isLowSurrogate() && at > 0 && text[at - 1].isHighSurrogate()) at - 1 else at
                    // core's is_alphanumeric: Unicode Alphabetic (letters, letter numbers like Ⅰ, marks like
                    // U+0345) or any number (², ½); isLetterOrDigit misses those
                    val cp = text.codePointAt(unit)
                    return Character.isAlphabetic(cp) || when (Character.getType(cp).toByte()) {
                        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
                        else -> false
                    }
                }
                if (word(start - 1)) continue
                var end = start + length
                while (end < text.length && (word(end) || text[end] in "_.-")) {
                    end += Character.charCount(text.codePointAt(end))
                }
                while (end > start + length && text[end - 1] in ".-") end--
                if (end > start + length) continue
            }
            // Explicit literal formatting removes semantic atoms; never resurrect stale spans in code.
            if (overlaps(literals, start, start + length).isNotEmpty()) continue
            val overlapping = overlaps(atoms, start, start + length)
            if (overlapping.any { it.getInt("offset") < start || it.getInt("offset") + it.getInt("length") > start + length }) continue
            retained.add(JSONObject(entity.toString()).put("offset", start))
        }
        val spans = retained.sortedBy { it.getInt("offset") }
        val merged = parsedEntities.filter { e ->
            !atomic(e) || overlaps(spans, e.getInt("offset"), e.getInt("offset") + e.getInt("length")).isEmpty()
        } + retained
        val sorted = merged
            .sortedWith(compareBy<JSONObject> { it.getInt("offset") }.thenByDescending { it.getInt("length") }.thenBy { kinds.indexOf(it.optString("type")) })
        return JSONObject().put("text", text).put("entities", JSONArray(sorted))
    }

    internal fun matches(old: String, new: String, selectionStart: Int?, selectionEnd: Int?, newCaret: Int? = null, preserveReplacement: Boolean = false): IntArray {
        val result = IntArray(new.length) { -1 }
        var prefix = 0
        var oldEnd = old.length
        var newEnd = new.length
        val a = selectionStart?.coerceIn(0, old.length)
        val b = selectionEnd?.coerceIn(0, old.length)
        // Prefer the actual selected replacement when its outside text is unchanged.
        val candidates = mutableListOf<Pair<Int, Int>>()
        if (a != null && b != null) {
            candidates.add(minOf(a, b) to maxOf(a, b))
            val removed = old.length - new.length
            if (a == b && removed > 0) {
                if (newCaret == a) candidates.add(a to (a + removed).coerceAtMost(old.length))
                else candidates.add((a - removed).coerceAtLeast(0) to a)
            }
        }
        val selected = candidates.firstOrNull { (start, end) ->
            new.length >= start + old.length - end && new.startsWith(old.substring(0, start)) && new.endsWith(old.substring(end))
        }
        if (selected != null && new.length >= selected.first + old.length - selected.second &&
            new.startsWith(old.substring(0, selected.first)) && new.endsWith(old.substring(selected.second))) {
            prefix = selected.first
            oldEnd = selected.second
            newEnd = new.length - (old.length - oldEnd)
        } else {
            while (prefix < minOf(old.length, new.length) && old[prefix] == new[prefix]) prefix++
            while (oldEnd > prefix && newEnd > prefix && old[oldEnd - 1] == new[newEnd - 1]) { oldEnd--; newEnd-- }
        }
        for (i in 0 until prefix) result[i] = i
        for (i in newEnd until new.length) result[i] = oldEnd + i - newEnd
        val n = oldEnd - prefix
        val m = newEnd - prefix
        // A selected TextField replacement is new input. Only an explicit formatting action
        // may carry provenance inside the selection; matching pasted labels are new tokens.
        if (n == 0 || m == 0 || (selected != null && !preserveReplacement)) return result
        val limit = minOf(n + m, 128)
        val offset = limit + 1
        val v = IntArray(2 * limit + 3)
        val trace = mutableListOf<IntArray>()
        for (d in 0..limit) {
            trace.add(v.copyOf())
            for (k in -d..d step 2) {
                var x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) v[offset + k + 1] else v[offset + k - 1] + 1
                var y = x - k
                while (x < n && y < m && old[prefix + x] == new[prefix + y]) { x++; y++ }
                v[offset + k] = x
                if (x >= n && y >= m) {
                    for (depth in d downTo 0) {
                        val previous = trace[depth]
                        val diagonal = x - y
                        val prevK = if (diagonal == -depth || (diagonal != depth && previous[offset + diagonal - 1] < previous[offset + diagonal + 1])) diagonal + 1 else diagonal - 1
                        val prevX = previous[offset + prevK]
                        val prevY = prevX - prevK
                        while (x > prevX && y > prevY) { x--; y--; result[prefix + y] = prefix + x }
                        x = prevX; y = prevY
                    }
                    return result
                }
            }
        }
        // Bulk formatting (e.g. quoting hundreds of lines) must not silently discard IDs when
        // its edit distance exceeds the fast path. Hirschberg uses linear memory, not an n*m table.
        fun lengths(a0: Int, a1: Int, b0: Int, b1: Int, reverse: Boolean): IntArray {
            val row = IntArray(b1 - b0 + 1)
            for (i in 0 until a1 - a0) {
                var previous = 0
                for (j in 1 until row.size) {
                    val diagonal = previous
                    previous = row[j]
                    val aChar = old[if (reverse) a1 - 1 - i else a0 + i]
                    val bChar = new[if (reverse) b1 - j else b0 + j - 1]
                    row[j] = if (aChar == bChar) diagonal + 1 else maxOf(row[j], row[j - 1])
                }
            }
            return row
        }
        fun align(a0: Int, a1: Int, b0: Int, b1: Int) {
            if (a0 == a1 || b0 == b1) return
            if (a1 - a0 == 1) {
                for (j in b0 until b1) if (old[a0] == new[j]) { result[j] = a0; break }
                return
            }
            val middle = (a0 + a1) / 2
            val left = lengths(a0, middle, b0, b1, false)
            val right = lengths(middle, a1, b0, b1, true)
            val split = (left.indices).maxBy { left[it] + right[right.lastIndex - it] }
            align(a0, middle, b0, b0 + split)
            align(middle, a1, b0 + split, b1)
        }
        align(prefix, oldEnd, prefix, newEnd)
        return result
    }
}
