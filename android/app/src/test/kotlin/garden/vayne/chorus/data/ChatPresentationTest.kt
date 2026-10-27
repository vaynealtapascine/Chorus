package garden.vayne.chorus.data

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ChatPresentationTest {
    private val zone = ZoneId.of("UTC")
    private val message = ChatMessage("a", "c", listOf("fox"), "Hello", 1_000, null,
        "all", emptySet(), "me", null, emptyList())

    @Test fun foldersSeparateMemberDmsFromInternalChannels() {
        val home = ChatSpace("s", "internal", "Home")
        val channel = ChatChannel("c", "s", "text", "General", null)
        assertEquals(ChatPresentation.Folder.Internal, ChatPresentation.folder(channel, home))
        assertEquals(ChatPresentation.Folder.Direct, ChatPresentation.folder(channel.copy(kind = "dm"), home))
        assertEquals(ChatPresentation.Folder.Direct, ChatPresentation.folder(channel, home.copy(kind = "dm")))
        assertEquals(ChatPresentation.Folder.Shared, ChatPresentation.folder(channel, home.copy(kind = "shared")))
    }

    @Test fun warningPreviewNeverLeaksTheHiddenText() {
        assertEquals("Content warning: sensitive", ChatPresentation.preview(message.copy(cw = "sensitive")))
        assertEquals("No messages yet", ChatPresentation.preview(null))
    }

    @Test fun ownBubblesFollowTheActingMemberWithinTheAccount() {
        assertTrue(ChatPresentation.own(message, "me", "fox"))
        assertFalse(ChatPresentation.own(message, "me", "amaranth"))
        assertFalse(ChatPresentation.own(message, "another-account", "fox"))
        assertFalse(ChatPresentation.own(message, "me", null))
    }

    @Test fun groupBoundariesKeepIdentityAndPrivacyVisible() {
        val next = message.copy(id = "b", occurredAt = 2_000)
        assertTrue(ChatPresentation.grouped(message, next, zone))
        for (different in listOf(next.copy(authors = listOf("other")), next.copy(accountId = "other"),
            next.copy(channelId = "other"), next.copy(occurredAt = 400_000), next.copy(cw = "CW"),
            next.copy(replyTo = "parent"), next.copy(visibilityMode = "members", visibleMemberIds = setOf("fox")),
            next.copy(segments = listOf(ChatSegment(0, 5, listOf("fox")))))) {
            assertFalse(ChatPresentation.grouped(message, different, zone))
        }
        assertFalse(ChatPresentation.grouped(message.copy(occurredAt = 86_399_000), next.copy(occurredAt = 86_401_000), zone))
    }

    @Test fun labelsReflectOfflineStateAndCalendarDates() {
        assertEquals("Waiting for network…", ChatPresentation.subtitle(true, "Fox", 4, "Internal"))
        assertEquals("reading as Fox · 4 unread", ChatPresentation.subtitle(false, "Fox", 4, "Internal"))
        assertEquals("Internal", ChatPresentation.subtitle(false, null, 0, "Internal"))
        assertEquals("Today", ChatPresentation.dateLabel(0, LocalDate.of(1970, 1, 1), zone))
        assertEquals("Yesterday", ChatPresentation.dateLabel(0, LocalDate.of(1970, 1, 2), zone))
    }
}
