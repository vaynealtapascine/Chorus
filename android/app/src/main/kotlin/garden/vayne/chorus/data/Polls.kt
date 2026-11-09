package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Polls (D-081, D-086, docs/POLLS.md). The definition rides on its parent message/post; a vote is
 * an account-scoped `poll.vote` (only this account's devices see it); totals are the server's
 * counts-only tally. Nothing here can name another account's voters.
 */
data class PollOption(val id: String, val text: String)
data class Poll(val question: String?, val options: List<PollOption>, val multiple: Boolean,
    /** When it stops taking votes (D-136): its closing time, or an earlier close a REST row reported. */
    val closesAt: Long? = null)
data class PollTally(val counts: Map<String, Long>, val voters: Long)

data class PollState(
    val polls: Map<String, Poll> = emptyMap(),
    val tallies: Map<String, PollTally> = emptyMap(),
    /** `<parent>|<member>` → choices, own account only. */
    val votes: Map<String, List<String>> = emptyMap(),
    /** The author's early closes (`*.poll_close`, D-136): parent → when. */
    val closes: Map<String, Long> = emptyMap(),
) {
    /** When [poll] on [parentId] stops taking votes: its closing time or the early close, whichever is first. */
    fun closedAt(parentId: String, poll: Poll): Long? = listOfNotNull(poll.closesAt, closes[parentId]).minOrNull()

    fun choices(parentId: String, member: String?): List<String> =
        member?.let { votes["$parentId|$it"] }.orEmpty()
}

object Polls {
    const val MAX_OPTIONS = 20

    /** The active acting member supplied by the chat composer, or no voter. */
    fun chatMember(model: Model, actingMemberId: String?): String? =
        actingMemberId?.takeIf { id -> model.active.any { it.id == id } }

    private fun rows(p: JSONObject, table: String): Sequence<Pair<String, JSONObject>> {
        val t = p.optJSONObject("rows")?.optJSONObject(table) ?: return emptySequence()
        return t.keys().asSequence().mapNotNull { id ->
            val r = t.getJSONObject(id)
            if (r.optBoolean("exists")) id to r.getJSONObject("fields") else null
        }
    }

    /** [closedAt]: a REST row's `poll_closed_at` (the author's early close), if any. */
    fun parse(v: JSONObject?, closedAt: Long? = null): Poll? {
        val list = v?.optJSONArray("options") ?: return null
        val options = (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id"); val text = o.optString("text")
            if (id.isEmpty() || text.isEmpty()) null else PollOption(id, text)
        }
        if (options.size < 2) return null
        val q = if (v.has("question") && !v.isNull("question")) v.optString("question") else null
        val closes = v.optLong("closes_at").takeIf { it > 0 && !v.isNull("closes_at") }
        return Poll(q, options, v.optBoolean("multiple"), listOfNotNull(closes, closedAt?.takeIf { it > 0 }).minOrNull())
    }

    /** A REST post or message row's poll, with its early close folded in. */
    fun parseRow(row: JSONObject): Poll? =
        parse(row.optJSONObject("poll"), row.optLong("poll_closed_at").takeIf { it > 0 && !row.isNull("poll_closed_at") })

    /** "Closed", "Closes in 40 min", "Closes in 3 h", "Closes in 2 days"; null for a poll without a close. */
    fun closing(closedAt: Long?, now: Long): String? {
        val at = closedAt ?: return null
        val left = at - now
        if (left <= 0) return "Closed"
        val minutes = (left + 59_999) / 60_000
        val hours = minutes / 60; val days = hours / 24
        return when {
            minutes < 60 -> "Closes in $minutes min"
            hours < 24 -> "Closes in $hours h"
            else -> "Closes in $days ${if (days == 1L) "day" else "days"}"
        }
    }

    /** The composer's closing-time value: "Fri 18:00" this week, else "14 Nov, 18:00". */
    fun closingTime(at: Long, now: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
        val time = java.time.Instant.ofEpochMilli(at).atZone(zone)
        val soon = at - now < 6 * 24 * 3_600_000L
        return time.format(java.time.format.DateTimeFormatter.ofPattern(if (soon) "EEE HH:mm" else "d MMM, HH:mm"))
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
        val closes = rows(p, "poll_close").mapNotNull { (id, f) -> f.optLong("closed_at").takeIf { it > 0 }?.let { id to it } }.toMap()
        return PollState(polls, tallies, votes, closes)
    }

    /** An option's share of the voters, as the whole percent a poll card shows. */
    fun percent(count: Long, voters: Long): Int =
        if (voters <= 0) 0 else Math.round(count * 100.0 / voters).toInt().coerceIn(0, 100)

    /** Each of our [members] with what they chose in [parentId]; own account only (D-081, D-086). */
    fun votesOf(state: PollState, parentId: String, members: List<Member>): List<Pair<Member, List<String>>> =
        members.map { it to state.choices(parentId, it.id) }

    /** How many of [members] have voted: "2 of 4 voted". */
    fun voted(state: PollState, parentId: String, members: List<Member>): Int =
        members.count { state.choices(parentId, it.id).isNotEmpty() }

    /** Next choices after tapping [option]: single choice replaces (or clears), multiple toggles. */
    fun toggled(poll: Poll, current: List<String>, option: String): List<String> = when {
        option in current -> current - option
        poll.multiple -> current + option
        else -> listOf(option)
    }

    fun votePayload(member: String, choices: List<String>): JSONObject =
        JSONObject().put("member_id", member).put("choices", JSONArray(choices))

    /** The `poll` payload for a composer draft (ids a, b, c…), or an error message. [closesAt]:
     * when it stops taking votes (D-136), fixed once posted. */
    fun draft(texts: List<String>, multiple: Boolean, closesAt: Long? = null, now: Long = System.currentTimeMillis()): Result<JSONObject> {
        val options = texts.map { it.trim() }.filter { it.isNotEmpty() }
        if (options.size < 2) return Result.failure(IllegalArgumentException("A poll needs at least two options."))
        if (options.size > MAX_OPTIONS) return Result.failure(IllegalArgumentException("A poll can have at most $MAX_OPTIONS options."))
        if (options.any { it.codePointCount(0, it.length) > 200 }) {
            return Result.failure(IllegalArgumentException("Each option can be at most 200 characters."))
        }
        val list = JSONArray()
        options.forEachIndexed { i, text -> list.put(JSONObject().put("id", ('a' + i).toString()).put("text", text)) }
        if (closesAt != null && closesAt <= now) return Result.failure(IllegalArgumentException("Pick a closing time that hasn't passed."))
        val poll = JSONObject().put("options", list).put("multiple", multiple)
        if (closesAt != null) poll.put("closes_at", closesAt)
        return Result.success(poll)
    }
}
