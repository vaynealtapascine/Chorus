package garden.vayne.chorus.data

import org.json.JSONObject

object ProfileHighlights {
    fun payload(memberId: String, postId: String): JSONObject = JSONObject()
        .put("profile_member_id", memberId).put("post_id", postId)

    fun fromProjection(sets: JSONObject?): Map<String, Set<String>> {
        val rows = sets?.optJSONObject("highlight") ?: return emptyMap()
        val result = HashMap<String, MutableSet<String>>()
        for (key in rows.keys()) {
            if (!rows.optBoolean(key)) continue
            val bar = key.indexOf('|')
            if (bar < 0) continue
            val memberId = key.substring(0, bar)
            val payload = runCatching { JSONObject(key.substring(bar + 1)) }.getOrNull() ?: continue
            if (payload.optString("profile_member_id") != memberId) continue
            val postId = payload.optString("post_id").takeIf { it.isNotBlank() && it != "null" } ?: continue
            result.getOrPut(memberId) { HashSet() }.add(postId)
        }
        return result
    }
}
