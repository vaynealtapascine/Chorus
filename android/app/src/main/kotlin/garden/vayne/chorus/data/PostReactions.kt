package garden.vayne.chorus.data

import org.json.JSONObject

object PostReactions {
    const val HEART = "💜"

    /** Exact set element shared by add and remove, as required by the core projection. */
    fun payload(postId: String, memberId: String, emoji: String = HEART): JSONObject = JSONObject()
        .put("target_type", "post").put("target_id", postId).put("emoji", emoji).put("member_id", memberId)

    fun toggle(reactions: List<PostReaction>, memberId: String, memberName: String,
        emoji: String = HEART): List<PostReaction> =
        if (reactions.any { it.emoji == emoji && it.memberId == memberId })
            reactions.filterNot { it.emoji == emoji && it.memberId == memberId }
        else reactions + PostReaction(emoji, memberId, memberName)

    /** A post's reactions as the server lists them; one by a member this account may not see comes
     * with no member and no name (D-154) and still counts, as "Someone". */
    fun fromServer(post: JSONObject): List<PostReaction> {
        val rows = post.optJSONArray("reactions") ?: return emptyList()
        fun JSONObject.text(key: String) = optString(key).takeIf { has(key) && !isNull(key) && it.isNotBlank() && it != "null" }
        return (0 until rows.length()).mapNotNull { i -> rows.optJSONObject(i)?.let { row ->
            val emoji = row.text("emoji") ?: return@let null
            val memberId = row.text("member_id")
            PostReaction(emoji, memberId, memberId?.let { row.text("member_name") } ?: "Someone")
        } }
    }

    /** Only present, well-formed reaction set elements can become local reaction badges. */
    fun fromProjection(sets: JSONObject?, names: Map<String, String>): Map<String, List<PostReaction>> {
        val rows = sets?.optJSONObject("reaction") ?: return emptyMap()
        val result = HashMap<String, MutableList<PostReaction>>()
        for (key in rows.keys()) {
            if (!rows.optBoolean(key)) continue
            val bar = key.indexOf('|')
            if (bar < 0) continue
            val postId = key.substring(0, bar)
            val payload = runCatching { JSONObject(key.substring(bar + 1)) }.getOrNull() ?: continue
            if (payload.optString("target_type") != "post" || payload.optString("target_id") != postId) continue
            val emoji = payload.optString("emoji").takeIf { it.isNotBlank() && it != "null" } ?: continue
            val memberId = payload.optString("member_id").takeIf { it.isNotBlank() && it != "null" } ?: continue
            result.getOrPut(postId) { ArrayList() }.add(PostReaction(emoji, memberId, names[memberId] ?: "Someone"))
        }
        return result.mapValues { (_, reactions) -> reactions.sortedWith(compareBy<PostReaction> { it.emoji }.thenBy { it.memberId }) }
    }

    fun merge(local: List<PostReaction>, remote: List<PostReaction>): List<PostReaction> =
        remote + local.filter { own -> remote.none { it.emoji == own.emoji && it.memberId == own.memberId } }
}
