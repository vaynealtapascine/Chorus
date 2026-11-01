package garden.vayne.chorus.data

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ChatPresentationTest {
    @org.junit.Test fun draftPreviewWinsAndBlankDraftAllowsTyping() {
        org.junit.Assert.assertEquals("Draft: tea later", ChatPresentation.activityPreview("  tea\nlater  ", "Kai is typing…"))
        org.junit.Assert.assertEquals("Kai is typing…", ChatPresentation.activityPreview(" ", "Kai is typing…"))
        org.junit.Assert.assertNull(ChatPresentation.activityPreview(null, null))
        org.junit.Assert.assertNull(ChatPresentation.activityPreview("\n", " "))
    }

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
        val poll = Poll("Hidden question", emptyList(), false)
        assertEquals("Content warning: sensitive", ChatPresentation.preview(message.copy(cw = "sensitive"), poll))
        assertEquals("Poll · Hidden question", ChatPresentation.preview(message, poll))
    }

    @Test fun listReplyPinAndActionPreviewsCannotRevealBodyOrForwardedSpoilers() {
        val entities = """[{"type":"spoiler","offset":0,"length":6}]"""
        val hidden = message.copy(text = "secret plan", entities = entities)
        assertEquals("▪▪▪▪▪▪ plan", ChatPresentation.preview(hidden))
        assertEquals("Poll · ▪▪▪▪▪▪ plan", ChatPresentation.preview(hidden, Poll(null, emptyList(), false)))
        val forward = ChatForward.parse(org.json.JSONArray("""[{"message_id":"source","text":"secret plan","entities":$entities}]""")).single()
        assertEquals("spoiler", org.json.JSONArray(forward.entities).getJSONObject(0).getString("type"))
        assertEquals("Forwarded · ▪▪▪▪▪▪ plan", ChatPresentation.preview(hidden.copy(entities = "[]", forward = listOf(forward))))
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
            next.copy(segments = listOf(ChatSegment(0, 5, listOf("other")))))) {
            assertFalse(ChatPresentation.grouped(message, different, zone))
        }
        assertFalse(ChatPresentation.grouped(message.copy(occurredAt = 86_399_000), next.copy(occurredAt = 86_401_000), zone))
    }

    @Test fun coreComposedSingleAuthorMessagesStillGroupWithTheirOrdinarySegment() {
        val model = Model(listOf(Member("fox", "Fox", null, null, "#aabbcc", emptyList(), null, false)),
            emptyList(), emptyMap(), emptyList(), null, emptyList())
        val payload = ChatCompose.payload(model, "c", "fox", "Hello", "", "all", emptySet(), "internal")
        val rows = payload.getJSONArray("segments")
        val segments = (0 until rows.length()).map { index -> rows.getJSONObject(index).let { row ->
            val authors = row.getJSONArray("authors")
            ChatSegment(row.getInt("offset"), row.getInt("length"), (0 until authors.length()).map { authors.getString(it) })
        } }
        assertTrue(segments.isNotEmpty()) // Real core payload, not a hand-written empty fixture.
        val composed = message.copy(text = payload.getString("text"), segments = segments)
        assertTrue(ChatPresentation.grouped(composed, composed.copy(id = "next", occurredAt = 2_000), zone))
        assertFalse(ChatPresentation.grouped(composed, composed.copy(id = "mixed",
            segments = segments + ChatSegment(0, 5, listOf("someone-else"))), zone))
    }

    @Test fun mentionCompletionUsesOnlyExistingTargets() {
        assertEquals(6, ChatPresentation.frontMentionStart("hello @fr"))
        assertEquals(0, ChatPresentation.frontMentionStart("@"))
        for (text in listOf("@everyone", "email@fr", "\\@fr", "@front ", "@member")) assertNull(ChatPresentation.frontMentionStart(text))
    }

    @Test fun configuredLayersDoNotInferLegacyMeaningOrFallbackPresence() {
        val named = NamedFronts("{}", listOf(FrontLayer("independent", "Steering", 0, false)), emptyMap(), emptyMap(), "body")
        val legacy = Entry("member", "fox", "front", true)
        val model = Model(emptyList(), emptyList(), emptyMap(), listOf(legacy), null, emptyList(), namedFronts = named)
        assertEquals("Steering", ChatPresentation.layer(model, legacy.copy(level = "independent")))
        assertNull(ChatPresentation.layer(model, legacy))
        assertTrue(ChatPresentation.presence(model, "channel").isEmpty())
    }

    @Test fun catchUpKeepsReaderIdentityAndTimestampTies() {
        val rows = listOf(message, message.copy(id = "b"), message.copy(id = "c", occurredAt = 2_000))
        val marks = listOf(ReadMark("fox", 1_000, "a"), ReadMark("another", 3_000, "z"))
        val snapshot = ChatPresentation.catchUp(rows, marks, "fox")
        assertEquals(linkedSetOf("b", "c"), snapshot.unreadIds)
        assertEquals("b", snapshot.firstUnread)
        assertEquals("fox", snapshot.mark?.member)
        assertEquals(setOf("a", "b", "c"), ChatPresentation.catchUp(rows, marks, "not-marked").unreadIds)
        assertNull(ChatPresentation.catchUp(emptyList(), marks, "fox").firstUnread)
        val advanced = ChatPresentation.catchUp(rows, listOf(ReadMark("fox", 3_000, "z")), "fox")
        assertTrue(advanced.unreadIds.isEmpty())
        assertEquals(setOf("b", "c"), snapshot.unreadIds)
        assertTrue(ChatPresentation.catchUp(rows, listOf(ReadMark("", 3_000, "z")), "").unreadIds.isEmpty())
    }

    @Test fun unreadMentionsNeedAnExplicitMemberEntity() {
        val tagged = message.copy(entities = """[{"type":"mention","target_type":"member","target_id":"fox"}]""")
        assertTrue(ChatPresentation.mentionsMember(tagged, "fox"))
        assertFalse(ChatPresentation.mentionsMember(tagged, "someone-else"))
        assertFalse(ChatPresentation.mentionsMember(message.copy(text = "@fox"), "fox"))
        assertFalse(ChatPresentation.mentionsMember(message.copy(entities = """[{"type":"mention","target_type":"front"}]"""), "fox"))
        assertFalse(ChatPresentation.mentionsMember(message.copy(entities = "invalid"), "fox"))
    }

    @Test fun listDatesUseTheProvidedCalendarZone() {
        assertEquals("Yesterday", ChatPresentation.listTimeLabel(0, LocalDate.of(1970, 1, 2), zone))
        assertEquals("Yesterday", ChatPresentation.listTimeLabel(0, LocalDate.of(1970, 1, 1), ZoneId.of("America/Los_Angeles")))
    }

    @Test fun labelsReflectOfflineStateAndCalendarDates() {
        assertEquals("Waiting for network…", ChatPresentation.subtitle(true, "Fox", 4, "Internal"))
        assertEquals("reading as Fox · 4 unread", ChatPresentation.subtitle(false, "Fox", 4, "Internal"))
        assertEquals("Internal", ChatPresentation.subtitle(false, null, 0, "Internal"))
        assertEquals("Today", ChatPresentation.dateLabel(0, LocalDate.of(1970, 1, 1), zone))
        assertEquals("Yesterday", ChatPresentation.dateLabel(0, LocalDate.of(1970, 1, 2), zone))
    }
}
