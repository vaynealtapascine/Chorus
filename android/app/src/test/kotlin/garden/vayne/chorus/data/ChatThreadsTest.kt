package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatThreadsTest {
    private fun msg(id: String, channel: String, author: String, at: Long) =
        ChatMessage(id, channel, listOf(author), "x", at, null, "all", emptySet(), "acct", null, emptyList())

    @Test fun summarisesRepliesAndRecentRepliersUnderTheParent() {
        val general = ChatChannel("general", "home", "text", "General", null)
        val thread = ChatChannel("p1", "home", "thread", "Thread", "p1")
        val replies = listOf(msg("r1", "p1", "kai", 1), msg("r2", "p1", "rin", 2), msg("r3", "p1", "kai", 3))
        val search = (replies + msg("p1", "general", "ash", 0)).map { SearchDocument(it.id, "Messages", it.occurredAt, it.text, channelId = it.channelId) }
        val model = Model(emptyList(), emptyList(), emptyMap(), emptyList(), null, emptyList(),
            spaces = listOf(ChatSpace("home", "internal", "Home")), channels = listOf(general, thread),
            chatMessages = mapOf("p1" to replies), searchMessages = search)
        val summary = ChatThreads.summaries(model)
        assertEquals(setOf("p1"), summary.keys)
        assertEquals(3, summary["p1"]!!.replies)
        assertEquals(listOf("kai", "rin"), summary["p1"]!!.lastRepliers)
        assertEquals(general, ChatThreads.parentChannel(model, thread))
        assertNull(ChatThreads.parentChannel(model, general))
        assertEquals("1 reply", ChatThreads.repliesLabel(1))
        assertEquals("3 replies", ChatThreads.repliesLabel(3))
    }
}
