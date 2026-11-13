package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import uniffi.chorus_ffi.ticksFor

/** Other accounts' reads of our messages (D-131, NOTIFICATIONS §5.9): memory only, never stored.
 * Fetched when a shared or direct chat opens (`GET /channels/{id}/reads`) and kept up by the
 * socket's `read` frame. A [ReadMark] here names the reading account in `member`. Web: read-receipts.ts. */
object ReadReceipts {
    /** What a fetch says for one channel replaces what was held for it. */
    fun replace(held: Map<String, List<ReadMark>>, channel: String, reads: JSONArray?): Map<String, List<ReadMark>> {
        val marks = (0 until (reads?.length() ?: 0)).mapNotNull { i ->
            val r = reads?.optJSONObject(i) ?: return@mapNotNull null
            val account = r.optString("account_id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val id = r.optString("message_id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ReadMark(account, r.optLong("at"), id)
        }
        return if (marks.isEmpty()) held - channel else held + (channel to marks)
    }

    /** A live frame only ever moves a reader forward. */
    fun receive(held: Map<String, List<ReadMark>>, frame: JSONObject): Map<String, List<ReadMark>> {
        val channel = frame.optString("channel").takeIf { it.isNotEmpty() } ?: return held
        val account = frame.optString("account").takeIf { it.isNotEmpty() } ?: return held
        val id = frame.optString("message_id").takeIf { it.isNotEmpty() } ?: return held
        val next = ReadMark(account, frame.optLong("at"), id)
        val rest = held[channel].orEmpty()
        val cur = rest.firstOrNull { it.member == account }
        if (cur != null && ReadTracking.compare(cur.at, cur.id, next.at, next.id) >= 0) return held
        return held + (channel to (rest.filter { it.member != account } + next).sortedBy { it.member })
    }
}

/** Ticks on our own messages (D-131): `pending` (a clock) until the server has stamped it, `saved`
 * (one tick) once stored, `read` (two) once someone but its authors has read it. The rule is
 * chorus-core's (`fixtures/ticks`); this only feeds it. Web: ticks.ts. */
object Ticks {
    const val PENDING = "pending"
    const val SAVED = "saved"
    const val READ = "read"

    /** Our own, undeleted messages of a chat (`accountId` ours or still waiting for the server) with
     * their tick. [members] are our members' marks (with per-member reading on), [accounts] other
     * accounts' reads (with both switches on). */
    fun of(messages: List<ChatMessage>, accountId: String, members: List<ReadMark>, accounts: List<ReadMark>,
        core: (String, String, String) -> String = ::ticksFor): Map<String, String> {
        val own = messages.filter { it.accountId == null || it.accountId == accountId }
        if (own.isEmpty()) return emptyMap()
        val input = JSONArray().apply { for (m in own) put(JSONObject().put("at", m.occurredAt).put("id", m.id)
            .put("authors", JSONArray(m.authors)).put("acked", m.accountId != null)) }
        fun marks(list: List<ReadMark>) = JSONArray().apply { for (m in list) put(JSONObject()
            .put("member", m.member).put("at", m.at).put("id", m.id)) }.toString()
        val result = JSONArray(core(input.toString(), marks(members), marks(accounts)))
        return own.indices.associate { own[it].id to result.getString(it) }
    }
}
