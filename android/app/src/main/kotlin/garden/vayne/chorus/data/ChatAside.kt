package garden.vayne.chorus.data

/**
 * The audience bar over a shared space's composer (Figma F5.4/F5.16): "Everyone here", or an aside
 * only our own system sees (`system_only`, SPEC §5.3). A singlet has no system to keep it to.
 */
object ChatAside {
    fun offered(spaceKind: String?, person: Boolean): Boolean = spaceKind == "shared" && !person

    private fun ours(system: String?) = system?.takeIf { it.isNotBlank() } ?: "your system"

    /** "Everyone here · 3 systems", "Everyone here · 2 systems · 1 person"; just "Everyone here" while unknown. */
    fun everyone(accounts: List<SpaceAccount>?): String {
        val systems = accounts.orEmpty().count { it.isSystem }
        val people = accounts.orEmpty().size - systems
        return (listOf("Everyone here") +
            listOfNotNull(
                systems.takeIf { it > 0 }?.let { "$it ${if (it == 1) "system" else "systems"}" },
                people.takeIf { it > 0 }?.let { "$it ${if (it == 1) "person" else "people"}" })).joinToString(" · ")
    }

    fun on(system: String?): String = "Aside · only ${ours(system)} will see this"

    fun placeholder(system: String?): String = "Aside to ${ours(system)}…"

    /** Beside an aside's author: ours just "aside"; another member's says who it stays with. */
    fun tag(own: Boolean, system: String?): String = if (own) "aside" else "aside · only ${ours(system)}"

    /** After sending, an aside stays on (the bar shows it); any other audience goes back to everyone. */
    fun afterSend(audience: String): String = if (audience == "system_only") audience else "all"
}
