package garden.vayne.chorus.data

/**
 * The attach sheet's selection (F5.8): picked in tap order, numbered like an album, at most
 * [MAX] per message so one send stays one reasonable upload.
 */
object AttachPicks {
    const val MAX = 10

    /** Tap a tile: deselect it, or add it at the end while there is room ([already] counts files in the composer). */
    fun <T> toggle(picked: List<T>, item: T, already: Int = 0): List<T> = when {
        item in picked -> picked - item
        already + picked.size >= MAX -> picked
        else -> picked + item
    }

    /** "1", "2"… on a picked tile; null when it isn't picked. */
    fun <T> number(picked: List<T>, item: T): String? = picked.indexOf(item).takeIf { it >= 0 }?.let { "${it + 1}" }

    /** A video's length on its tile: "0:07", "12:30", "1:02:03". */
    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** "Add 3", or "Add" while nothing is picked. */
    fun addLabel(count: Int): String = if (count > 0) "Add $count" else "Add"

    /** The short type label on a file tile in the composer tray: "PDF", "ZIP", or "FILE". */
    fun extension(name: String): String =
        name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 && it != name }?.uppercase() ?: "FILE"

    /** The sheet's count once something is picked (F5.8): "1 selected". */
    fun selectedLabel(count: Int): String? = if (count > 0) "$count selected" else null

    /**
     * The Journal tile (F5.8): our own posts and entries one can link, newest first; replies and
     * reposts stay out (a link to them shows the original anyway).
     */
    fun journalChoices(posts: List<JournalPost>, limit: Int = 40): List<JournalPost> =
        posts.filter { it.replyTo == null && it.repostOf == null }.sortedByDescending { it.occurredAt }.take(limit)

    /** A post's link added to the draft (D-109 shows it as an embed): on its own after any text. */
    fun withLink(draft: String, link: String): String = when {
        draft.isBlank() -> link
        draft.last().isWhitespace() -> draft + link
        else -> "$draft $link"
    }
}
