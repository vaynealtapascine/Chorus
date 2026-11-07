package garden.vayne.chorus.data

/** Device-local navigation choices are names, never enum ordinals. */
object DeviceNavigation {
    // Notifications sits beside Fronting and Chat (D-127, Figma F11); Members and Friends wait in More
    val systemDefault = listOf("Home", "Notifications", "Chat", "Journal")
    val personDefault = listOf("Journal", "Notifications", "Chat", "People")
    fun available(person: Boolean) = if (person) personDefault else systemDefault + listOf("Members", "People", "History")
    fun resolve(raw: String?, person: Boolean): List<String> {
        if (raw == null) return if (person) personDefault else systemDefault
        val resolved = raw.split(',').filter { it in available(person) }.distinct().take(4)
        return if (raw.isNotBlank() && resolved.isEmpty()) resolve(null, person) else resolved
    }
}
