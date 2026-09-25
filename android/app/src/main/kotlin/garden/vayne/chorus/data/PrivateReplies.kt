package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

/** Select or create a two-member DM within an internal space. */
object PrivateReplies {
    fun existing(channels: List<ChatChannel>, spaceId: String, speakerId: String, authorId: String): ChatChannel? {
        if (speakerId == authorId) return null
        val pair = setOf(speakerId, authorId)
        return channels.firstOrNull { it.spaceId == spaceId && it.kind == "member_dm" && it.memberIds.size == 2 &&
            it.memberIds.toSet() == pair }
    }

    fun createPayload(spaceId: String, speaker: Member, author: Member): JSONObject {
        require(speaker.id != author.id) { "Choose another member to reply privately." }
        return JSONObject().put("space_id", spaceId).put("kind", "member_dm")
            .put("name", "${speaker.name} & ${author.name}")
            .put("member_ids", JSONArray().put(speaker.id).put(author.id))
    }
}
