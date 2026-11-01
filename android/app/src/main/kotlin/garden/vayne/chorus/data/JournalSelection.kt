package garden.vayne.chorus.data

/** Ephemeral composer selection; null follows the current default speaker. */
object JournalSelection {
    fun authors(person: Boolean, selected: List<String>?, fallback: String): List<String> =
        if (person) listOf(fallback) else selected ?: listOf(fallback)
    fun unavailable(selected: List<String>, eligible: Set<String>): List<String> = selected.filterNot { it in eligible }
}
