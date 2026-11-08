package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemberSharingTest {
    private val friends = Audience("friends")
    private val role = MemberSharing.Field("f-role", "Role", Audience.PRIVATE)
    private val groups = MemberSharing.partGroups(listOf(role))

    @Test fun partsAreGroupedAsFigmaDrawsThemAndWriteEveryKey() {
        assertEquals(listOf("Name and picture", "Pronouns", "Description and fields"), groups.map { it.title })
        assertEquals(listOf("description", "birthday", "f-role"), groups[2].keys)
        assertEquals("Bio, Birthday, Role", groups[2].subtitle)
        val parts = MemberSharing.withAll(null, groups[0].keys, friends)
        assertEquals(setOf("name", "banner"), parts.keys().asSequence().toSet())
        assertEquals(friends, MemberSharing.summaryAudience(parts, groups[0]))
        assertFalse(MemberSharing.inherits(parts, groups[0]))
        // back to each part's default
        val cleared = MemberSharing.withAll(parts, groups[0].keys, null)
        assertEquals(0, cleared.length())
        assertTrue(MemberSharing.inherits(cleared, groups[0]))
    }

    @Test fun aGroupShowsOneAudienceOnlyWhenItsPartsAgree() {
        val names = mapOf("b1" to "Close friends")
        // name is private unless chosen (D-126); banner follows the profile
        assertNull(MemberSharing.summary(friends, null, groups[0], names))
        assertEquals("Friends", MemberSharing.summary(friends, null, groups[1], names))
        // the field's own default (private) differs from the profile's
        assertNull(MemberSharing.summary(friends, null, groups[2], names, listOf(role)))
        val all = MemberSharing.withAll(null, groups[2].keys, Audience("buckets", listOf("b1")))
        assertEquals("Close friends", MemberSharing.summary(friends, all, groups[2], names, listOf(role)))
        assertEquals("Only us", MemberSharing.summary(Audience.PRIVATE, null, groups[1], names))
        // mixed own choices have no single audience to tick
        val mixed = ProfileAudience.with(MemberSharing.withAll(null, groups[0].keys, friends), "banner", Audience("server"))
        assertNull(MemberSharing.summaryAudience(mixed, groups[0]))
    }

    @Test fun profileChoicesListEachBucketAndKeepAnOddCurrentOne() {
        val buckets = listOf("b1" to "Close friends", "b2" to "Work")
        assertEquals(listOf("private", "buckets", "buckets", "friends", "server"), MemberSharing.profileChoices(Audience.PRIVATE, buckets).map { it.mode })
        val odd = MemberSharing.profileChoices(Audience("followers"), buckets)
        assertEquals("followers", odd[odd.size - 2].mode)
        // a bucket that is gone reads as only us, not as an extra choice
        assertEquals(5, MemberSharing.profileChoices(Audience("buckets", listOf("gone")), buckets).size)
    }

    @Test fun namesComeFromActiveFollowersAndFriendsAreBothWays() {
        fun acct(id: String) = SpaceAccount(id, id, id.replaceFirstChar { it.uppercase() })
        val f = FollowList(following = listOf(FollowInfo("o1", acct("kit"), "active"), FollowInfo("o2", acct("ash"), "requested")),
            followers = listOf(FollowInfo("i1", acct("kit"), "active"), FollowInfo("i2", acct("ash"), "active"), FollowInfo("i3", acct("ren"), "active"),
                FollowInfo("i4", acct("old"), "ended")))
        assertEquals(listOf("Kit"), MemberSharing.friendNames(f))
        assertEquals(listOf("Ash", "Ren"), MemberSharing.bucketNames(f, mapOf("b1" to setOf("ash", "ren", "old")), listOf("b1")))
        assertEquals("Ash, Ren +1", MemberSharing.names(listOf("Ash", "Ren", "Kit")))
        assertNull(MemberSharing.names(emptyList()))
    }

    @Test fun frontingStatusIsTheAnnouncePolicyAndKeepsTheRest() {
        assertEquals("everyone", MemberSharing.announce(null).mode)
        assertEquals("Nobody", MemberSharing.announceLabel(MemberSharing.announce("""{"announce":"nobody"}"""), emptyMap()))
        val b = MemberSharing.announce("""{"announce":{"buckets":["b1","gone"]}}""")
        assertEquals("Close friends", MemberSharing.announceLabel(b, mapOf("b1" to "Close friends")))
        val next = MemberSharing.announcePolicy("""{"announce":"everyone","delay":true}""", "nobody")
        assertEquals("nobody", next.getString("announce"))
        assertTrue(next.getBoolean("delay"))
    }

    @Test fun fieldsSkipRemovedOnesAndSlotsNotYetMade() {
        val rows = JSONArray().put(JSONObject().put("id", "a").put("name", "Role").put("exists", true).put("default_visibility", JSONObject().put("mode", "friends")))
            .put(JSONObject().put("id", "b").put("name", "Website 2").put("exists", false))
            .put(JSONObject().put("id", "c").put("name", "Gone").put("exists", true))
        val out = MemberSharing.fields(rows, setOf("c"))
        assertEquals(listOf("a"), out.map { it.id })
        assertEquals(friends, out[0].default)
    }
}
