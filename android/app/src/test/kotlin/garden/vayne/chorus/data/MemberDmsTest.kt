package garden.vayne.chorus.data

import org.junit.Assert.*
import org.junit.Test

class MemberDmsTest {
    private fun member(id: String, archived: Boolean = false) = Member(id, id, null, null, "#fff", emptyList(), null, archived)

    @Test fun reusesOnlyTheExactParticipantSetInTheSameInternalSpace() {
        val channels = listOf(
            ChatChannel("other", "other", "member_dm", "", null, listOf("a", "b")),
            ChatChannel("text", "home", "text", "", null, listOf("a", "b")),
            ChatChannel("group", "home", "member_dm", "", null, listOf("a", "b", "c")),
            ChatChannel("pair", "home", "member_dm", "", null, listOf("b", "a")),
        )
        assertEquals("pair", MemberDms.existing(channels, "home", listOf("a", "b"))?.id)
        assertEquals("group", MemberDms.existing(channels, "home", listOf("c", "b", "a"))?.id)
        assertNull(MemberDms.existing(channels, "home", listOf("a", "c")))
    }

    @Test fun createsGroupMemberDmWithCanonicalParticipants() {
        val p = MemberDms.payload("home", listOf(member("c"), member("a"), member("b")))
        assertEquals("member_dm", p.getString("kind"))
        assertEquals("home", p.getString("space_id"))
        assertEquals("[\"a\",\"b\",\"c\"]", p.getJSONArray("member_ids").toString())
    }

    @Test fun rejectsSelfOnlyDuplicateAndArchivedParticipants() {
        for (participants in listOf(listOf(member("a")), listOf(member("a"), member("a")), listOf(member("a"), member("b", true)))) {
            assertThrows(IllegalArgumentException::class.java) { MemberDms.payload("home", participants) }
        }
    }

    @Test fun reuseIsDeterministicAndDoesNotAcceptMalformedDuplicateParticipants() {
        val duplicate = ChatChannel("0", "home", "member_dm", "", null, listOf("a", "b", "b"))
        val a = ChatChannel("a", "home", "member_dm", "", null, listOf("b", "a"))
        val z = a.copy(id = "z")
        assertEquals("a", MemberDms.existing(listOf(z, duplicate, a), "home", listOf("a", "b"))?.id)
        assertEquals("a", MemberDms.existing(listOf(a, duplicate, z), "home", listOf("b", "a"))?.id)
        assertThrows(IllegalArgumentException::class.java) { MemberDms.existing(listOf(a), "home", listOf("a", "a")) }
    }

    @Test fun picksUseLatestNamesAndRejectRemovedArchivedOrForeignMembers() {
        val latest = member("a").copy(displayName = "Latest", createdByAccountId = "own")
        assertEquals("Latest", MemberDms.selected(listOf(latest), listOf("a"), "own").single().shownName)
        for (active in listOf(emptyList(), listOf(latest.copy(archived = true)), listOf(latest.copy(createdByAccountId = "other")))) {
            assertThrows(IllegalStateException::class.java) { MemberDms.selected(active, listOf("a"), "own") }
        }
    }
}
