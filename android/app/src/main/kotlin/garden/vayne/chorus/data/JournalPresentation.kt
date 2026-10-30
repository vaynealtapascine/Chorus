package garden.vayne.chorus.data

object JournalPresentation {
    fun audience(mode: String, person: Boolean = false): String = when (mode) {
        "private" -> if (person) "Only me" else "Only our system"
        "followers" -> "Followers"
        "server" -> "Everyone on server"
        else -> "Chosen audience"
    }
    /** "now", "25m", "3h", "2d", then [older] (a date) after a week: the muted time after a name. */
    fun shortAgo(ms: Long, now: Long = System.currentTimeMillis(), older: (Long) -> String): String {
        val s = ((now - ms) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "now"
            s < 3600 -> "${s / 60}m"
            s < 86_400 -> "${s / 3600}h"
            s < 7 * 86_400 -> "${s / 86_400}d"
            else -> older(ms)
        }
    }

    /** `Journal entry · 2 min read · #tags`: the quiet meta line under an entry. */
    fun entryMeta(text: String, tags: List<String>): String =
        listOfNotNull("Journal entry", readTime(text), tags.takeIf { it.isNotEmpty() }?.joinToString(" ") { "#$it" })
            .joinToString(" · ")

    fun readTime(text: String): String {
        val words = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        return "${maxOf(1, (words + 199) / 200)} min read"
    }
}
