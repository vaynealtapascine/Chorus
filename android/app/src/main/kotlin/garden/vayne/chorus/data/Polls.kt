package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Polls (D-081, D-086, docs/POLLS.md). The definition rides on its parent message/post; a vote is
 * an account-scoped `poll.vote` (only this account's devices see it); totals are the server's
 * counts-only tally. Nothing here can name another account's voters.
 */
data class PollOption(val id: String, val text: String)
data class Poll(val question: String?, val options: List<PollOption>, val multiple: Boolean)
data class PollTally(val counts: Map<String, Long>, val voters: Long)

data class PollState(
    val polls: Map<String, Poll> = emptyMap(),
    val tallies: Map<String, PollTally> = emptyMap(),
    /** `<parent>|<member>` → choices, own account only. */
    val votes: Map<String, List<String>> = emptyMap(),
) {
    fun choices(parentId: String, member: String?): List<String> =
        member?.let { votes["$parentId|$it"] }.orEmpty()
}

object Polls {
    const val MAX_OPTIONS = 20

    private fun rows(p: JSONObject, table: String): Sequence<Pair<String, JSONObject>> {
        val t = p.optJSONObject("rows")?.optJSONObject(table) ?: return emptySequence()
        return t.keys().asSequence().mapNotNull { id ->
            val r = t.getJSONObject(id)
            if (r.optBoolean("exists")) id to r.getJSONObject("fields") else null
        }
    }

    fun parse(v: JSONObject?): Poll? {
        val list = v?.optJSONArray("options") ?: return null
        val options = (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id"); val text = o.optString("text")
            if (id.isEmpty() || text.isEmpty()) null else PollOption(id, text)
        }
        if (options.size < 2) return null
        val q = if (v.has("question") && !v.isNull("question")) v.optString("question") else null
        return Poll(q, options, v.optBoolean("multiple"))
    }

    fun tally(v: JSONObject?): PollTally? {
        val counts = v?.optJSONObject("counts") ?: return null
        return PollTally(counts.keys().asSequence().associateWith { counts.optLong(it) }, v.optLong("voters"))
    }

    fun fromProjection(p: JSONObject): PollState {
        val polls = HashMap<String, Poll>()
        for (table in listOf("message", "post")) {
            for ((id, f) in rows(p, table)) parse(f.optJSONObject("poll"))?.let { polls[id] = it }
        }
        val tallies = rows(p, "poll_tally").mapNotNull { (id, f) -> tally(f)?.let { id to it } }.toMap()
        val votes = rows(p, "poll_vote").associate { (id, f) ->
            val a = f.optJSONArray("choices") ?: JSONArray()
            id to List(a.length()) { a.getString(it) }
        }
        return PollState(polls, tallies, votes)
    }

    /** Next choices after tapping [option]: single choice replaces (or clears), multiple toggles. */
    fun toggled(poll: Poll, current: List<String>, option: String): List<String> = when {
        option in current -> current - option
        poll.multiple -> current + option
        else -> listOf(option)
    }

    fun votePayload(member: String, choices: List<String>): JSONObject =
        JSONObject().put("member_id", member).put("choices", JSONArray(choices))

    /** The `poll` payload for a composer draft (ids a, b, c…), or an error message. */
    fun draft(texts: List<String>, multiple: Boolean): Result<JSONObject> {
        val options = texts.map { it.trim() }.filter { it.isNotEmpty() }
        if (options.size < 2) return Result.failure(IllegalArgumentException("A poll needs at least two options."))
        if (options.size > MAX_OPTIONS) return Result.failure(IllegalArgumentException("A poll can have at most $MAX_OPTIONS options."))
        if (options.any { it.codePointCount(0, it.length) > 200 }) {
            return Result.failure(IllegalArgumentException("Each option can be at most 200 characters."))
        }
        val list = JSONArray()
        options.forEachIndexed { i, text -> list.put(JSONObject().put("id", ('a' + i).toString()).put("text", text)) }
        return Result.success(JSONObject().put("options", list).put("multiple", multiple))
    }
}
