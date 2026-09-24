package garden.vayne.chorus.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatVisibilityTest {
    private val hidden = ChatMessage("m", "c", listOf("a"), "secret", 1, null, "members", setOf("kai"), "acct", null, emptyList())

    @Test
    fun memberVisibilityUsesFrontCoConOrExplicitViewer() {
        assertFalse(memberVisible(hidden, "internal", emptyList(), null))
        assertFalse(memberVisible(hidden, "internal", listOf(Entry("member", "kai", "present", false)), null))
        assertTrue(memberVisible(hidden, "internal", listOf(Entry("member", "kai", "cocon", false)), null))
        assertTrue(memberVisible(hidden, "internal", emptyList(), "kai"))
        assertTrue(memberVisible(hidden, "shared", emptyList(), null))
    }

    @Test
    fun oldForeignAsideDoesNotAppearFromLocalReplica() {
        val aside = hidden.copy(visibilityMode = "system_only", accountId = "other")
        assertFalse(accountVisible(aside, "shared", "acct"))
        assertTrue(accountVisible(aside.copy(accountId = "acct"), "shared", "acct"))
    }
}
