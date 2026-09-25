package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldMessagesTest {
    @Test fun keepsOnlyMessagesAndOrdersTheNextWake() {
        val held = HeldMessages.parse("""[
          {"id":"late","entity_id":"message-b","until":24000},
          {"id":"no-entity","entity_id":null,"until":22000},
          {"id":"first","entity_id":"message-a","until":21000}
        ]""")
        assertEquals(listOf("message-a", "message-b"), held.map { it.messageId })
        assertEquals(21000L, held.first().until)
    }

    @Test fun heldOpsDoNotKeepTheBackgroundSocketLeaseOpen() {
        assertTrue(HeldMessages.caughtUp(2UL, 2))
        assertFalse(HeldMessages.caughtUp(3UL, 2))
    }
}
