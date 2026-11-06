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
data class Undo(val opId: String, val label: String, val at: Long, val frontId: String? = null,
    /** Further ops written by the same action (an Add-mode sheet places several people). */
    val more: List<String> = emptyList())

/** One subject's new layer in a front (null: out of it), and whether they are in it now. */
data class Placement(val type: String, val id: String, val layerId: String?, val present: Boolean, val primary: Boolean? = null)

/** Front actions shared by the app, the widget and the search launcher. */
object Front {
    private val _undo = MutableStateFlow<Undo?>(null)
    val undo: StateFlow<Undo?> = _undo

    /** Apply a tap on `subject` in `mode`. Returns the label for the undo row. */
    suspend fun tap(chorus: Chorus, s: Subject, mode: Mode, frontId: String? = null): String {
        val inner = chorus.model.value.namedFronts?.liveLayers?.firstOrNull()?.id ?: "front"
        val entry = Entry(s.type, s.id, inner, mode == Mode.Replace)
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
        u.more.forEach { retract(chorus, it, frontId = u.frontId) }
        retract(chorus, u.opId, frontId = u.frontId)
        _undo.value = null
        return true
    }

    suspend fun amendTime(chorus: Chorus, opId: String, at: Long, frontId: String? = null) {
        val target = frontId ?: chorus.device?.accountId?.takeIf { chorus.model.value.namedFronts != null }
        val payload = JSONObject().put("target_op_id", opId).put("occurred_at", at)
        if (target == null) chorus.create("front.amend", chorus.newId(), payload)
        else chorus.create("front.change", target, payload.put("action", "amend"))
    }

    fun dismiss(opId: String) {
        if (_undo.value?.opId == opId) _undo.value = null
    }

    /**
     * Put one subject on a layer of a front, or take them out of it (`layerId` null: Away; F3.3).
     * `present` says whether they are in the front now, so a move is an update, not a second entry.
     * Returns the op ID; the caller decides whether it is the undo.
     */
    suspend fun place(chorus: Chorus, type: String, id: String, layerId: String?, present: Boolean,
        frontId: String? = null, primary: Boolean? = null, at: Long? = null): String {
        val target = frontId ?: chorus.device?.accountId?.takeIf { chorus.model.value.namedFronts != null }
        if (target == null) {
            val (kind, payload) = when {
                layerId == null -> "front.remove" to JSONObject().put("subject_type", type).put("subject_id", id)
                present -> "front.update" to JSONObject().put("subject_type", type).put("subject_id", id).put("level", layerId)
                    .also { if (primary != null) it.put("is_primary", primary) }
                else -> "front.add" to JSONObject().put("entry", Entry(type, id, layerId, primary == true).toJson())
            }
            return chorus.create(kind, chorus.newId(), payload, userTime = at)
        }
        val change = when {
            layerId == null -> JSONObject().put("action", "remove").put("subject_type", type).put("subject_id", id)
            present -> JSONObject().put("action", "update").put("subject_type", type).put("subject_id", id).put("layer_id", layerId)
                .also { if (primary != null) it.put("is_primary", primary) }
            else -> JSONObject().put("action", "add").put("entry", FrontPresence(type, id, layerId, primary == true).toJson())
        }
        if (at != null) change.put("effective_at", at)
        return chorus.create("front.change", target, change)
    }

    /** Several placements as one undoable action (the Add-mode switch sheet). */
    suspend fun placeAll(chorus: Chorus, moves: List<Placement>, label: String, frontId: String? = null, at: Long? = null) {
        if (moves.isEmpty()) return
        val ids = moves.map { place(chorus, it.type, it.id, it.layerId, it.present, frontId, it.primary, at) }
        val target = frontId ?: chorus.device?.accountId?.takeIf { chorus.model.value.namedFronts != null }
        _undo.value = Undo(ids.last(), label, System.currentTimeMillis(), target, ids.dropLast(1))
    }

    /** One placement, offered for undo (the front card and the member sheet). */
    suspend fun placeOne(chorus: Chorus, move: Placement, label: String, frontId: String? = null) =
        placeAll(chorus, listOf(move), label, frontId)

    /** Sparse named-front definition edit (D-080); missing fields stay as they are. */
    suspend fun define(chorus: Chorus, frontId: String, patch: JSONObject) {
        chorus.create("front.define", frontId, patch)
    }

    /** System-wide layer edit, keyed by `layer_id`; the op's entity is the account. */
    suspend fun layer(chorus: Chorus, layerId: String, patch: JSONObject) {
        val account = checkNotNull(chorus.device?.accountId) { "Not signed in." }
        chorus.create("front.layer", account, JSONObject(patch.toString()).put("layer_id", layerId))
    }

    suspend fun resume(chorus: Chorus, frontId: String) {
        chorus.create("front.change", frontId, JSONObject().put("action", "resume"))
    }
}
