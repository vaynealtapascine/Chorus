package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfilePresentationTest {
    private fun member(id: String) = Member(id, id, null, null, "#aaaaaa", emptyList(), null, false)
    private fun post(id: String, kind: String, authors: List<String>, repostOf: String? = null) =
        JournalPost(id, kind, authors, null, "", 0L, null, "private", null, null, emptyList(), repostOf = repostOf)

    @Test fun frontStatusNamesTheInnermostLayerAndWhenThisStretchBegan() {
        val model = Model(listOf(member("fox"), member("kai")), emptyList(), emptyMap(),
            listOf(Entry("member", "fox", "present", false), Entry("member", "fox", "front", true),
                Entry("member", "kai", "cocon", false)), 100L, emptyList(),
            frontIntervals = listOf(
                FrontInterval("old", "member", "fox", "front", true, 10L, 50L),
                FrontInterval("now", "member", "fox", "front", true, 400L, null)))
        assertEquals(ProfilePresentation.FrontStatus("Fronting", 400L), ProfilePresentation.frontStatus(model, "fox"))
        // no open interval of their own: the front's own start stands in
        assertEquals(ProfilePresentation.FrontStatus("Co-conscious", 100L), ProfilePresentation.frontStatus(model, "kai"))
        assertNull(ProfilePresentation.frontStatus(model, "nobody"))
    }

    @Test fun durationReadsLikeAClock() {
        assertEquals("just now", ProfilePresentation.duration(30_000))
        assertEquals("45m", ProfilePresentation.duration(45 * 60_000L))
        assertEquals("2h 04m", ProfilePresentation.duration((2 * 60 + 4) * 60_000L))
        assertEquals("3d 2h", ProfilePresentation.duration(((3 * 24 + 2) * 60 + 59) * 60_000L))
        assertEquals("just now", ProfilePresentation.duration(-5_000))
    }

    @Test fun audienceLineSaysWhoCanOpenTheProfile() {
        assertEquals("Only our system sees this profile", ProfilePresentation.audienceLine("private", false))
        assertEquals("Only you see this profile", ProfilePresentation.audienceLine("private", true))
        assertEquals("Followers see this profile", ProfilePresentation.audienceLine("followers", false))
        assertEquals("Everyone on the server sees this profile", ProfilePresentation.audienceLine("server", false))
    }

    @Test fun countsSeparatePostsFromEntriesAndSkipReposts() {
        val model = Model(listOf(member("fox")), emptyList(), emptyMap(), emptyList(), null, emptyList(),
            posts = listOf(post("a", "note", listOf("fox")), post("b", "note", listOf("fox", "kai")),
                post("c", "entry", listOf("fox")), post("d", "note", listOf("fox"), repostOf = "x"),
                post("e", "note", listOf("kai"))),
            relationships = listOf(MemberRelationship("r", "fox", "member", "kai", null, "t", null),
                MemberRelationship("s", "kai", "member", "fox", null, "t", null)))
        assertEquals(ProfilePresentation.Counts(2, 1, 1), ProfilePresentation.counts(model, "fox"))
    }
}
