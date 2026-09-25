package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import uniffi.chorus_ffi.readReaders
import uniffi.chorus_ffi.readUnseenBy

data class ReadMark(val member: String, val at: Long, val id: String)

/** Account-local read positions. Pending marks have an empty account id in the projection. */
object ReadTracking {
    fun marks(p: JSONObject, accountId: String): Map<String, List<ReadMark>> {
        val rows = p.optJSONObject("rows")?.optJSONObject("read_state") ?: return emptyMap()
        val byChannel = HashMap<String, MutableMap<String, ReadMark>>()
        for (key in rows.keys()) {
            val parts = key.split('|', limit = 3)
            if (parts.size != 3 || parts[1] != accountId && parts[1].isNotEmpty()) continue
            val row = rows.optJSONObject(key)?.takeIf { it.optBoolean("exists") } ?: continue
            val fields = row.optJSONObject("fields") ?: continue
            val mark = ReadMark(parts[2], fields.optLong("last_read_message_at"),
                fields.optString("last_read_message_id"))
            val channel = byChannel.getOrPut(parts[0]) { HashMap() }
            val prior = channel[mark.member]
            if (prior == null || compare(mark.at, mark.id, prior.at, prior.id) > 0)
                channel[mark.member] = mark
        }
        return byChannel.mapValues { (_, marks) -> marks.values.sortedBy { it.member } }
    }

    fun readers(perMember: Boolean, current: List<Entry>, core: (Boolean, String) -> String = ::readReaders): List<String> {
        val front = JSONArray().apply {
            for (entry in current.filter { it.subjectType == "member" }) put(JSONObject()
                .put("member_id", entry.subjectId).put("is_primary", entry.isPrimary).put("level", entry.level))
        }
        val result = JSONArray(core(perMember, front.toString()))
        return (0 until result.length()).map { result.getString(it) }
    }

    fun unseen(at: Long, id: String, marks: List<ReadMark>,
        core: (Long, String, String) -> String = ::readUnseenBy): List<String> {
        if (marks.none { it.member.isNotEmpty() }) return emptyList()
        val input = JSONArray().apply { for (mark in marks) put(JSONObject()
            .put("member", mark.member).put("at", mark.at).put("id", mark.id)) }
        val result = JSONArray(core(at, id, input.toString()))
        return (0 until result.length()).map { result.getString(it) }
    }

    fun needsMark(at: Long, id: String, marks: List<ReadMark>, member: String): Boolean {
        val last = marks.firstOrNull { it.member == member } ?: return true
        return compare(at, id, last.at, last.id) > 0
    }

    private fun compare(at: Long, id: String, otherAt: Long, otherId: String): Int =
        at.compareTo(otherAt).takeIf { it != 0 } ?: id.compareTo(otherId)
}
