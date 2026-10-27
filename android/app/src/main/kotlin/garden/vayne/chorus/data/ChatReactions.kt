package garden.vayne.chorus.data

import org.json.JSONObject

/** One emoji on a message and the members who chose it, in the order they appear in the set. */
data class ChatReaction(val emoji: String, val memberIds: List<String>)

/**
 * Message reactions (DATA_MODEL `reaction`, an LWW element set keyed by entity + canonical
 * payload). Add and remove must carry the identical element, so both use [payload].
 */
object ChatReactions {
    /** The F5.6 long-press row. */
    val QUICK = listOf("❤️", "👍", "😂", "🙏", "🫂", "👀")
    /** Behind the row's expand button. */
    val MORE = listOf(
        "😊", "🥰", "😍", "🤗", "😌", "🥲", "😢", "😭", "😮", "😳", "😅", "🤔",
        "😴", "🙃", "😤", "😡", "🎉", "✨", "💜", "💙", "💚", "🧡", "💛", "🖤",
        "🤍", "💔", "🔥", "⭐", "🌙", "☀️", "🌧️", "🌸", "🍵", "☕", "🍲", "🎨",
        "📚", "🎵", "👏", "🙌", "👋", "🤝", "💪", "✅", "❌", "❓", "💤", "🕯️",
    )

    fun payload(messageId: String, emoji: String, memberId: String): JSONObject = JSONObject()
        .put("target_type", "message").put("target_id", messageId).put("emoji", emoji).put("member_id", memberId)

    /** message id → its reactions, the most chosen first. */
    fun fromProjection(sets: JSONObject?): Map<String, List<ChatReaction>> {
        val rows = sets?.optJSONObject("reaction") ?: return emptyMap()
        val byMessage = HashMap<String, LinkedHashMap<String, MutableList<String>>>()
        for (key in rows.keys()) {
            if (!rows.optBoolean(key)) continue
            val bar = key.indexOf('|')
            if (bar < 0) continue
            val entity = key.substring(0, bar)
            val r = runCatching { JSONObject(key.substring(bar + 1)) }.getOrNull() ?: continue
            if (r.optString("target_type") != "message" || r.optString("target_id") != entity) continue
            val emoji = r.optString("emoji").takeIf { it.isNotBlank() && it != "null" } ?: continue
            val member = r.optString("member_id").takeIf { it.isNotBlank() && it != "null" } ?: continue
            val list = byMessage.getOrPut(entity) { LinkedHashMap() }.getOrPut(emoji) { ArrayList() }
            if (member !in list) list.add(member)
        }
        return byMessage.mapValues { (_, emojis) ->
            emojis.map { (emoji, members) -> ChatReaction(emoji, members.sorted()) }
                .sortedWith(compareByDescending<ChatReaction> { it.memberIds.size }.thenBy { it.emoji })
        }
    }

    /** Whether [memberId] already chose [emoji], so the next tap removes it. */
    fun chosen(reactions: List<ChatReaction>, emoji: String, memberId: String?): Boolean =
        memberId != null && reactions.any { it.emoji == emoji && memberId in it.memberIds }
}
