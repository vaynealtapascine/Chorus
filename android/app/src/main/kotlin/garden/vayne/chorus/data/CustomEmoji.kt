package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One server-wide custom emoji (`custom_emoji`, D-054). A retired one stays in the list so old
 * messages still resolve their `emoji_id`; the composer and the manager use [CustomEmojis.active].
 */
data class CustomEmoji(val id: String, val name: String, val aliases: List<String>, val category: String,
    val blobHash: String, val animated: Boolean, val retired: Boolean) {
    /** What the text carries: the entity covers this `:name:`. */
    val token: String get() = ":$name:"
}

/** Custom emoji as web has them (chat-composer.ts, Chat.svelte): projection, `:name:` typing, names for the core, the manager's rules. */
object CustomEmojis {
    /** A cropped emoji is at most this large (web: 256 KB), and at most [SIZE] px square. */
    const val MAX_BYTES = 256 * 1024
    const val SIZE = 128
    const val DEFAULT_CATEGORY = "Custom"

    private val NAME = Regex("[a-z0-9_]{2,32}")
    private val TYPING = Regex("(?:^|\\s):([a-z0-9_]{1,32})$")
    private val HASH = Regex("[0-9a-f]{64}")
    val IMAGE_TYPES = listOf("image/png", "image/webp", "image/gif")

    /** chorus-core's `emoji::valid_name` (2–32 lowercase letters, digits, `_`); not exported through the FFI. */
    fun validName(name: String) = NAME.matches(name)

    /** The projection's rows, retired ones included, ordered by category then name (web's `customEmojis`). */
    fun parse(projection: JSONObject): List<CustomEmoji> {
        val table = projection.optJSONObject("rows")?.optJSONObject("custom_emoji") ?: return emptyList()
        return table.keys().asSequence().mapNotNull { id ->
            val row = table.optJSONObject(id) ?: return@mapNotNull null
            if (!row.optBoolean("exists")) return@mapNotNull null
            val f = row.optJSONObject("fields") ?: return@mapNotNull null
            val hash = f.optString("blob_hash").takeIf { f.has("blob_hash") && !f.isNull("blob_hash") && HASH.matches(it) }
                ?: return@mapNotNull null
            CustomEmoji(id, f.text("name") ?: "", aliases(f.opt("aliases")), f.text("category") ?: DEFAULT_CATEGORY, hash,
                f.optBoolean("is_animated"), f.has("deleted_at") && !f.isNull("deleted_at"))
        }.sortedWith(compareBy<CustomEmoji>({ it.category.lowercase() }, { it.name })).toList()
    }

    private fun JSONObject.text(key: String): String? = if (has(key) && !isNull(key)) optString(key).ifEmpty { null } else null

    private fun aliases(value: Any?): List<String> {
        val array = when (value) {
            is JSONArray -> value
            is String -> runCatching { JSONArray(value) }.getOrNull()
            else -> null
        } ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotEmpty() } }
    }

    fun active(all: List<CustomEmoji>) = all.filter { !it.retired && it.name.isNotEmpty() }

    /** The `emoji` names the core's markup parser resolves `:name:` with: aliases, then names, which win a collision. */
    fun names(all: List<CustomEmoji>): JSONObject {
        val out = JSONObject()
        val live = active(all)
        for (e in live) for (alias in e.aliases) out.put(alias, e.id)
        for (e in live) out.put(e.name, e.id)
        return out
    }

    /** The `:word` being typed at the end of [draft] (after a space or at the start), or null. */
    fun typing(draft: String): String? = TYPING.find(draft)?.groupValues?.get(1)

    /** Up to eight live emoji whose name or an alias starts with [query]. */
    fun matches(all: List<CustomEmoji>, query: String): List<CustomEmoji> =
        active(all).filter { e -> (listOf(e.name) + e.aliases).any { it.startsWith(query) } }.take(8)

    /** [draft] with its trailing `:word` replaced by the emoji's `:name:` and a space. */
    fun complete(draft: String, emoji: CustomEmoji): String {
        val query = typing(draft) ?: return draft
        return draft.dropLast(query.length + 1) + emoji.token + " "
    }

    /** [draft] with the emoji added at the end, a space before it when the text doesn't end in one (the tray). */
    fun append(draft: String, emoji: CustomEmoji): String =
        draft + (if (draft.isNotEmpty() && !draft.last().isWhitespace()) " " else "") + emoji.token + " "

    /** The live emoji by category, in the picker's order. */
    fun grouped(all: List<CustomEmoji>): List<Pair<String, List<CustomEmoji>>> =
        active(all).groupBy { it.category }.toList()

    /** The aliases typed as `a, b, c`: valid names only, as web keeps them. */
    fun parseAliases(text: String): List<String> = text.split(',').map { it.trim() }.filter { validName(it) }.distinct()

    /** What stops an add, in web's words, or null when it can go. */
    fun problem(all: List<CustomEmoji>, name: String, hasImage: Boolean): String? = when {
        !hasImage || !validName(name.trim()) -> "Choose an image and a 2–32 character lowercase name."
        active(all).any { it.name == name.trim() } -> "That emoji name is already in use."
        else -> null
    }

    /** An animated GIF goes up as it is: square, at most [SIZE] px, at most [MAX_BYTES]. */
    fun gifProblem(width: Int, height: Int, bytes: Long): String? = when {
        width != height || width > SIZE -> "Animated GIFs must already be square and at most 128 px."
        bytes > MAX_BYTES -> "Cropped emoji exceeds 256 KB."
        else -> null
    }

    /** The `emoji.create` payload (web: name, aliases, category, blob_hash, is_animated). */
    fun createPayload(name: String, aliases: String, category: String, blobHash: String, animated: Boolean): JSONObject =
        JSONObject().put("name", name.trim()).put("aliases", JSONArray(parseAliases(aliases)))
            .put("category", category.trim().ifEmpty { DEFAULT_CATEGORY }).put("blob_hash", blobHash).put("is_animated", animated)
}
