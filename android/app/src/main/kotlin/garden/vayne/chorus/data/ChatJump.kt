package garden.vayne.chorus.data

/** Tapping a reply, quote or forward (owner, 2026-11-02): which chat holds the original, if this reader can see it. */
object ChatJump {
    /** The loaded conversation first, then every chat shown now; null when the original isn't here (gone, hidden or too old). */
    fun channelOf(id: String, here: List<ChatMessage>, everywhere: Map<String, ChatMessage>): String? =
        (here.find { it.id == id } ?: everywhere[id])?.channelId

    /** The jump trail (D-112): a reply jump from [from] to [to] remembers [from], newest last, once. */
    fun push(trail: List<String>, from: String?, to: String): List<String> =
        if (from == null || from == to) trail else trail.filterNot { it == from } + from

    data class Back(val id: String, val rest: List<String>)

    /** Where the down arrow goes first: the newest start of a jump still in this chat (older ones that
     * are gone are skipped), and the trail left after it; null means the latest message. */
    fun back(trail: List<String>, present: Set<String>): Back? {
        val at = trail.indexOfLast { it in present }
        return if (at < 0) null else Back(trail[at], trail.subList(0, at))
    }
}
