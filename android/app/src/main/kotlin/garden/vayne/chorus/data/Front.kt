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
data class Undo(val opId: String, val label: String, val at: Long, val frontId: String? = null)

/** Front actions shared by the app, the widget and the search launcher. */
object Front {
    private val _undo = MutableStateFlow<Undo?>(null)
    val undo: StateFlow<Undo?> = _undo

    /** Apply a tap on `subject` in `mode`. Returns the label for the undo row. */
    suspend fun tap(chorus: Chorus, s: Subject, mode: Mode, frontId: String? = null): String {
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
        val target = frontId ?: chorus.device?.accountId?.takeIf { chorus.model.value.namedFronts != null }
        val id = if (target == null) chorus.create(kind, chorus.newId(), payload) else {
            val change = JSONObject().put("action", when (mode) {
                Mode.Replace -> "switch"; Mode.Add -> "add"; Mode.Remove -> "remove"
            })
            val presence = FrontPresence(entry.subjectType, entry.subjectId, entry.level, entry.isPrimary).toJson()
            when (mode) {
                Mode.Replace -> change.put("entries", JSONArray().put(presence))
                Mode.Add -> change.put("entry", presence)
                Mode.Remove -> change.put("subject_type", s.type).put("subject_id", s.id)
            }
            chorus.create("front.change", target, change)
        }
        _undo.value = Undo(id, label, System.currentTimeMillis(), target)
        return label
    }

    /** Record a full switch (the switcher sheet). */
    suspend fun switch(chorus: Chorus, entries: List<Entry>, label: String, at: Long? = null, note: String = "", notify: String = "default", frontId: String? = null, historyOnly: Boolean = false) {
        val target = frontId ?: chorus.device?.accountId?.takeIf { chorus.model.value.namedFronts != null }
        val id = if (target == null) chorus.create("front.switch", chorus.newId(), switchPayload(entries, note, notify), userTime = at)
            else chorus.create("front.change", target, changePayload(entries, note, notify, at, historyOnly))
        _undo.value = Undo(id, label, System.currentTimeMillis(), target)
    }

    fun switchPayload(entries: List<Entry>, note: String = "", notify: String = "default"): JSONObject = JSONObject()
        .put("entries", JSONArray().also { a -> entries.forEach { a.put(it.toJson()) } })
        .also { if (note.isNotBlank()) it.put("note", note.trim()) }
        .also { if (notify != "default") it.put("notify", notify) }

    fun changePayload(entries: List<Entry>, note: String = "", notify: String = "default", at: Long? = null, historyOnly: Boolean = false): JSONObject = JSONObject()
        .put("action", "switch")
        .put("entries", JSONArray().also { a -> entries.forEach { a.put(FrontPresence(it.subjectType, it.subjectId, it.level, it.isPrimary).toJson()) } })
        .also { if (note.isNotBlank()) it.put("note", note.trim()) }
        .also { if (notify != "default") it.put("notify", notify) }
        .also { if (at != null) it.put("effective_at", at) }
        .also { if (historyOnly) it.put("history_only", true) }

    suspend fun retract(chorus: Chorus, opId: String, redo: Boolean = false, frontId: String? = null) {
        val target = frontId ?: chorus.device?.accountId?.takeIf { chorus.model.value.namedFronts != null }
        if (target == null) chorus.create(if (redo) "front.unretract" else "front.retract", chorus.newId(), JSONObject().put("target_op_id", opId))
        else chorus.create("front.change", target, JSONObject().put("action", if (redo) "unretract" else "retract").put("target_op_id", opId))
        dismiss(opId)
    }

    suspend fun undo(chorus: Chorus): Boolean {
        val u = _undo.value ?: return false
        if (System.currentTimeMillis() - u.at > 30_000) return false
        retract(chorus, u.opId, frontId = u.frontId)
        _undo.value = null
        return true
    }

    fun dismiss(opId: String) {
        if (_undo.value?.opId == opId) _undo.value = null
    }
}
