package garden.vayne.chorus.data

import org.junit.Assert.*
import org.junit.Test

class DeviceNavigationTest {
    @Test fun defaultsPutJournalInTheBarAndFriendsRemainAvailable() {
        assertEquals(listOf("Home", "Notifications", "Chat", "Journal"), DeviceNavigation.resolve(null, false))
        assertTrue("People" in DeviceNavigation.available(false))
        assertTrue("Members" in DeviceNavigation.available(false))
        assertEquals(listOf("Journal", "Notifications", "Chat", "People"), DeviceNavigation.resolve(null, true))
    }
    @Test fun persistedNamesAreBoundedDeduplicatedAndAccountAppropriate() {
        assertEquals(listOf("Chat", "People", "Journal", "Members"), DeviceNavigation.resolve("Chat,Chat,bad,People,Journal,Members,Home", false))
        assertEquals(listOf("Journal", "Chat"), DeviceNavigation.resolve("Members,Home,Journal,Chat,History", true))
        assertEquals(DeviceNavigation.systemDefault, DeviceNavigation.resolve("Unknown", false))
        assertEquals(emptyList<String>(), DeviceNavigation.resolve("", false))
    }
    @Test fun homeIsOnNoDefaultBarButEitherKindCanAddIt() {
        assertFalse(DeviceNavigation.HOME in DeviceNavigation.resolve(null, false))
        assertFalse(DeviceNavigation.HOME in DeviceNavigation.resolve(null, true))
        assertEquals(listOf("Today", "Notifications", "Chat", "People"), DeviceNavigation.resolve("Today,Notifications,Chat,People", true))
        // "Home" stays Fronting's saved name, so a bar saved before D-130 keeps Fronting
        assertEquals(listOf("Today", "Home"), DeviceNavigation.resolve("Today,Home", false))
    }
}
