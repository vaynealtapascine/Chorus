package garden.vayne.chorus.data

/**
 * One action as several of our members at once (D-142): hold a heart, a reaction or a poll option
 * and choose who does it. Each member's part is still its own op (`post.react`, `reaction.add`,
 * `poll.vote`), exactly as if each had tapped it, so nothing new is stored or revealed.
 */
object ManyMembers {
    data class Change(val add: List<String>, val remove: List<String>) {
        val empty get() = add.isEmpty() && remove.isEmpty()
    }

    /** Who to add and who to take back, in [order], when [had] have it and [chosen] should. */
    fun change(order: List<String>, had: Set<String>, chosen: Set<String>): Change =
        Change(order.filter { it in chosen && it !in had }, order.filter { it in had && it !in chosen })

    /**
     * The votes to write when [chosen] (of [members]) should pick [option]: each member whose
     * choice changes, with their whole next choice. Single choice moves a vote to [option];
     * multiple choice adds it beside the others; leaving someone out takes only [option] back.
     */
    fun votes(poll: Poll, state: PollState, parentId: String, members: List<String>, option: String,
        chosen: Set<String>): List<Pair<String, List<String>>> = members.mapNotNull { id ->
        val now = state.choices(parentId, id)
        when {
            id in chosen && option !in now -> id to (if (poll.multiple) now + option else listOf(option))
            id !in chosen && option in now -> id to (now - option)
            else -> null
        }
    }
}
