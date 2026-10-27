package garden.vayne.chorus.data

import org.json.JSONObject

/** Socket-only data, absent from the replica and encrypted store. until is server wall time. */
data class TypingEntry(val account: String, val member: String?, val until: Long)

data class TypingNames(val accountId: String = "", val accounts: Map<String, String> = emptyMap(),
    val visibleAuthors: Map<String, ForeignAuthor> = emptyMap())

/** Pure, deterministic TTL handling; callers supply server time. */
object TypingExpiry {
    fun expire(channels: Map<String, List<TypingEntry>>, now: Long): Map<String, List<TypingEntry>> =
        channels.mapValues { (_, entries) -> entries.filter { it.until > now } }.filterValues { it.isNotEmpty() }

    fun receive(channels: Map<String, List<TypingEntry>>, channel: String, entry: TypingEntry,
        start: Boolean, now: Long): Map<String, List<TypingEntry>> {
        val live = expire(channels, now)
        val rest = live[channel].orEmpty().filter { it.account != entry.account || it.member != entry.member }
        val next = if (start && entry.until > now) rest + entry else rest
        return if (next.isEmpty()) live - channel else live + (channel to next)
    }
}

class TypingThrottle {
    private val last = mutableMapOf<String, Long>()
    fun allow(channel: String, start: Boolean, now: Long): Boolean {
        if (!start) return true
        if (last[channel]?.let { now - it < 2000 } == true) return false
        last[channel] = now
        return true
    }
    fun clear() = last.clear()
}

object TypingText {
    /** Only own members and already-visible author cards are named; hidden members use the account. */
    fun label(model: Model, entries: List<TypingEntry>): String? {
        val unique = entries.distinctBy { it.account to it.member }
        if (unique.isEmpty()) return null
        if (unique.size > 2) return "Several people are typing…"
        val names = unique.map { e ->
            val own = if (e.account == model.typingNames.accountId) e.member?.let(model::member)?.shownName else null
            val visible = e.member?.let { model.typingNames.visibleAuthors[it] }?.takeIf { it.accountId == e.account }?.name
            own ?: visible ?: model.typingNames.accounts[e.account] ?: "Someone"
        }
        return if (names.size == 1) "${names[0]} is typing…" else "${names[0]} and ${names[1]} are typing…"
    }
}

internal fun typingEntry(frame: JSONObject): TypingEntry? {
    if (frame.optString("state") !in listOf("start", "stop")) return null
    val account = frame.optString("account").takeIf { it.isNotEmpty() } ?: return null
    val until = (frame.opt("until") as? Number)?.toLong() ?: return null
    return TypingEntry(account, if (frame.isNull("member")) null else frame.optString("member").takeIf { it.isNotEmpty() }, until)
}
