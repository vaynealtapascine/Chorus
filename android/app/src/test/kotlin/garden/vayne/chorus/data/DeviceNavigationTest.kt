package garden.vayne.chorus.data

import org.junit.Assert.*
import org.junit.Test

class DeviceNavigationTest {
    @Test fun defaultsPutJournalInTheBarAndFriendsRemainAvailable() {
        assertEquals(listOf("Members", "Home", "Chat", "Journal"), DeviceNavigation.resolve(null, false))
        assertTrue("People" in DeviceNavigation.available(false))
        assertEquals(listOf("Journal", "Chat", "People"), DeviceNavigation.resolve(null, true))
    }
    @Test fun persistedNamesAreBoundedDeduplicatedAndAccountAppropriate() {
        assertEquals(listOf("Chat", "People", "Journal", "Members"), DeviceNavigation.resolve("Chat,Chat,bad,People,Journal,Members,Home", false))
        assertEquals(listOf("Journal", "Chat"), DeviceNavigation.resolve("Members,Home,Journal,Chat,History", true))
        assertEquals(DeviceNavigation.systemDefault, DeviceNavigation.resolve("Unknown", false))
        assertEquals(emptyList<String>(), DeviceNavigation.resolve("", false))
    }
}
