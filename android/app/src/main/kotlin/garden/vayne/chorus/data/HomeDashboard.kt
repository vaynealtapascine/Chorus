package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

/** A Home section and whether it shows (F0.3). */
data class HomeSection(val id: String, val on: Boolean)

/** One line of Home's *Today* (F0.2): a switch or a post of ours. */
data class TodayEvent(val id: String, val at: Long, val kind: String, val text: String)

/** A friend's card under *Friends right now* (F0.1, F0.2, F0.4), most telling first. */
enum class FriendCard { Around, NotYet, Posted, Quiet, NotSharing }

/**
 * Chorus Home (D-130, Figma F0): which sections show and in what order, who leads Quick switch,
 * what waits for us, how friends' cards sort and what happened today. Pure, so it is tested
 * without a device. Each reader keeps their own sections in `home.sections:<member>`; a person (or
 * a system with nobody reading) uses `home.sections`.
 */
object HomeDashboard {
    const val PREF = "home.sections"
    const val READER = "home.sections:"

    val SYSTEM = listOf("fronting", "quick_switch", "handover", "waiting", "friends", "today", "new_from_friends")
    val PERSON = listOf("write", "friends", "waiting", "new_from_friends")
    /** Sections waiting on a feature: Handover is D-132. They keep their place but never show. */
    val UNBUILT = setOf("handover")

    fun key(reader: String?): String = if (reader.isNullOrBlank()) PREF else READER + reader

    /** The stored order and switches, with unknown ids dropped and missing ones put back where the
     * default order has them (after the nearest earlier one), shown. */
    fun parse(raw: Any?, person: Boolean): List<HomeSection> {
        val defaults = if (person) PERSON else SYSTEM
        val stored = (raw as? JSONArray)?.let { a -> (0 until a.length()).mapNotNull { i ->
            when (val v = a.opt(i)) {
                is JSONObject -> v.optString("id").takeIf { it in defaults }?.let { HomeSection(it, v.optBoolean("on", true)) }
                is String -> v.takeIf { it in defaults }?.let { HomeSection(it, true) }
                else -> null
            }
        } }.orEmpty().distinctBy { it.id }
        val out = stored.toMutableList()
        defaults.forEachIndexed { i, id ->
            if (out.any { it.id == id }) return@forEachIndexed
            val before = defaults.take(i).lastOrNull { prev -> out.any { it.id == prev } }
            out.add(before?.let { b -> out.indexOfFirst { it.id == b } + 1 } ?: 0, HomeSection(id, true))
        }
        return out
    }

    /** A reader's sections, else the account's, else the defaults. */
    fun forReader(byReader: Map<String, Any?>, accountWide: Any?, reader: String?, person: Boolean): List<HomeSection> =
        parse(reader?.let { byReader[it] } ?: accountWide, person)

    /** What the drawer lists: everything built. */
    fun editable(sections: List<HomeSection>): List<HomeSection> = sections.filter { it.id !in UNBUILT }

    /** What Home draws, in order. */
    fun shown(sections: List<HomeSection>): List<String> = sections.filter { it.on && it.id !in UNBUILT }.map { it.id }

    fun value(sections: List<HomeSection>): JSONArray =
        JSONArray(sections.map { JSONObject().put("id", it.id).put("on", it.on) })

    fun payload(reader: String?, sections: List<HomeSection>?): JSONObject =
        AccountPrefs.payload(key(reader), sections?.let(::value) ?: JSONObject.NULL)

    /** Quick switch (F0.1): pinned members, then the recently switched in, then everyone else, as
     * many as fit. Only [own] members (active, ours) are offered. */
    fun quickSwitch(own: List<String>, pins: List<String>, recents: List<String>, limit: Int): List<String> {
        val ours = own.toSet()
        return (pins + recents + own).filter { it in ours }.distinct().take(limit.coerceAtLeast(0))
    }

    /** *Waiting for you* (F0.1): chats with unread messages, not muted or archived, newest first. */
    fun <T> waiting(rows: List<T>, unread: (T) -> Int, muted: (T) -> Boolean, archived: (T) -> Boolean, latest: (T) -> Long,
        limit: Int = 3): List<T> = rows.filter { unread(it) > 0 && !muted(it) && !archived(it) }
        .sortedByDescending(latest).take(limit)

    fun card(row: FriendRow, now: Long): FriendCard = when {
        !row.account.isSystem -> if (Friends.around(row, now)) FriendCard.Posted else FriendCard.Quiet
        row.view?.shared == false -> FriendCard.NotSharing
        row.view?.entries?.isNotEmpty() == true -> FriendCard.Around
        row.view?.since == null -> FriendCard.NotYet
        else -> FriendCard.Quiet
    }

    /** *Friends right now*: whom we follow, those around first (newest arrival first), then those
     * we'll see after their next switch, people who posted lately, the quiet ones and those who
     * don't share who's around. A person who hasn't posted lately isn't "right now": left out. */
    fun friends(rows: List<FriendRow>, now: Long): List<Pair<FriendRow, FriendCard>> =
        rows.filter { it.follows }.map { it to card(it, now) }
            .filter { (r, c) -> r.account.isSystem || c == FriendCard.Posted }
            .sortedWith(compareBy<Pair<FriendRow, FriendCard>> { it.second.ordinal }
                .thenByDescending { it.first.view?.since ?: it.first.latest?.occurredAt ?: it.first.following?.createdAt ?: 0L })

    /** "Kit steering · Juniper co-conscious" for one of our switches: the innermost layer "started"
     * its layer word, the next layer after it; two layers at most. [layers] are (id, name) inner first. */
    fun switchLine(entries: List<Entry>, layers: List<Pair<String, String>>, name: (Entry) -> String?): String {
        val order = layers.map { it.first }
        val groups = entries.groupBy { it.level }.toList()
            .sortedBy { (level, _) -> order.indexOf(level).let { if (it < 0) Int.MAX_VALUE else it } }
        if (groups.isEmpty()) return "No one fronting"
        return groups.take(2).mapNotNull { (level, here) ->
            val names = here.sortedByDescending { it.isPrimary }.mapNotNull(name).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val who = if (names.size <= 2) names.joinToString(" & ") else "${names[0]}, ${names[1]} & ${names.size - 2} more"
            val word = layers.firstOrNull { it.first == level }?.second?.lowercase() ?: Friends.levelWord(level)
            if (level == order.firstOrNull()) "$who started $word" else "$who $word"
        }.joinToString(" · ").ifEmpty { "No one fronting" }
    }

    /** *Today* (F0.2): our switches and posts since [dayStart], newest first. Retracted switches,
     * replies and reposts are left out. */
    fun today(switches: List<SwitchRow>, posts: List<JournalPost>, dayStart: Long, layers: List<Pair<String, String>>,
        name: (Entry) -> String?, author: (List<String>) -> String?): List<TodayEvent> {
        val s = switches.filter { !it.retracted && it.occurredAt >= dayStart }
            .map { TodayEvent(it.id, it.occurredAt, "switch", switchLine(it.resultingFront, layers, name)) }
        val p = posts.filter { it.occurredAt >= dayStart && it.replyTo == null && it.repostOf == null }.map { post ->
            val title = post.title?.takeIf { it.isNotBlank() } ?: post.text.lineSequence().firstOrNull { it.isNotBlank() }
                ?.let { if (it.length > 40) it.take(39).trimEnd() + "…" else it }
            val who = author(post.authors) ?: "We"
            TodayEvent(post.id, post.occurredAt, "post", if (title == null) "$who posted" else "$who wrote “$title”")
        }
        return (s + p).sortedWith(compareByDescending<TodayEvent> { it.at }.thenBy { it.id })
    }

    /** A person's first day (F0.6): what is still to do. */
    data class FirstDay(val welcome: Boolean, val post: Boolean, val profile: Boolean) {
        val any: Boolean get() = welcome || post || profile
    }

    fun firstDay(follows: Int, ownPosts: Int, self: Member?): FirstDay = FirstDay(follows == 0, ownPosts == 0,
        self != null && self.pronouns.isNullOrBlank() && self.avatarBlob == null && self.description.isNullOrBlank())
}
