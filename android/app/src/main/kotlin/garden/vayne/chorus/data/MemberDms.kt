package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

/** Internal DMs use the existing member_dm contract, including group conversations. */
object MemberDms {
    /** Re-resolve picks against the current replica before writing or setting authorship. */
    fun selected(active: List<Member>, ids: List<String>, accountId: String): List<Member> = ids.map { id ->
        active.firstOrNull { it.id == id && !it.archived && (it.createdByAccountId == null || it.createdByAccountId == accountId) }
            ?: throw IllegalStateException("A selected member is no longer available.")
    }

    fun existing(channels: List<ChatChannel>, spaceId: String, participants: List<String>): ChatChannel? {
        val ids = participants.toSet()
        require(ids.size >= 2 && ids.size == participants.size) { "Choose a sender and at least one other member." }
        return channels.filter { it.spaceId == spaceId && it.kind == "member_dm" &&
            it.memberIds.size == ids.size && it.memberIds.toSet() == ids }.minByOrNull { it.id }
    }

    fun payload(spaceId: String, participants: List<Member>): JSONObject {
        require(participants.size >= 2 && participants.map { it.id }.distinct().size == participants.size) {
            "Choose a sender and at least one other member."
        }
        require(participants.none { it.archived }) { "Archived members cannot start a conversation." }
        return JSONObject().put("space_id", spaceId).put("kind", "member_dm")
            .put("name", participants.joinToString(" & ") { it.shownName })
            .put("member_ids", JSONArray(participants.map { it.id }.sorted()))
    }
}
