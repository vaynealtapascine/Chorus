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
    @Test fun editBottomBarListsTheBarFirstThenTheRestAndKeepsFourAtMost() {
        val rows = DeviceNavigation.editable(listOf("Chat", "Home"), false)
        assertEquals(listOf("Chat" to true, "Home" to true), rows.take(2))
        assertEquals(DeviceNavigation.available(false).toSet(), rows.map { it.first }.toSet())
        assertTrue(rows.drop(2).none { it.second })
        assertEquals(listOf("Chat", "Home"), DeviceNavigation.chosen(rows))
        val all = DeviceNavigation.available(false).map { it to true }
        assertEquals(DeviceNavigation.MAX, DeviceNavigation.chosen(all).size)
        // a switched-off row moved to the top stays off the bar
        assertEquals(listOf("Home"), DeviceNavigation.chosen(listOf("Journal" to false, "Home" to true)))
    }
}
