package garden.vayne.chorus.data

import org.json.JSONObject

/**
 * Mentions in the composer (SPEC §5.3; web's chat-composer.ts): `@front`, our members and groups.
 * The core's markup parser turns `@name` into a mention through [names]; [choices] are what the
 * composer suggests while an `@word` is being typed.
 */
object ChatMentions {
    data class Choice(val id: String?, val name: String, val type: String) {
        /** What follows the @: the core reads letters, digits, `_`, `.` and `-` as one name. */
        val token: String get() = name.trim().replace(Regex("[^\\p{L}\\p{N}_.-]+"), "_").trim('_', '.', '-').ifEmpty { type }
    }

    fun choices(model: Model): List<Choice> = listOf(Choice(null, "front", "front")) +
        model.active.sortedBy { it.shownName.lowercase() }.map { Choice(it.id, it.shownName, "member") } +
        model.groups.sortedBy { it.name.lowercase() }.map { Choice(it.id, it.name, "group") }

    /** The `names` the core composer resolves mentions with; a name two choices share resolves to neither. */
    fun names(model: Model): JSONObject {
        val all = choices(model) + model.active.filter { it.displayName != null && it.displayName != it.name }.map { Choice(it.id, it.name, "member") }
        val counts = all.groupingBy { it.token.lowercase() }.eachCount()
        val mentions = JSONObject()
        for (c in all) if (counts[c.token.lowercase()] == 1) mentions.put(c.token.lowercase(),
            JSONObject().put("target_type", c.type).put("target_id", c.id ?: JSONObject.NULL))
        return JSONObject().put("mentions", mentions).put("emoji", JSONObject())
    }

    /** The `@word` being typed at the end of [draft], or null. */
    fun typing(draft: String): String? = Regex("(?:^|\\s)@([^\\s@]*)$").find(draft)?.groupValues?.get(1)

    fun matches(model: Model, query: String): List<Choice> =
        choices(model).filter { it.name.contains(query, ignoreCase = true) || it.token.startsWith(query, ignoreCase = true) }.take(8)

    /** [draft] with its trailing `@word` replaced by the choice. */
    fun insert(draft: String, choice: Choice): String {
        val query = typing(draft) ?: return draft
        return draft.dropLast(query.length + 1) + "@" + choice.token + " "
    }
}
