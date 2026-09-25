package garden.vayne.chorus.data

import org.json.JSONArray

/** One local message the server asked this device to send later (D-076). */
data class HeldMessage(val opId: String, val messageId: String, val until: Long)

object HeldMessages {
    fun parse(json: String): List<HeldMessage> {
        val rows = JSONArray(json)
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val op = row.optString("id")
            val entity = if (row.isNull("entity_id")) "" else row.optString("entity_id")
            if (op.isBlank() || entity.isBlank() || !row.has("until")) null
            else HeldMessage(op, entity, row.getLong("until"))
        }.sortedWith(compareBy<HeldMessage> { it.until }.thenBy { it.opId })
    }

    /** A held message does not keep a bounded background sync lease open for its full wait. */
    fun caughtUp(pending: ULong, held: Int): Boolean = pending == held.toULong()
}
