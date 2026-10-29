package garden.vayne.chorus.data

/** Device-local navigation choices are names, never enum ordinals. */
object DeviceNavigation {
    val systemDefault = listOf("Members", "Home", "Chat", "Journal")
    val personDefault = listOf("Journal", "Chat", "People")
    fun available(person: Boolean) = if (person) personDefault else systemDefault + listOf("People", "History")
    fun resolve(raw: String?, person: Boolean): List<String> {
        if (raw == null) return if (person) personDefault else systemDefault
        val resolved = raw.split(',').filter { it in available(person) }.distinct().take(4)
        return if (raw.isNotBlank() && resolved.isEmpty()) resolve(null, person) else resolved
    }
}
