package garden.vayne.chorus.data

import kotlinx.coroutines.flow.first
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

    /** The (space, channel) of the DM between [speaker] and [other], made if there is none yet. */
    suspend fun open(chorus: Chorus, speaker: Member, other: String): Pair<String, String> {
        val latest = chorus.model.value
        val home = latest.spaces.firstOrNull { it.kind == "internal" } ?: throw IllegalStateException("Your internal space is not available yet.")
        val participants = selected(latest.active, listOf(speaker.id, other), chorus.device?.accountId.orEmpty())
        val target = existing(latest.channels, home.id, participants.map { it.id })?.id
            ?: chorus.newId().also { id -> chorus.create("channel.create", id, payload(home.id, participants), scope = "space:${home.id}") }
        kotlinx.coroutines.withTimeout(15_000) { chorus.model.first { next -> next.channels.any { it.id == target && it.spaceId == home.id } } }
        return home.id to target
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
