package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDashboardTest {
    private fun stored(vararg s: Pair<String, Boolean>) = JSONArray(s.map { JSONObject().put("id", it.first).put("on", it.second) })

    @Test fun sectionsDefaultInFigmaOrderAndHandoverWaitsForD132() {
        val system = HomeDashboard.parse(null, person = false)
        assertEquals(HomeDashboard.SYSTEM, system.map { it.id })
        assertTrue(system.all { it.on })
        assertFalse("handover" in HomeDashboard.shown(system))
        assertFalse(HomeDashboard.editable(system).any { it.id == "handover" })
        assertEquals(listOf("write", "friends", "waiting", "new_from_friends"), HomeDashboard.shown(HomeDashboard.parse(null, person = true)))
    }

    @Test fun storedOrderWinsUnknownDropsAndMissingReturnsBesideItsNeighbour() {
        val raw = stored("today" to true, "waiting" to false, "bogus" to true, "fronting" to true, "today" to false)
        val sections = HomeDashboard.parse(raw, person = false)
        // quick_switch follows fronting, handover follows it, friends follows waiting, new_from_friends follows today
        assertEquals(listOf("today", "new_from_friends", "waiting", "friends", "fronting", "quick_switch", "handover"), sections.map { it.id })
        assertFalse(sections.first { it.id == "waiting" }.on)
        assertEquals(listOf("today", "new_from_friends", "friends", "fronting", "quick_switch"), HomeDashboard.shown(sections))
        // a person's list never takes a system's sections
        assertEquals(HomeDashboard.PERSON, HomeDashboard.parse(raw, person = true).map { it.id })
    }

    @Test fun eachReaderKeepsTheirOwnAndFallsBackToTheAccount() {
        val mine = stored("fronting" to false)
        val account = stored("waiting" to false)
        val byReader = mapOf<String, Any?>("fox" to mine)
        val fox = HomeDashboard.forReader(byReader, account, "fox", false)
        assertFalse(fox.first { it.id == "fronting" }.on)
        assertTrue(fox.first { it.id == "waiting" }.on)
        val nairon = HomeDashboard.forReader(byReader, account, "nairon", false)
        assertTrue(nairon.first { it.id == "fronting" }.on)
        assertFalse(nairon.first { it.id == "waiting" }.on)
        assertEquals("home.sections:fox", HomeDashboard.key("fox"))
        assertEquals("home.sections", HomeDashboard.key(null))
        val payload = HomeDashboard.payload("fox", HomeDashboard.parse(null, false))
        assertEquals("home.sections:fox", payload.getString("key"))
        assertEquals(HomeDashboard.SYSTEM.size, payload.getJSONArray("value").length())
        assertEquals(JSONObject.NULL, HomeDashboard.payload(null, null).get("value"))
    }

    @Test fun quickSwitchLeadsWithPinsThenRecentsThenEveryoneOurs() {
        val own = listOf("violet", "nairon", "amarynthe", "foxglove", "ash")
        assertEquals(listOf("foxglove", "violet", "nairon", "amarynthe"),
            HomeDashboard.quickSwitch(own, pins = listOf("foxglove", "gone"), recents = listOf("violet", "foxglove", "nairon"), limit = 4))
        assertEquals(own, HomeDashboard.quickSwitch(own, emptyList(), emptyList(), 10))
        assertEquals(emptyList<String>(), HomeDashboard.quickSwitch(own, emptyList(), emptyList(), -1))
    }

    private data class Row(val id: String, val unread: Int, val muted: Boolean = false, val archived: Boolean = false, val at: Long = 0)

    @Test fun waitingListsUnreadChatsNewestFirstWithoutMutedOrArchived() {
        val rows = listOf(Row("read", 0, at = 9), Row("general", 3, at = 5), Row("kit", 1, at = 7), Row("quiet", 4, muted = true, at = 8),
            Row("old", 2, archived = true, at = 6), Row("market", 1, at = 1))
        val shown = HomeDashboard.waiting(rows, { it.unread }, { it.muted }, { it.archived }, { it.at }, limit = 2)
        assertEquals(listOf("kit", "general"), shown.map { it.id })
    }

    private fun account(id: String, kind: String) = SpaceAccount(id, id, id.replaceFirstChar { it.uppercase() }, kind)
    private fun row(id: String, kind: String, view: FollowerView? = null, latest: SharedPost? = null, status: String = "active") =
        FriendRow(account(id, kind), FollowInfo("f$id", account(id, kind), status, createdAt = 1), null, view, latest)

    @Test fun friendsAroundFirstThenNotYetThenQuietThenNotSharing() {
        val now = 100L * 3_600_000
        val kit = SharedMember("Kit", null, level = "front")
        val rows = listOf(
            row("moss", "system", FollowerView(emptyList(), null, null, shared = false)),
            row("drift", "system", FollowerView(emptyList(), null, null)),
            row("idle", "system", FollowerView(emptyList(), null, null, since = now - 10)),
            row("junipers", "system", FollowerView(emptyList(), null, null, entries = listOf(kit), since = now - 5)),
            row("lanterns", "system", FollowerView(emptyList(), null, null, entries = listOf(kit), since = now - 1)),
            row("ren", "person", latest = SharedPost("p", "entry", null, "hi", null, now - 3_600_000, listOf("Ren"))),
            row("ash", "person", latest = SharedPost("q", "note", null, "hi", null, now - Friends.AROUND_MS - 1, listOf("Ash"))),
            row("asked", "system", FollowerView(emptyList(), null, null, entries = listOf(kit)), status = "requested"),
        )
        val shown = HomeDashboard.friends(rows, now)
        assertEquals(listOf("lanterns", "junipers", "drift", "ren", "idle", "moss"), shown.map { it.first.id })
        assertEquals(listOf(FriendCard.Around, FriendCard.Around, FriendCard.NotYet, FriendCard.Posted, FriendCard.Quiet, FriendCard.NotSharing),
            shown.map { it.second })
    }

    private val layers = listOf("front" to "Steering", "cocon" to "Co-conscious", "present" to "Present")
    private val names = mapOf("fox" to "Foxglove", "am" to "Amarynthe", "nai" to "Nairon", "vi" to "Violet")
    private fun e(id: String, level: String, primary: Boolean = false) = Entry("member", id, level, primary)

    @Test fun switchLineNamesTheInnerLayerThenTheNext() {
        val name = { x: Entry -> names[x.subjectId] }
        assertEquals("Foxglove started steering · Amarynthe co-conscious",
            HomeDashboard.switchLine(listOf(e("am", "cocon"), e("nai", "present"), e("fox", "front")), layers, name))
        assertEquals("Violet & Foxglove started steering", HomeDashboard.switchLine(listOf(e("fox", "front"), e("vi", "front", true)), layers, name))
        assertEquals("Nairon present", HomeDashboard.switchLine(listOf(e("nai", "present")), layers, name))
        assertEquals("No one fronting", HomeDashboard.switchLine(emptyList(), layers, name))
    }

    @Test fun todayMergesOurSwitchesAndPostsSinceMidnightNewestFirst() {
        val day = 1_000L
        val switches = listOf(
            SwitchRow("s0", "switch", day - 1, emptyList(), listOf(e("vi", "front")), null, false),
            SwitchRow("s1", "switch", day + 10, emptyList(), listOf(e("nai", "front")), null, false),
            SwitchRow("s2", "switch", day + 30, emptyList(), listOf(e("fox", "front"), e("am", "cocon")), null, false),
            SwitchRow("s3", "switch", day + 40, emptyList(), listOf(e("vi", "front")), null, true))
        fun post(id: String, at: Long, title: String?, text: String, reply: String? = null) =
            JournalPost(id, "entry", listOf("am"), title, text, at, null, "private", reply, null, emptyList())
        val posts = listOf(post("p1", day + 20, "A quiet afternoon", ""), post("p2", day + 25, null, "reply", reply = "x"),
            post("p3", day + 5, null, "\nTiny painting from this morning"))
        val events = HomeDashboard.today(switches, posts, day, layers, { names[it.subjectId] }, { ids -> ids.mapNotNull(names::get).firstOrNull() })
        assertEquals(listOf("s2", "p1", "s1", "p3"), events.map { it.id })
        assertEquals("Foxglove started steering · Amarynthe co-conscious", events[0].text)
        assertEquals("Amarynthe wrote “A quiet afternoon”", events[1].text)
        assertEquals("Amarynthe wrote “Tiny painting from this morning”", events[3].text)
        assertEquals(listOf("switch", "post", "switch", "post"), events.map { it.kind })
    }

    @Test fun firstDayShowsWhatIsLeftToDo() {
        val ren = Member("ren", "Ren", null, null, "#F0A060", emptyList(), null, false, isSelf = true)
        assertTrue(HomeDashboard.firstDay(0, 0, ren).let { it.welcome && it.post && it.profile })
        val done = HomeDashboard.firstDay(2, 1, ren.copy(pronouns = "she/her"))
        assertFalse(done.any)
    }
}
