package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

/** A privacy bucket (D-101, D-115): a named group of followers with its own icon, colour and ceiling. */
data class PrivacyBucket(val id: String, val name: String, val icon: String, val ceiling: JSONObject, val color: String? = null)

/** One Advanced notification detail; [default] is what it means while inherited. */
data class CeilingField(val key: String, val label: String, val kind: String, val default: Any?,
    val choices: List<String> = emptyList(), val hint: String? = null)

/**
 * Privacy bucket controls (UI11c, D-115): the icons a bucket may wear, the Advanced notification
 * ceiling fields with the values each accepts, and reading buckets from the projection. Shared
 * with web through fixtures/ui_bucket_controls.json (BucketControlsTest checks they agree);
 * notification semantics stay in chorus-core::notify.
 */
object BucketControls {
    val icons = listOf("friends", "members", "heart", "lock", "globe", "chat", "journal", "front", "layers", "bell", "bookmark", "folder")
    const val DEFAULT_ICON = "friends"
    const val CUSTOM_ICON_MAX = 16
    val swatches = listOf("#B99CE8", "#F08C7F", "#EEAA62", "#DCA3CC", "#8FB8E8", "#86BFA5", "#42A397", "#A09184")
    /** An audience menu lists this many buckets, then folds the rest under "N more". */
    const val COLLAPSE_AFTER = 8

    /** A custom icon: a short glyph such as an emoji (every code point non-ASCII). */
    fun isCustomIcon(v: String): Boolean = v.length in 1..CUSTOM_ICON_MAX && v.codePoints().allMatch { it >= 0x80 }
    /** A bucket's icon as it shows: a built-in icon's name, its custom glyph, or the default. */
    fun icon(v: Any?): String = (v as? String)?.takeIf { it in icons || isCustomIcon(it) } ?: DEFAULT_ICON
    fun isBuiltIn(icon: String): Boolean = icon in icons
    /** A bucket's colour, `#RRGGBB`, or none. */
    fun color(v: Any?): String? = (v as? String)?.takeIf { Regex("#[0-9a-fA-F]{6}").matches(it) }?.uppercase()

    /** The buckets a menu lists: all of them up to [COLLAPSE_AFTER], else the first ones plus any
     *  chosen further down, and how many are folded away. */
    fun <T> listed(buckets: List<T>, id: (T) -> String, chosen: Collection<String>, all: Boolean): Pair<List<T>, Int> {
        if (all || buckets.size <= COLLAPSE_AFTER) return buckets to 0
        val shown = buckets.filterIndexed { i, b -> i < COLLAPSE_AFTER || id(b) in chosen }
        return shown to buckets.size - shown.size
    }

    val fields = listOf(
        CeilingField("switch_notifications", "Switch notifications", "bool", true, hint = "Off still reveals the front on schedule, without a ping"),
        CeilingField("levels", "Layers they hear about", "levels", JSONArray(listOf("front", "cocon")), listOf("front", "cocon", "present")),
        CeilingField("announce_leaving", "Say who left", "bool", false),
        CeilingField("hidden_subjects", "Members they can't see", "enum", "omit", listOf("omit", "someone")),
        CeilingField("delay", "Random delay", "range", range(300, 1200), hint = "Hides when you switched as it happens"),
        CeilingField("extra_delay", "Extra delay, when a switch asks for it", "range", range(1800, 7200)),
        CeilingField("allow_now", "Allow “notify now”", "bool", true),
        CeilingField("time", "Time shown", "time", JSONObject().put("mode", "round").put("round_min", 15),
            listOf("exact", "round", "jitter", "part_of_day", "hidden"), "Hides the exact time afterwards"),
        CeilingField("stacking", "Several switches in a row", "enum", "collapse", listOf("collapse", "sequence")),
        CeilingField("digest_only", "Digest only", "bool", false),
        CeilingField("max_per_day", "Most pings a day", "limit", null, hint = "Past it, the rest go in the digest"),
        CeilingField("quiet_hours", "Quiet hours", "quiet", null, hint = "Your system's time; pings wait, the reveal doesn't"),
        CeilingField("share_current_front", "Show who's here now", "bool", true),
        CeilingField("share_history", "Look back at who fronted", "bool", false),
        CeilingField("share_stats", "Fronting stats", "bool", false),
    )
    private val labels = mapOf("front" to "Front", "cocon" to "Co-conscious", "present" to "Present",
        "omit" to "Left out", "someone" to "Shown as “someone”",
        "exact" to "Exact", "round" to "Rounded", "jitter" to "Nudged", "part_of_day" to "Part of day", "hidden" to "Not shown",
        "collapse" to "Only the latest", "sequence" to "Each one, in order")
    fun choiceLabel(id: String): String = labels[id] ?: id.replace('_', ' ')
    const val RANGE_MAX_S = 604800L
    const val MINUTES_MAX = 1440L
    const val PER_DAY_MAX = 1000L

    fun range(min: Long, max: Long): JSONObject = JSONObject().put("min_s", min).put("max_s", max)

    private fun whole(v: Any?, min: Long, max: Long): Boolean = when (v) {
        is Int, is Long -> (v as Number).toLong() in min..max
        is Double -> v % 1.0 == 0.0 && v.toLong() in min..max
        else -> false
    }
    private fun clock(v: Any?) = v is String && Regex("([01][0-9]|2[0-3]):[0-5][0-9]").matches(v)

    /** Whether [value] may be saved for the ceiling field [key]. */
    fun valid(key: String, value: Any?): Boolean {
        val f = fields.find { it.key == key } ?: return false
        val v = if (value == JSONObject.NULL) null else value
        val o = v as? JSONObject
        return when (f.kind) {
            "bool" -> v is Boolean
            "enum" -> v is String && v in f.choices
            "levels" -> v is JSONArray && (0 until v.length()).map { v.opt(it) }.let { xs ->
                xs.all { it is String && it in f.choices } && xs.toSet().size == xs.size }
            "range" -> o != null && whole(o.opt("min_s"), 0, RANGE_MAX_S) && whole(o.opt("max_s"), 0, RANGE_MAX_S) &&
                o.getLong("max_s") >= o.getLong("min_s")
            "time" -> o != null && (o.opt("mode") as? String)?.takeIf { it in f.choices }?.let { mode ->
                when (mode) {
                    "round" -> whole(o.opt("round_min"), 1, MINUTES_MAX)
                    "jitter" -> whole(o.opt("jitter_min"), 1, MINUTES_MAX)
                    else -> true
                }
            } == true
            "limit" -> v == null || whole(v, 0, PER_DAY_MAX)
            "quiet" -> v == null || (o != null && clock(o.opt("from")) && clock(o.opt("to")))
            else -> false
        }
    }

    /** [ceiling] with [key] set to [value], or inherited again (removed) when [inherit]. */
    fun withField(ceiling: JSONObject, key: String, value: Any?, inherit: Boolean = false): JSONObject =
        JSONObject(ceiling.toString()).apply { if (inherit) remove(key) else put(key, value ?: JSONObject.NULL) }

    /** Live buckets, by name. A deleted or nameless bucket is skipped; an unknown icon reads as the default. */
    fun buckets(p: JSONObject): List<PrivacyBucket> {
        val rows = p.optJSONObject("rows")?.optJSONObject("bucket") ?: return emptyList()
        return rows.keys().asSequence().mapNotNull { id ->
            val row = rows.optJSONObject(id)?.takeIf { it.optBoolean("exists") }?.optJSONObject("fields") ?: return@mapNotNull null
            if (row.has("deleted_at") && !row.isNull("deleted_at")) null
            else PrivacyBucket(id, (row.opt("name") as? String)?.takeIf { it.isNotBlank() } ?: "Untitled",
                icon(row.opt("icon")), row.optJSONObject("ceiling") ?: JSONObject(), color(row.opt("color")))
        }.sortedWith(compareBy({ it.name.lowercase() }, { it.id })).toList()
    }

    /** bucket id → its followers' account ids (`bucket.assign`/`bucket.unassign`, a set). */
    fun assignments(p: JSONObject): Map<String, Set<String>> {
        val rows = p.optJSONObject("sets")?.optJSONObject("bucket_assignment") ?: return emptyMap()
        val out = mutableMapOf<String, MutableSet<String>>()
        for (key in rows.keys()) if (rows.optBoolean(key)) {
            val bar = key.indexOf('|')
            if (bar < 1) continue
            val id = runCatching { JSONObject(key.substring(bar + 1)).opt("follower_account_id") as? String }.getOrNull() ?: continue
            out.getOrPut(key.substring(0, bar)) { mutableSetOf() }.add(id)
        }
        return out
    }

    /** The one live bucket [audience] names, if it names exactly one: the chip wears its look. */
    fun single(audience: Audience, looks: Map<String, PrivacyBucket>): PrivacyBucket? =
        if (audience.mode != "buckets") null else audience.buckets.distinct().mapNotNull { looks[it] }.singleOrNull()
}
