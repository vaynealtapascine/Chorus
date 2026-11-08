package garden.vayne.chorus.data

object JournalPresentation {
    /** Narrowest to widest: who can read a post. */
    val AUDIENCES = listOf("private", "followers", "server")

    /** Whether moving a post from [from] to [to] lets more people read it. An audience this app
     * doesn't know (a bucket, say) counts as wider than private, so the warning is never skipped. */
    fun widens(from: String, to: String): Boolean {
        val before = AUDIENCES.indexOf(from)
        return if (before < 0) to != "private" else AUDIENCES.indexOf(to) > before
    }

    fun audience(mode: String, person: Boolean = false): String = when (mode) {
        "private" -> if (person) "Only me" else "Only our system"
        "friends" -> "Friends"
        "followers" -> "Followers"
        "following" -> "Following"
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

    /** Who was here when a post was written, from Body's saved intervals: "while Amarynthe was
     * fronting, Nairon co-conscious". Members only, primary first; null when nobody was recorded. */
    fun frontContext(intervals: List<FrontInterval>, at: Long, name: (String) -> String?): String? {
        fun at(level: String) = intervals.filter { it.subjectType == "member" && it.level == level &&
            it.startAt <= at && (it.endAt ?: Long.MAX_VALUE) > at }
            .sortedWith(compareByDescending<FrontInterval> { it.isPrimary }.thenBy { it.startAt })
            .mapNotNull { name(it.subjectId) }.distinct()
        val fronting = at("front")
        val cocon = at("cocon")
        if (fronting.isEmpty() && cocon.isEmpty()) return null
        val lead = fronting.takeIf { it.isNotEmpty() }?.let {
            "while ${it.joinToString(" & ")} ${if (it.size == 1) "was" else "were"} fronting"
        }
        val aware = cocon.takeIf { it.isNotEmpty() }?.let { "${it.joinToString(" & ")} co-conscious" }
        return lead?.let { listOfNotNull(it, aware).joinToString(", ") }
            ?: "while ${cocon.joinToString(" & ")} ${if (cocon.size == 1) "was" else "were"} co-conscious"
    }
}
