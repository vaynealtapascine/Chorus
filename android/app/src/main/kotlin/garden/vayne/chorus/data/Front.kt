package garden.vayne.chorus.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** How a quick-switch tap applies (CLIENTS.md §3.2). */
enum class Mode(val label: String) {
    Replace("Replace"), Add("Add"), Remove("Remove");

    fun next(): Mode = entries[(ordinal + 1) % entries.size]
}

/** The one pending undo (SPEC §4.3: offered 10 s; taps up to 30 s after the switch still count). */
data class Undo(val opId: String, val label: String, val at: Long)

/** Front actions shared by the app, the widget and the search launcher. */
object Front {
    private val _undo = MutableStateFlow<Undo?>(null)
    val undo: StateFlow<Undo?> = _undo

    /** Apply a tap on `subject` in `mode`. Returns the label for the undo row. */
    suspend fun tap(chorus: Chorus, s: Subject, mode: Mode): String {
        val entry = Entry(s.type, s.id, "front", mode == Mode.Replace)
        val (kind, payload, label) = when (mode) {
            Mode.Replace -> Triple("front.switch", JSONObject().put("entries", JSONArray().put(entry.toJson())), "Switched to ${s.name}")
            Mode.Add -> Triple("front.add", JSONObject().put("entry", entry.toJson()), "Added ${s.name}")
            Mode.Remove -> Triple(
                "front.remove",
                JSONObject().put("subject_type", s.type).put("subject_id", s.id),
                "${s.name} left",
            )
        }
        val id = chorus.create(kind, chorus.newId(), payload)
        _undo.value = Undo(id, label, System.currentTimeMillis())
        return label
    }

    /** Record a full switch (the switcher sheet). */
    suspend fun switch(chorus: Chorus, entries: List<Entry>, label: String, at: Long? = null) {
        val arr = JSONArray().also { a -> entries.forEach { a.put(it.toJson()) } }
        val id = chorus.create("front.switch", chorus.newId(), JSONObject().put("entries", arr), userTime = at)
        _undo.value = Undo(id, label, System.currentTimeMillis())
    }

    suspend fun undo(chorus: Chorus): Boolean {
        val u = _undo.value ?: return false
        if (System.currentTimeMillis() - u.at > 30_000) return false
        chorus.create("front.retract", chorus.newId(), JSONObject().put("target_op_id", u.opId))
        _undo.value = null
        return true
    }

    fun dismiss(opId: String) {
        if (_undo.value?.opId == opId) _undo.value = null
    }
}
