package garden.vayne.chorus.ui

import org.junit.Assert.*
import org.junit.Test

class NavigationDraftTest {
    @Test fun returningToOneChannelRestoresItsOwnPrivacyPollAndReplyWithoutChangingAnother() {
        val first = ChatDraft().apply {
            text.value = "A quiet plan"; author.value = "kai"; audience.value = "members"
            visibleTo.value = listOf("kai", "moss"); replyTo.value = "parent"; cw.value = "sleep"
            poll.value = true; multiple.value = true; options.clear(); options.addAll(listOf("Garden", "Library"))
        }
        val second = ChatDraft().apply { text.value = "Different conversation" }
        val saved = mapOf("first" to first.encode(), "second" to second.encode())
        val restored = saved.mapValues { ChatDraft.decode(it.value) }
        assertEquals("A quiet plan", restored.getValue("first").text.value)
        assertEquals("members", restored.getValue("first").audience.value)
        assertEquals(listOf("kai", "moss"), restored.getValue("first").visibleTo.value)
        assertEquals("parent", restored.getValue("first").replyTo.value)
        assertEquals("sleep", restored.getValue("first").cw.value)
        assertTrue(restored.getValue("first").poll.value)
        assertTrue(restored.getValue("first").multiple.value)
        assertEquals(listOf("Garden", "Library"), restored.getValue("first").options.toList())
        assertEquals("Different conversation", restored.getValue("second").text.value)
        assertEquals("all", restored.getValue("second").audience.value)
        assertNull(restored.getValue("second").replyTo.value)
    }
}
