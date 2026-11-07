package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

// Who can see each part of a member's profile (D-101). `member.visibility` is who can open the
// profile; `member.field_visibility` maps a part key or custom field id to its own audience. An
// unset built-in part follows the profile; an unset custom field follows the field's
// `default_visibility`. The server applies the same rule (profiles.rs) and is what decides; this
// only shows and edits the choices. Mirrors web profile-audience.ts (fixture ui_profile_audience.json).

/** [mode] is `private`, `followers`, `buckets` or `server`; [buckets] only for `buckets`. */
data class Audience(val mode: String, val buckets: List<String> = emptyList()) {
    fun toJson(): JSONObject = JSONObject().put("mode", mode).apply { if (mode == "buckets") put("bucket_ids", JSONArray(buckets)) }

    fun label(bucketNames: Map<String, String> = emptyMap()): String = when (mode) {
        "followers" -> "Friends"
        "server" -> "Everyone here"
        "buckets" -> buckets.distinct().filter { it in bucketNames }.let { named ->
            when (named.size) { 0 -> "Only us"; 1 -> bucketNames.getValue(named[0]); else -> "${named.size} buckets" }
        }
        else -> "Only us"
    }

    /** Nobody outside the account (buckets that no longer exist share with nobody). */
    fun isPrivate(liveBuckets: Set<String>): Boolean = mode == "private" || (mode == "buckets" && buckets.none { it in liveBuckets })

    fun same(other: Audience): Boolean = mode == other.mode && (mode != "buckets" || buckets.sorted() == other.buckets.sorted())

    /** Without buckets that no longer exist; with none left it reaches nobody, so it's private. */
    fun live(liveBuckets: Set<String>): Audience = if (mode != "buckets") this
        else buckets.filter { it in liveBuckets }.let { if (it.isEmpty()) PRIVATE else Audience("buckets", it) }

    /** Incomparable bucket selections may add readers, so warn conservatively. */
    fun possiblyWidens(previous: Audience): Boolean {
        val ranks = mapOf("private" to 0, "buckets" to 1, "followers" to 2, "server" to 3)
        if (mode == "buckets" && previous.mode == "buckets") return buckets.any { it !in previous.buckets }
        return (ranks[mode] ?: 0) > (ranks[previous.mode] ?: 0)
    }

    companion object {
        val PRIVATE = Audience("private")
    }
}

object ProfileAudience {
    /** Live sharing buckets as (id, name), by name. */
    fun buckets(p: JSONObject): List<Pair<String, String>> {
        val rows = p.optJSONObject("rows")?.optJSONObject("bucket") ?: return emptyList()
        return rows.keys().asSequence().mapNotNull { id ->
            val f = rows.optJSONObject(id)?.takeIf { it.optBoolean("exists") }?.optJSONObject("fields") ?: return@mapNotNull null
            if (!f.isNull("deleted_at")) null else id to f.optString("name").ifBlank { "Untitled" }
        }.sortedWith(compareBy({ it.second.lowercase() }, { it.first })).toList()
    }

    /** The built-in parts with their own audience, in the editor's order. The display name has
     *  none: it follows the profile (D-126). */
    val PARTS = listOf("name" to "Full name", "pronouns" to "Pronouns", "description" to "Bio",
        "birthday" to "Birthday", "banner" to "Banner")

    /** A stored audience, or private when it's missing or of the wrong shape (fails closed, as the server does). */
    fun of(v: Any?): Audience {
        val o = v as? JSONObject ?: return Audience.PRIVATE
        return when (val mode = o.opt("mode")) {
            "followers", "server" -> Audience(mode as String)
            "buckets" -> Audience("buckets", o.optJSONArray("bucket_ids")?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } }.orEmpty())
            else -> Audience.PRIVATE
        }
    }

    /** Parses stored JSON text (as kept on [Member]); anything unreadable is `null`. */
    fun parse(text: String?): Any? = text?.let { t -> runCatching { org.json.JSONTokener(t).nextValue() }.getOrNull() }
        ?.takeIf { it is JSONObject || it is JSONArray }

    /** A part's or field's own audience, or `null` when it follows the profile (or the field's default). */
    fun own(fieldVisibility: Any?, key: String): Audience? = when (fieldVisibility) {
        null, JSONObject.NULL -> null
        is JSONObject -> fieldVisibility.opt(key)?.takeIf { it != JSONObject.NULL }?.let(::of)
        else -> Audience.PRIVATE
    }

    /** What a part or field is actually shared with, before the profile's own limit. [fieldDefault]
     *  is the custom field's `default_visibility` (absent for built-in parts). The complete name
     *  is private unless chosen (D-126). */
    fun effective(profile: Any?, fieldVisibility: Any?, key: String, isField: Boolean = false, fieldDefault: Any? = null): Audience =
        own(fieldVisibility, key) ?: if (!isField && key == "name") Audience.PRIVATE else of(if (isField) fieldDefault else profile)

    /** The parts map with [key] set to [audience], or cleared back to "same as profile" with `null`. */
    fun with(fieldVisibility: Any?, key: String, audience: Audience?): JSONObject {
        val out = JSONObject()
        (fieldVisibility as? JSONObject)?.let { map ->
            for (k in map.keys()) map.opt(k)?.takeIf { it != JSONObject.NULL }?.let { out.put(k, of(it).toJson()) }
        }
        if (audience != null) out.put(key, audience.toJson()) else out.remove(key)
        return out
    }
}
