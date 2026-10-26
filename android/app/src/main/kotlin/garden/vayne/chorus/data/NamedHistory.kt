package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

/** Saved entry IDs can be edited. Effective IDs identify inherited transitions, never undo targets. */
data class FrontCause(val opId: String, val frontId: String, val recordedTimeEstimated: Boolean)
data class EffectiveFrontChange(val id: String, val occurredAt: Long, val entries: List<FrontPresence>,
    val source: String?, val state: String, val issue: String?, val deleted: Boolean, val causes: List<FrontCause>)

data class NamedFrontInterval(val id: String, val frontId: String, val presence: FrontPresence,
    val position: Int, val startAt: Long, val endAt: Long?, val startSwitchId: String,
    val endSwitchId: String?, val startTzOffsetMin: Int) {
    fun toJson(): JSONObject = presence.toJson().put("id", id).put("front_id", frontId)
        .put("position", position).put("start_at", startAt).put("end_at", endAt ?: JSONObject.NULL)
        .put("start_switch_id", startSwitchId).put("end_switch_id", endSwitchId ?: JSONObject.NULL)
        .put("start_tz_offset_min", startTzOffsetMin)
}

data class NamedHistory(val frontId: String, val saved: List<SwitchRow>,
    val ownIntervals: List<NamedFrontInterval>, val changes: List<EffectiveFrontChange>,
    val intervals: List<NamedFrontInterval>) {
    companion object {
        private fun string(o: JSONObject, key: String): String? =
            if (o.has(key) && !o.isNull(key)) o.getString(key) else null
        private fun <T> rows(a: JSONArray?, parse: (JSONObject) -> T): List<T> =
            if (a == null) emptyList() else List(a.length()) { parse(a.getJSONObject(it)) }
        private fun interval(o: JSONObject): NamedFrontInterval = NamedFrontInterval(
            o.getString("id"), o.getString("front_id"),
            FrontPresence(o.getString("subject_type"), o.getString("subject_id"), o.getString("layer_id"), o.optBoolean("is_primary")),
            o.getInt("position"), o.getLong("start_at"), if (o.isNull("end_at")) null else o.getLong("end_at"),
            o.getString("start_switch_id"), string(o, "end_switch_id"), o.getInt("start_tz_offset_min"))

        fun parse(front: String, own: JSONObject, effective: JSONObject): NamedHistory {
            val history = own.optJSONObject("histories")?.optJSONObject(front)
            val saved = rows(history?.optJSONArray("switches")) { s -> SwitchRow(
                s.getString("id"), s.getString("kind"), s.getLong("occurred_at"),
                NamedFronts.presence(s.optJSONArray("entries")).map { it.displayEntry() },
                NamedFronts.presence(s.optJSONArray("resulting_front")).map { it.displayEntry() },
                string(s, "note"), s.getBoolean("retracted")) }
            val changes = rows(effective.optJSONArray("changes")) { c -> EffectiveFrontChange(
                c.getString("id"), c.getLong("occurred_at"), NamedFronts.presence(c.optJSONArray("entries")),
                string(c, "source"), c.getString("state"), string(c, "issue"), c.getBoolean("deleted"),
                rows(c.optJSONArray("causes")) { FrontCause(it.getString("op_id"), it.getString("front_id"), it.getBoolean("recorded_time_estimated")) }) }
            return NamedHistory(front, saved, rows(history?.optJSONArray("intervals"), ::interval),
                changes, rows(effective.optJSONArray("intervals"), ::interval))
        }
    }
}

/** Store-thread cache. Its revision changes only for front dependencies, including a past
 * correction whose current presence compares equal. No history fold runs during Model.parse. */
class NamedHistoryCache {
    private var account = ""
    private var revision: Long? = null
    private var own: JSONObject? = null
    private val fronts = HashMap<String, NamedHistory>()

    fun read(accountId: String, atRevision: Long, frontId: String, readOwn: () -> String,
        readEffective: (String) -> String): NamedHistory {
        if (account != accountId || revision != atRevision) {
            account = accountId; revision = atRevision; own = null; fronts.clear()
        }
        return fronts.getOrPut(frontId) {
            val saved = own ?: JSONObject(readOwn()).also { own = it }
            NamedHistory.parse(frontId, saved, JSONObject(readEffective(frontId)))
        }
    }
}
