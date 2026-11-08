package garden.vayne.chorus.data

/** Device-local navigation choices are names, never enum ordinals. */
object DeviceNavigation {
    // Notifications sits beside Fronting and Chat (D-127, Figma F11); Members and Friends wait in More
    val systemDefault = listOf("Home", "Notifications", "Chat", "Journal")
    val personDefault = listOf("Journal", "Notifications", "Chat", "People")
    /** Home (D-130) is stored as "Today": "Home" was already Fronting's name. It is on no default
     * bar; Chorus opens on it, and it can be added. */
    const val HOME = "Today"
    fun available(person: Boolean) = (if (person) personDefault else systemDefault + listOf("Members", "People", "History")) + HOME
    /** At most this many sit on the bar; More always follows them. */
    const val MAX = 4

    /** Edit bottom bar's rows (F0.3's pattern): the bar's destinations in order, switched on, then the rest off. */
    fun editable(selected: List<String>, person: Boolean): List<Pair<String, Boolean>> =
        selected.map { it to true } + available(person).filter { it !in selected }.map { it to false }

    /** The bar a set of rows makes: the switched-on ones, in their order, never more than [MAX]. */
    fun chosen(rows: List<Pair<String, Boolean>>): List<String> = rows.filter { it.second }.map { it.first }.take(MAX)

    fun resolve(raw: String?, person: Boolean): List<String> {
        if (raw == null) return if (person) personDefault else systemDefault
        val resolved = raw.split(',').filter { it in available(person) }.distinct().take(MAX)
        return if (raw.isNotBlank() && resolved.isEmpty()) resolve(null, person) else resolved
    }
}
