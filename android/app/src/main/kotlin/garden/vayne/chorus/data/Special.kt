package garden.vayne.chorus.data

/**
 * The system's own Handover and Notes channels (D-132, D-162; docs/handoff/d132-clients.md §1, web's
 * special.ts). They are ordinary channels in the account's own internal space, told apart by
 * `special`, never by name. A person's (singlet's) internal space exists only to hold Notes (D-093),
 * so it is never shown as a space: only the Notes chat is, as one row.
 */
object Special {
    const val HANDOVER = "handover"
    const val NOTES = "notes"

    /** The line under a special chat's name; a person has one member, so Notes has none to say it for. */
    fun subtitle(special: String?, person: Boolean): String? = when (special) {
        HANDOVER -> "notes between fronts"
        NOTES -> if (person) null else "saved for every member"
        else -> null
    }

    /** What a chat's notification level is until someone sets one: Handover rings for everything, Notes
     * stays quiet (D-162); [fallback] is any other chat's default. */
    fun defaultLevel(special: String?, fallback: String): String = when (special) {
        HANDOVER -> "all"
        NOTES -> "none"
        else -> fallback
    }

    /** Whether [spaceId] is a person's own internal space: it holds only Notes and shows as nothing but that row. */
    fun isPersonHome(model: Model, spaceId: String?): Boolean = spaceId != null && spaceId in model.personHomes

    /** Our live Handover and Notes channels, by role: in a space of ours of kind internal, at most one of each. */
    fun channel(model: Model, special: String): ChatChannel? {
        val homes = model.spaces.filter { it.kind == "internal" && it.own }.mapTo(HashSet()) { it.id }
        return model.channels.firstOrNull { it.special == special && it.kind == "text" && it.spaceId in homes }
    }

    /** The "#from chats" chip's value: no tag can hold a `#`, so it never clashes with one. */
    const val FROM_CHATS = "#from chats"

    /** Whether a Notes message shows under the chosen chip ("" is All): it carries the tag, or for
     * "#from chats" it was forwarded from another chat (`forward_of_id`). */
    fun noteMatches(message: ChatMessage, tags: List<String>, filter: String): Boolean =
        filter.isEmpty() || if (filter == FROM_CHATS) message.forwardOf != null else filter in tags
}
