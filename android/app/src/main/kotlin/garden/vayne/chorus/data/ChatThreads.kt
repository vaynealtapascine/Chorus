package garden.vayne.chorus.data

/** A thread under its parent message (SPEC §5.3): how many replies, and who answered last. */
data class ThreadSummary(val channel: ChatChannel, val replies: Int, val lastRepliers: List<String>)

/**
 * Threads are `thread` channels with a `parent_message_id`; one per parent, whose id is the
 * parent's id so every device opens the same one. They live under their parent, not in lists.
 */
object ChatThreads {
    /** parent message id → its thread. */
    fun summaries(model: Model): Map<String, ThreadSummary> {
        val threads = model.channels.filter { it.kind == "thread" && it.parentMessageId != null }
        if (threads.isEmpty()) return emptyMap()
        val ids = threads.mapTo(HashSet()) { it.id }
        val counts = HashMap<String, Int>()
        for (m in model.searchMessages) if (m.channelId in ids) counts[m.channelId!!] = (counts[m.channelId] ?: 0) + 1
        return threads.associate { thread ->
            val recent = model.chatMessages[thread.id].orEmpty()
            val repliers = LinkedHashSet<String>()
            for (m in recent.asReversed()) { for (a in m.authors) repliers.add(a); if (repliers.size >= 3) break }
            thread.parentMessageId!! to ThreadSummary(thread, counts[thread.id] ?: recent.size, repliers.take(3))
        }
    }

    /** The channel the thread's parent message was written in. */
    fun parentChannel(model: Model, thread: ChatChannel): ChatChannel? {
        val parent = thread.parentMessageId ?: return null
        val channelId = model.searchMessages.firstOrNull { it.id == parent }?.channelId ?: return null
        return model.channels.find { it.id == channelId }
    }

    fun repliesLabel(n: Int): String = if (n == 1) "1 reply" else "$n replies"
}
