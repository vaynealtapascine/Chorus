package garden.vayne.chorus.data

import org.junit.Assert.*
import org.junit.Test

class ChatAsideTest {
    private fun account(id: String, kind: String) = SpaceAccount(id, id, null, kind)

    @Test fun onlySystemsInSharedSpacesGetTheAsidePill() {
        assertTrue(ChatAside.offered("shared", person = false))
        assertFalse(ChatAside.offered("shared", person = true))
        assertFalse(ChatAside.offered("internal", person = false))
        assertFalse(ChatAside.offered("dm", person = false))
    }

    @Test fun theBarCountsSystemsAndPeople() {
        assertEquals("Everyone here", ChatAside.everyone(null))
        assertEquals("Everyone here · 3 systems", ChatAside.everyone(listOf("a", "b", "c").map { account(it, "system") }))
        assertEquals("Everyone here · 1 system · 2 people",
            ChatAside.everyone(listOf(account("a", "system"), account("b", "person"), account("c", "person"))))
        assertEquals("Everyone here · 1 person", ChatAside.everyone(listOf(account("b", "person"))))
    }

    @Test fun asideWordsNameOurSystem() {
        assertEquals("Aside · only The Lanterns will see this", ChatAside.on("The Lanterns"))
        assertEquals("Aside to your system…", ChatAside.placeholder(" "))
        assertEquals("aside", ChatAside.tag(true, "The Lanterns"))
        assertEquals("aside · only The Lanterns", ChatAside.tag(false, "The Lanterns"))
    }

    @Test fun anAsideStaysOnAfterSendingAndChosenMembersDoNot() {
        assertEquals("system_only", ChatAside.afterSend("system_only"))
        assertEquals("all", ChatAside.afterSend("members"))
        assertEquals("all", ChatAside.afterSend("all"))
    }
}
