package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

/** Immutable editor history. Only explicit Undo/Redo events can recover deleted provenance.
 * Original rich data is stored once; history is bounded by both frames and UTF-16 storage cost.
 * Input changes come from Compose's ChangeList, including same-text replacement and IME edits.
 */
data class RichEditFrame(val text: String, val origins: List<Int>, val start: Int = 0, val end: Int = start) {
    fun selection(start: Int, end: Int) = copy(start = start.coerceIn(0, text.length), end = end.coerceIn(0, text.length))
    // Conservative bound for UTF-8 JSON text escapes + worst-case one origin run per unit.
    internal val cost: Int get() = text.length * 24 + 64
    fun json(): JSONObject = JSONObject().put("text", text).put("start", start).put("end", end)
        .put("origins", JSONArray().also { runs ->
            var i = 0
            while (i < origins.size) {
                val at = i++
                while (i < origins.size && (if (origins[at] < 0) origins[i] < 0 else origins[i] == origins[at] + i - at)) i++
                runs.put(i - at).put(origins[at])
            }
        })
    companion object {
        fun decode(j: JSONObject): RichEditFrame {
            val text = j.getString("text")
            val runs = j.getJSONArray("origins")
            val origins = ArrayList<Int>(text.length)
            var i = 0
            while (i < runs.length()) {
                val count = runs.getInt(i++); val at = runs.getInt(i++)
                require(count > 0 && origins.size.toLong() + count <= text.length)
                repeat(count) { origins.add(if (at < 0) -1 else at + it) }
            }
            require(origins.size == text.length)
            return RichEditFrame(text, origins).selection(j.getInt("start"), j.getInt("end"))
        }
    }
}

data class RichInputChange(val oldStart: Int, val oldEnd: Int, val newStart: Int, val newEnd: Int)

data class RichEditHistory(val originalText: String, val originalEntities: String, val current: RichEditFrame,
    val past: List<RichEditFrame> = emptyList(), val future: List<RichEditFrame> = emptyList(), val opening: RichEditFrame = current) {
    val dirty: Boolean get() = current.text != opening.text || current.origins != opening.origins
    fun selection(start: Int, end: Int) = copy(current = current.selection(start, end))
    private fun advance(frame: RichEditFrame): RichEditHistory {
        val frames = (past + current).toMutableList()
        var cost = frame.cost.toLong() + frames.sumOf { it.cost.toLong() }
        while (frames.isNotEmpty() && (frames.size > MAX_UNDO || cost > MAX_HISTORY_BYTES)) cost -= frames.removeAt(0).cost
        return copy(current = frame, past = frames, future = emptyList())
    }

    /** Unchanged units outside actual replacement ranges retain their occurrence origins.
     * Even replacement with identical text invalidates the replaced units. No text-history guess.
     */
    fun input(text: String, oldStart: Int, oldEnd: Int, start: Int, end: Int, changes: List<RichInputChange>): RichEditHistory {
        val selected = selection(oldStart, oldEnd)
        if (changes.isEmpty()) return selected.selection(start, end)
        val origins = MutableList(text.length) { -1 }
        var oldAt = 0; var newAt = 0
        for (change in changes.sortedBy { it.newStart }) {
            require(change.oldStart >= oldAt && change.newStart >= newAt &&
                change.oldStart - oldAt == change.newStart - newAt)
            while (newAt < change.newStart) origins[newAt++] = current.origins[oldAt++]
            oldAt = change.oldEnd; newAt = change.newEnd
        }
        require(current.text.length - oldAt == text.length - newAt)
        while (newAt < text.length) origins[newAt++] = current.origins[oldAt++]
        return selected.advance(RichEditFrame(text, origins, start, end))
    }

    /** Toolbar supplies the exact map of retained units; bulk line formatting is linear. */
    fun format(text: String, start: Int, end: Int, mapping: IntArray): RichEditHistory {
        require(mapping.size == text.length)
        if (text == current.text) return selection(start, end)
        return advance(RichEditFrame(text, mapping.map { if (it < 0) -1 else current.origins[it] }, start, end))
    }
    fun undo(): RichEditHistory = if (past.isEmpty()) this else copy(current = past.last(), past = past.dropLast(1), future = future + current)
    fun redo(): RichEditHistory = if (future.isEmpty()) this else copy(current = future.last(), past = past + current, future = future.dropLast(1))

    fun parse(names: String): JSONObject = RichTextEdit.parse(legacyState(), current.text, names)
    fun legacyState(): String = JSONObject().put("text", originalText).put("entities", JSONArray(originalEntities))
        .put("markup", current.text).put("origins", JSONArray(current.origins)).toString()
    fun json(): JSONObject = JSONObject().put("originalText", originalText).put("originalEntities", JSONArray(originalEntities))
        .put("current", current.json()).put("opening", opening.json()).put("past", JSONArray(past.map { it.json() })).put("future", JSONArray(future.map { it.json() }))
    companion object {
        const val MAX_UNDO = 32
        const val MAX_HISTORY_BYTES = 1024 * 1024
        fun open(text: String, entities: String, markup: String): RichEditHistory {
            val old = JSONObject(RichTextEdit.open(text, entities, markup))
            val origins = old.getJSONArray("origins")
            return RichEditHistory(text, entities, RichEditFrame(markup, List(origins.length()) { origins.getInt(it) }))
        }
        fun decode(j: JSONObject): RichEditHistory {
            fun frames(key: String) = j.getJSONArray(key).let { a -> List(a.length()) { RichEditFrame.decode(a.getJSONObject(it)) } }
            return RichEditHistory(j.getString("originalText"), j.getJSONArray("originalEntities").toString(),
                RichEditFrame.decode(j.getJSONObject("current")), frames("past"), frames("future"), RichEditFrame.decode(j.getJSONObject("opening")))
        }
    }
}
