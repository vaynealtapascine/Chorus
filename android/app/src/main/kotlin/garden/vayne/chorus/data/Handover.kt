package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

/** One checklist line a handover was sent with (`handover.checklist`). */
data class ChecklistDef(val id: String, val text: String)

/** A handover's definition, fixed at sending (D-132): when it reaches the next front, and its checklist. */
data class HandoverDef(val deliver: String, val checklist: List<ChecklistDef> = emptyList()) {
    val nextSwitch: Boolean get() = deliver == "next_switch"

    companion object {
        fun parse(o: JSONObject?): HandoverDef? {
            o ?: return null
            val items = o.optJSONArray("checklist")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    a.optJSONObject(i)?.let { c -> ChecklistDef(c.optString("id"), c.optString("text")) }?.takeIf { it.id.isNotEmpty() }
                }
            }.orEmpty()
            return HandoverDef(o.optString("deliver", "now").ifEmpty { "now" }, items)
        }
    }
}
