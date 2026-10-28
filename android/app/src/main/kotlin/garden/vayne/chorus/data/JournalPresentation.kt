package garden.vayne.chorus.data

object JournalPresentation {
    fun audience(mode: String, person: Boolean = false): String = when (mode) {
        "private" -> if (person) "Only me" else "Only our system"
        "followers" -> "Followers"
        "server" -> "Everyone on server"
        else -> "Chosen audience"
    }
    fun readTime(text: String): String {
        val words = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        return "${maxOf(1, (words + 199) / 200)} min read"
    }
}
