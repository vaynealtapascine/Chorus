package garden.vayne.chorus.data

/**
 * The New message page (Figma F5.18) and the New shared space page (F5.10): who is listed, in what
 * order, and what the chat list's round button starts from each folder (F5.22).
 */
object NewChat {
    /** "shared" when [folder] holds only shared spaces (the Shared tab, or a folder of our own that is
     * every shared space and nothing else), else "message". */
    fun startFrom(folder: String?, settings: ChatFolders.Settings): String {
        val custom = folder?.let(settings::custom)
        val shared = folder == "shared" || custom != null && custom.kinds == setOf("shared") && custom.chats.isEmpty()
        return if (shared) "shared" else "message"
    }

    private fun matches(query: String, vararg names: String?): Boolean {
        val q = query.trim()
        return q.isEmpty() || names.any { it?.contains(q, ignoreCase = true) == true }
    }

    /**
     * Our members one may write to, without [speaker]: those here now first, in the order the front
     * lists them, then everyone else in member order. A guest another account added is left out.
     */
    fun members(model: Model, accountId: String, speaker: String?, query: String): List<Member> {
        val ours = model.active.filter { (it.createdByAccountId == null || it.createdByAccountId == accountId) && it.id != speaker &&
            matches(query, it.shownName, it.name, *it.sigils.toTypedArray(), model.handle(it.id)) }
        val here = ChatPresentation.presence(model, null).filter { it.subjectType == "member" }.map { it.subjectId }.distinct()
        val rank = here.withIndex().associate { (i, id) -> id to i }
        return ours.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
    }

    /** What a member's row says under the name: where they are now, else their pronouns. */
    fun memberLine(model: Model, member: Member): String? {
        val entry = ChatPresentation.presence(model, null).firstOrNull { it.subjectType == "member" && it.subjectId == member.id }
        return ChatPresentation.layer(model, entry) ?: member.pronouns?.takeIf { it.isNotBlank() }
    }

    fun friends(accounts: List<SpaceAccount>, query: String): List<SpaceAccount> =
        accounts.filter { matches(query, it.shownName, it.handle) }

    /** Under a friend's name: who is fronting there, as far as they share it, else what they are. */
    fun friendLine(account: SpaceAccount, fronting: List<String>?): String =
        fronting?.let(FriendFronts::line) ?: if (account.isSystem) "a system" else "one person"

    fun <T> toggle(list: List<T>, item: T): List<T> = if (item in list) list - item else list + item

    /** "2 systems selected", "1 person selected", "3 selected" (a mix). */
    fun selectedLabel(chosen: List<SpaceAccount>): String? {
        if (chosen.isEmpty()) return null
        val systems = chosen.count { it.isSystem }
        val noun = when (systems) {
            chosen.size -> if (systems == 1) "system" else "systems"
            0 -> if (chosen.size == 1) "person" else "people"
            else -> null
        }
        return listOfNotNull(chosen.size.toString(), noun, "selected").joinToString(" ")
    }

    /** A first name for the new space: us and who we picked ("Lanterns, Junipers & Driftwood"). */
    fun spaceName(own: String?, chosen: List<SpaceAccount>): String {
        val names = listOfNotNull(own?.takeIf { it.isNotBlank() }) + chosen.map { it.shownName }
        val name = when (names.size) {
            0 -> ""
            1 -> names[0]
            else -> names.dropLast(1).joinToString(", ") + " & " + names.last()
        }
        return name.take(80)
    }
}
