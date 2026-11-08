package garden.vayne.chorus.data

import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject

/**
 * One account on the Friends screens (Figma F7.1, F7.4): whom we follow ([following]), who follows
 * us ([follower]), what they share of who's here ([view]) and their newest post we may read.
 */
data class FriendRow(val account: SpaceAccount, val following: FollowInfo?, val follower: FollowInfo?,
    val view: FollowerView?, val latest: SharedPost?) {
    val id: String get() = account.id
    val waiting: Boolean get() = following?.status == "requested"
    val follows: Boolean get() = following?.status == "active"
    /** Friends follow each other (D-129); one way is a follow, not a friendship. */
    val mutual: Boolean get() = follows && follower?.status == "active"
    /** Since when we've been friends: the newer of the two follows. */
    val since: Long get() = if (mutual) maxOf(following!!.createdAt, follower!!.createdAt) else 0
}

/**
 * The Friends screens' wording and choices (UI20, Figma F7): who is "around right now", how a
 * friend's shared time reads, follow prefs (NOTIFICATIONS §4) edited one control at a time, and the
 * ceiling an accepted request starts with. Pure, so it is tested without a device.
 */
object Friends {
    /** A person counts as around when they posted within this long. */
    const val AROUND_MS = 24 * 60 * 60 * 1000L
    /** Account pref: the bucket the accept drawer starts on ("New friends start in", F7.8). */
    const val START_BUCKET = "follow.start_bucket"

    /** Everyone we follow (in the order we followed them), then those who only follow us. */
    fun rows(follows: FollowList, views: Map<String, FollowerView>, latest: Map<String, SharedPost?>): List<FriendRow> {
        val following = follows.following.filter { it.status == "active" || it.status == "requested" }
        val followers = follows.followers.filter { it.status == "active" }
        val ids = (following.map { it.account.id } + followers.map { it.account.id }).distinct()
        return ids.map { id ->
            val out = following.find { it.account.id == id }
            val back = followers.find { it.account.id == id }
            FriendRow((out ?: back)!!.account, out, back, views[id], latest[id])
        }
    }

    /** Around right now (F7.1): a system that shows someone here, a person who posted lately. */
    fun around(row: FriendRow, now: Long): Boolean = when {
        !row.follows -> false
        row.account.isSystem -> row.view?.entries?.isNotEmpty() == true
        else -> row.latest?.let { now - it.occurredAt < AROUND_MS } == true
    }

    /** How a layer reads after a name: "Kit fronting", "Juniper co-conscious". */
    fun levelWord(level: String): String = when (level) {
        "front" -> "fronting"; "cocon" -> "co-conscious"; "present" -> "present"; else -> "here"
    }

    /** "Kit fronting · Juniper co-conscious", front first, at most [max] names. */
    fun nowLine(entries: List<SharedMember>, max: Int = 3): String {
        val order = listOf("front", "cocon", "present")
        val sorted = entries.sortedBy { order.indexOf(it.level).let { i -> if (i < 0) order.size else i } }
        val shown = sorted.take(max).joinToString(" · ") { "${it.name} ${levelWord(it.level)}" }
        return if (sorted.size > max) "$shown · ${sorted.size - max} more" else shown
    }

    private fun clock(at: Long): String = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(at))

    /** The part of day core uses (notify.rs `part_of`), in this phone's time. */
    fun partOfDay(at: Long): String {
        val h = Calendar.getInstance().apply { timeInMillis = at }.get(Calendar.HOUR_OF_DAY)
        return when (h) { in 5..11 -> "morning"; in 12..16 -> "afternoon"; in 17..21 -> "evening"; else -> "night" }
    }

    private fun dayOffset(at: Long, now: Long): Int {
        fun day(t: Long) = Calendar.getInstance().apply {
            timeInMillis = t; set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return Math.round((day(now) - day(at)) / 86_400_000.0).toInt()
    }

    /**
     * "since about 10:15", "since this morning", "since 10:15": the view's time as precisely as the
     * friend shares it (their ceiling's time rule), never more. Null when they hide the time.
     */
    fun sinceLine(view: FollowerView?, now: Long, clock: (Long) -> String = ::clock): String? {
        val at = view?.since ?: return null
        val mode = view.time?.optString("mode").orEmpty()
        val days = dayOffset(at, now)
        val day = when (days) { 0 -> ""; 1 -> "yesterday "; else -> java.text.SimpleDateFormat("EEE ", java.util.Locale.getDefault()).format(java.util.Date(at)) }
        return when (mode) {
            "hidden" -> null
            "part_of_day" -> partOfDay(at).let { part -> if (days == 0) (if (part == "night") "since last night" else "since this $part") else "since $day$part".trimEnd() }
            "exact" -> "since $day${clock(at)}"
            else -> "since about $day${clock(at)}"
        }
    }

    /** One line on how precise their times are (F7.2): "The Junipers round times to 15 minutes." */
    fun timeNote(name: String, time: JSONObject?): String? = when (time?.optString("mode")) {
        "exact" -> "$name share exact times."
        "round" -> "$name round times to ${time.optInt("round_min", 15)} minutes."
        "jitter" -> "$name nudge times by up to ${time.optInt("jitter_min", 15)} minutes."
        "part_of_day" -> "$name share only the part of the day."
        "hidden" -> "$name don't share times."
        else -> null
    }

    private val MONTH = java.text.SimpleDateFormat("MMMM", java.util.Locale.getDefault())
    private val MONTH_YEAR = java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault())

    /** "friends since March" (this year), "friends since March 2025", "friends since today". */
    fun friendsSince(at: Long, now: Long): String? {
        if (at <= 0) return null
        if (dayOffset(at, now) == 0) return "friends since today"
        val year = { t: Long -> Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.YEAR) }
        return "friends since " + (if (year(at) == year(now)) MONTH else MONTH_YEAR).format(java.util.Date(at))
    }

    /** What a person did last (F7.1, F7.4): "Wrote a journal entry · 2h", "Posted a photo · 2h". */
    fun activity(post: SharedPost, ago: String): String = when {
        post.kind == "entry" -> "Wrote a journal entry · $ago"
        post.replyTo != null -> "Replied to a post · $ago"
        post.attachments.any { it.mime.startsWith("image/") } -> "Posted a photo · $ago"
        else -> "Posted · $ago"
    }

    // ─── follow prefs (NOTIFICATIONS §4), the follower's own ───────────────────────────────────

    private fun copy(prefs: JSONObject) = JSONObject(prefs.toString())

    fun enabled(prefs: JSONObject): Boolean = prefs.optBoolean("enabled", true)
    fun switchOuts(prefs: JSONObject): Boolean = prefs.optBoolean("switch_outs", false)
    fun delivery(prefs: JSONObject): String = prefs.optString("delivery", "each").takeIf { it in DELIVERIES } ?: "each"
    fun digestTime(prefs: JSONObject): String = prefs.optString("digest_time", "20:00").takeIf { CLOCK.matches(it) } ?: "20:00"
    val DELIVERIES = listOf("each", "digest_hourly", "digest_daily")
    val CLOCK = Regex("([01][0-9]|2[0-3]):[0-5][0-9]")

    fun levels(prefs: JSONObject): Set<String> =
        prefs.optJSONArray("levels")?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String }.toSet() } ?: setOf("front")

    /** Who they hear about: null for everyone, else the member ids picked. */
    fun subjects(prefs: JSONObject): Set<String>? {
        val s = prefs.optJSONObject("subjects") ?: return null
        return when (s.optString("mode")) {
            "only" -> s.optJSONArray("ids")?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String }.toSet() }.orEmpty()
            else -> null
        }
    }

    fun withEnabled(prefs: JSONObject, on: Boolean) = copy(prefs).put("enabled", on)
    fun withSwitchOuts(prefs: JSONObject, on: Boolean) = copy(prefs).put("switch_outs", on)
    fun withDelivery(prefs: JSONObject, delivery: String): JSONObject { require(delivery in DELIVERIES); return copy(prefs).put("delivery", delivery) }
    fun withDigestTime(prefs: JSONObject, at: String): JSONObject { require(CLOCK.matches(at)); return copy(prefs).put("digest_time", at) }

    /** Tap one layer chip: at least one layer stays on. */
    fun withLevel(prefs: JSONObject, level: String): JSONObject {
        val now = levels(prefs)
        val next = if (level in now) now - level else now + level
        if (next.isEmpty()) return prefs
        return copy(prefs).put("levels", JSONArray(listOf("front", "cocon", "present").filter { it in next }))
    }

    /**
     * Tap one member chip ([all]: everyone they share): from "everyone" it keeps all but that one;
     * picking everyone again goes back to "everyone", so new members are heard about too. At least
     * one stays picked.
     */
    fun withSubject(prefs: JSONObject, id: String, all: List<String>): JSONObject {
        val now = subjects(prefs) ?: all.toSet()
        val next = if (id in now) now - id else now + id
        if (next.isEmpty()) return prefs
        val out = copy(prefs)
        if (all.all { it in next }) out.put("subjects", JSONObject().put("mode", "all"))
        else out.put("subjects", JSONObject().put("mode", "only").put("ids", JSONArray(all.filter { it in next } + next.filter { it !in all }.sorted())))
        return out
    }

    fun quiet(prefs: JSONObject): Pair<String, String>? = prefs.optJSONObject("quiet_hours")?.let { q ->
        val from = q.optString("from"); val to = q.optString("to")
        if (CLOCK.matches(from) && CLOCK.matches(to)) from to to else null
    }
    fun quietHold(prefs: JSONObject): Boolean = prefs.optString("quiet_behaviour", "hold") != "drop"
    fun withQuiet(prefs: JSONObject, hours: Pair<String, String>?, hold: Boolean = quietHold(prefs)): JSONObject {
        val out = copy(prefs)
        if (hours == null) out.put("quiet_hours", JSONObject.NULL)
        else { require(CLOCK.matches(hours.first) && CLOCK.matches(hours.second)); out.put("quiet_hours", JSONObject().put("from", hours.first).put("to", hours.second)) }
        return out.put("quiet_behaviour", if (hold) "hold" else "drop")
    }

    fun mutedUntil(prefs: JSONObject, now: Long): Long? =
        prefs.optLong("mute_until").takeIf { prefs.has("mute_until") && !prefs.isNull("mute_until") && it > now }
    fun withMute(prefs: JSONObject, until: Long?) = copy(prefs).put("mute_until", until ?: JSONObject.NULL)

    /** Prefs as saved: the phone's UTC offset rides along so their quiet hours are ours (§4). */
    fun forSaving(prefs: JSONObject, tzOffsetMin: Int) = copy(prefs).put("tz_offset_min", tzOffsetMin)

    /** "Kit and Juniper · each switch", "Off", "Muted": the Notifications from them row (F7.2). */
    fun prefsSummary(prefs: JSONObject, names: Map<String, String>, now: Long): String {
        if (!enabled(prefs)) return "Off"
        val who = subjects(prefs)?.mapNotNull { names[it] }?.let { list ->
            when (list.size) { 0 -> "Some members"; 1 -> list[0]; 2 -> "${list[0]} and ${list[1]}"; else -> "${list[0]}, ${list[1]} and ${list.size - 2} more" }
        } ?: "Everyone"
        val how = when (delivery(prefs)) { "digest_hourly" -> "hourly"; "digest_daily" -> "daily at ${digestTime(prefs)}"; else -> "each switch" }
        return if (mutedUntil(prefs, now) != null) "Muted for now" else "$who · $how"
    }

    // ─── sharing (our side) ─────────────────────────────────────────────────────────────────────

    /** The Basic preset names as the segmented controls show them (F7.9, F7.10). */
    val PRESETS = listOf("close", "gentle", "private", "digest", "off")
    fun presetName(choice: String): String = when (choice) {
        "close" -> "Close"; "gentle" -> "Gentle"; "private" -> "Private"; "digest" -> "Digest"; "off" -> "Off"
        "inherit" -> "From their buckets"; else -> "Custom"
    }
    fun presetAbout(choice: String): String = when (choice) {
        "close" -> "Right away, with the exact time. Switch-outs too."
        "gentle" -> "5–20 min later, rounded to 15 min."
        "private" -> "30–90 min later, only “this evening”; co-conscious hidden."
        "digest" -> "One summary a day."
        "off" -> "Your profile only, no switch pings."
        else -> "Set detail by detail."
    }

    /** Whether a follower in [buckets] hears when a member with [policy] fronts (`announce`, NOTIFICATIONS §2.4). */
    fun announcedTo(policy: String?, buckets: Set<String>): Boolean {
        val announce = policy?.let { runCatching { JSONObject(it) }.getOrNull() }?.opt("announce")
        return when {
            announce == "nobody" -> false
            announce is JSONObject -> announce.optJSONArray("buckets")?.let { a -> (0 until a.length()).any { a.optString(it) in buckets } } == true
            else -> true
        }
    }

    /** "Everyone", "Everyone except Violet", "Kit and Juniper", "Nobody" (F7.9 "Who they hear about"). */
    fun hearsAbout(members: List<Pair<String, Boolean>>): String {
        val heard = members.filter { it.second }.map { it.first }
        val not = members.filterNot { it.second }.map { it.first }
        fun list(n: List<String>) = when (n.size) { 1 -> n[0]; 2 -> "${n[0]} and ${n[1]}"; else -> "${n[0]}, ${n[1]} and ${n.size - 2} more" }
        return when {
            not.isEmpty() -> "Everyone"
            heard.isEmpty() -> "Nobody"
            not.size <= heard.size -> "Everyone except ${list(not)}"
            else -> list(heard)
        }
    }

    /** A ceiling detail's value as a row shows it (F7.10): "0 min", "5–20 min", "Exact", "Collapse". */
    fun fieldValue(key: String, value: Any?): String {
        fun min(s: Long) = if (s % 60 == 0L) "${s / 60}" else "%.1f".format(s / 60.0)
        return when {
            value == null || value == JSONObject.NULL -> if (key == "quiet_hours" || key == "max_per_day") "None" else "Off"
            value is Boolean -> if (value) "On" else "Off"
            key == "levels" && value is JSONArray -> (0 until value.length()).map { value.optString(it) }
                .map { when (it) { "front" -> "Front"; "cocon" -> "Co-conscious"; "present" -> "Present"; else -> it } }.joinToString(", ").ifEmpty { "None" }
            key == "delay" || key == "extra_delay" -> (value as? JSONObject)?.let { r ->
                val a = r.optLong("min_s"); val b = r.optLong("max_s")
                if (a == b) "${min(a)} min" else "${min(a)}–${min(b)} min"
            } ?: "Off"
            key == "time" -> (value as? JSONObject)?.let { t -> when (t.optString("mode")) {
                "exact" -> "Exact"; "round" -> "${t.optInt("round_min", 15)} min"; "jitter" -> "±${t.optInt("jitter_min", 15)} min"
                "part_of_day" -> "Part of day"; "hidden" -> "Hidden"; else -> "Custom"
            } } ?: "Custom"
            key == "stacking" -> if (value == "sequence") "Each one" else "Collapse"
            key == "hidden_subjects" -> if (value == "someone") "As “someone”" else "Left out"
            key == "quiet_hours" -> (value as? JSONObject)?.let { "${it.optString("from")}–${it.optString("to")}" } ?: "None"
            key == "max_per_day" -> "$value a day"
            else -> value.toString()
        }
    }

    /** What everyone else hears (the account default): an empty one is core's default, Gentle (notify.rs). */
    fun everyoneElse(accountDefault: JSONObject, choiceOf: (JSONObject) -> String = { FollowPresets.choiceOf(it) }): String =
        choiceOf(accountDefault).let { if (it == "inherit") "gentle" else it }

    /**
     * The override an accepted request gets (F7.7): none when the chosen [preset] is what they'd
     * get anyway, from [bucketCeiling] when a bucket is picked or else the [accountDefault], so the
     * bucket stays in charge whenever it can be.
     */
    fun acceptCeiling(preset: String, bucketCeiling: JSONObject?, accountDefault: JSONObject,
        choiceOf: (JSONObject) -> String = { FollowPresets.choiceOf(it) },
        ceiling: (String) -> JSONObject = { FollowPresets.ceiling(it) }): JSONObject? {
        val inherited = bucketCeiling?.let(choiceOf)?.takeIf { it != "inherit" } ?: everyoneElse(accountDefault, choiceOf)
        return if (preset == inherited) null else ceiling(preset)
    }
}
