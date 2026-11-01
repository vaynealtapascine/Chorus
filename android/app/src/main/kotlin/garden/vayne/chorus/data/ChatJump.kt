package garden.vayne.chorus.data

/** Tapping a reply, quote or forward (owner, 2026-11-02): which chat holds the original, if this reader can see it. */
object ChatJump {
    /** The loaded conversation first, then every chat shown now; null when the original isn't here (gone, hidden or too old). */
    fun channelOf(id: String, here: List<ChatMessage>, everywhere: Map<String, ChatMessage>): String? =
        (here.find { it.id == id } ?: everywhere[id])?.channelId
}
