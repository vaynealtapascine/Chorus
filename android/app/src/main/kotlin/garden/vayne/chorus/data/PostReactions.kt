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
}
