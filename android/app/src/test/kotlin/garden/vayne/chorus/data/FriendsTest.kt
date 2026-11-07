package garden.vayne.chorus.data

import java.util.Calendar
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendsTest {
    private fun account(id: String, kind: String) = SpaceAccount(id, id, id.replaceFirstChar { it.uppercase() }, kind)
    private fun follow(id: String, who: SpaceAccount, status: String = "active", at: Long = 0) = FollowInfo(id, who, status, createdAt = at)
    private fun post(at: Long, kind: String = "note") = SharedPost("p", kind, null, "hi", null, at, listOf("Ren"))
    private fun at(hour: Int, minute: Int = 0, daysAgo: Int = 0) = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 7, hour, minute, 0); set(Calendar.MILLISECOND, 0); add(Calendar.DAY_OF_MONTH, -daysAgo)
    }.timeInMillis

    @Test fun rowsListWhomWeFollowThenThoseWhoOnlyFollowUs() {
        val junipers = account("junipers", "system"); val ren = account("ren", "person"); val moss = account("moss", "system")
        val follows = FollowList(listOf(follow("f1", junipers), follow("f2", ren, "requested")),
            listOf(follow("b1", moss), follow("b2", junipers, at = 5), follow("b3", account("ash", "person"), "requested")))
        val rows = Friends.rows(follows, emptyMap(), emptyMap())
        assertEquals(listOf("junipers", "ren", "moss"), rows.map { it.id })
        assertEquals("b2", rows[0].follower?.id)
        assertTrue(rows[1].waiting)
        assertNull(rows[2].following)
    }

    @Test fun aroundMeansSomeoneSharedForASystemAndARecentPostForAPerson() {
        val now = at(15)
        val shown = FollowerView(emptyList(), null, null, entries = listOf(SharedMember("Kit", null, level = "cocon")))
        val system = FriendRow(account("j", "system"), follow("f", account("j", "system")), null, shown, null)
        assertTrue(Friends.around(system, now))
        assertFalse(Friends.around(system.copy(view = FollowerView(emptyList(), null, null)), now))
        val person = FriendRow(account("r", "person"), follow("g", account("r", "person")), null, null, post(now - 2 * 3_600_000))
        assertTrue(Friends.around(person, now))
        assertFalse(Friends.around(person.copy(latest = post(now - Friends.AROUND_MS - 1)), now))
        // a request not yet accepted is never around
        assertFalse(Friends.around(system.copy(following = follow("f", account("j", "system"), "requested")), now))
    }

    @Test fun nowLinePutsTheFrontFirst() {
        val line = Friends.nowLine(listOf(SharedMember("Juniper", null, level = "cocon"), SharedMember("Kit", null)))
        assertEquals("Kit fronting · Juniper co-conscious", line)
        val many = (1..5).map { SharedMember("M$it", null) }
        assertEquals("M1 fronting · M2 fronting · M3 fronting · 2 more", Friends.nowLine(many))
    }

    @Test fun sinceIsNeverMorePreciseThanTheirTimeRule() {
        val now = at(15)
        val clock = { t: Long -> Calendar.getInstance().apply { timeInMillis = t }.let { "%02d:%02d".format(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) } }
        fun view(mode: String, since: Long) = FollowerView(emptyList(), null, null, since = since, time = JSONObject().put("mode", mode))
        assertEquals("since about 10:15", Friends.sinceLine(view("round", at(10, 15)), now, clock))
        assertEquals("since 10:15", Friends.sinceLine(view("exact", at(10, 15)), now, clock))
        assertEquals("since this morning", Friends.sinceLine(view("part_of_day", at(5)), now, clock))
        assertEquals("since yesterday evening", Friends.sinceLine(view("part_of_day", at(17, daysAgo = 1)), now, clock))
        assertNull(Friends.sinceLine(view("hidden", at(10)), now, clock))
        assertNull(Friends.sinceLine(FollowerView(emptyList(), null, null), now, clock))
    }

    @Test fun personActivityAndFriendsSince() {
        assertEquals("Wrote a journal entry · 2h", Friends.activity(post(0, "entry"), "2h"))
        assertEquals("Posted · 1d", Friends.activity(post(0), "1d"))
        assertEquals("friends since today", Friends.friendsSince(at(9), at(15)))
        assertNull(Friends.friendsSince(0, at(15)))
    }

    @Test fun memberChipsGoFromEveryoneToSomeAndBack() {
        val all = listOf("kit", "juniper", "sable")
        val some = Friends.withSubject(JSONObject(), "sable", all)
        assertEquals(setOf("kit", "juniper"), Friends.subjects(some))
        val back = Friends.withSubject(some, "sable", all)
        assertNull(Friends.subjects(back))
        // the last one stays
        val one = Friends.withSubject(Friends.withSubject(some, "kit", all), "juniper", all)
        assertEquals(setOf("juniper"), Friends.subjects(one))
    }

    @Test fun layerChipsKeepOneOnAndPrefsKeepTheirOtherKeys() {
        val prefs = JSONObject().put("channel", "switches")
        assertEquals(setOf("front"), Friends.levels(Friends.withLevel(prefs, "front")))
        val both = Friends.withLevel(prefs, "cocon")
        assertEquals(setOf("front", "cocon"), Friends.levels(both))
        assertEquals("switches", both.getString("channel"))
        assertFalse(prefs.has("levels"))
    }

    @Test fun deliveryQuietAndMute() {
        val p = Friends.withDelivery(JSONObject(), "digest_daily")
        assertEquals("Everyone · daily at 20:00", Friends.prefsSummary(p, emptyMap(), 0))
        val q = Friends.withQuiet(p, "23:00" to "08:00", hold = false)
        assertEquals("23:00" to "08:00", Friends.quiet(q))
        assertFalse(Friends.quietHold(q))
        assertNull(Friends.quiet(Friends.withQuiet(q, null)))
        val muted = Friends.withMute(q, 10_000)
        assertEquals(10_000L, Friends.mutedUntil(muted, 5_000))
        assertNull(Friends.mutedUntil(muted, 20_000))
        assertEquals("Off", Friends.prefsSummary(Friends.withEnabled(q, false), emptyMap(), 0))
        val names = mapOf("kit" to "Kit", "juniper" to "Juniper")
        val only = Friends.withSubject(JSONObject(), "sable", listOf("kit", "juniper", "sable"))
        assertEquals("Kit and Juniper · each switch", Friends.prefsSummary(only, names, 0))
        assertEquals(-60, Friends.forSaving(only, -60).getInt("tz_offset_min"))
    }

    @Test fun acceptingAsTheBucketsPresetLeavesTheBucketInCharge() {
        val choiceOf = { c: JSONObject -> c.optString("preset", "inherit") }
        val ceiling = { p: String -> JSONObject().put("preset", p) }
        val gentle = JSONObject().put("preset", "gentle")
        assertNull(Friends.acceptCeiling("gentle", gentle, JSONObject(), choiceOf, ceiling))
        assertEquals("close", Friends.acceptCeiling("close", gentle, JSONObject(), choiceOf, ceiling)!!.getString("preset"))
        // no bucket: compared with the account default
        assertNull(Friends.acceptCeiling("private", null, JSONObject().put("preset", "private"), choiceOf, ceiling))
        // a bucket that inherits falls through to the account default
        assertNull(Friends.acceptCeiling("digest", JSONObject(), JSONObject().put("preset", "digest"), choiceOf, ceiling))
    }

    @Test fun whoAFollowerHearsAboutFollowsEachMembersAnnounceList() {
        assertTrue(Friends.announcedTo(null, emptySet()))
        assertFalse(Friends.announcedTo("""{"announce":"nobody"}""", setOf("close")))
        assertTrue(Friends.announcedTo("""{"announce":{"buckets":["close"]}}""", setOf("close", "friends")))
        assertFalse(Friends.announcedTo("""{"announce":{"buckets":["close"]}}""", setOf("friends")))
        assertEquals("Everyone", Friends.hearsAbout(listOf("Kit" to true, "June" to true)))
        assertEquals("Everyone except Violet", Friends.hearsAbout(listOf("Kit" to true, "June" to true, "Violet" to false)))
        assertEquals("Kit", Friends.hearsAbout(listOf("Kit" to true, "June" to false, "Violet" to false)))
        assertEquals("Nobody", Friends.hearsAbout(listOf("Kit" to false)))
    }

    @Test fun ceilingDetailsReadShortly() {
        assertEquals("0 min", Friends.fieldValue("delay", BucketControls.range(0, 0)))
        assertEquals("5–20 min", Friends.fieldValue("delay", BucketControls.range(300, 1200)))
        assertEquals("5 min", Friends.fieldValue("time", JSONObject().put("mode", "round").put("round_min", 5)))
        assertEquals("Front, Co-conscious", Friends.fieldValue("levels", org.json.JSONArray(listOf("front", "cocon"))))
        assertEquals("Collapse", Friends.fieldValue("stacking", "collapse"))
        assertEquals("23:00–08:00", Friends.fieldValue("quiet_hours", JSONObject().put("from", "23:00").put("to", "08:00")))
        assertEquals("None", Friends.fieldValue("quiet_hours", null))
    }

    @Test fun theViewKeepsEveryLayerAndItsTime() {
        val v = PeopleApi.followerView(JSONObject("""{"entries":[{"t":"subject","level":"front","name":"Kit"},
            {"t":"subject","level":"cocon","name":"Juniper","color":"#F2C230"}],"since":1000,"revealed_at":2000,"time":{"mode":"round","round_min":15}}"""))
        assertEquals(listOf("Kit"), v.frontNames)
        assertEquals(listOf("front", "cocon"), v.entries.map { it.level })
        assertEquals(1000L, v.since)
        assertEquals(2000L, v.revealedAt)
        assertEquals("The Junipers round times to 15 minutes.", Friends.timeNote("The Junipers", v.time))
        assertFalse(PeopleApi.followerView(JSONObject("""{"shared":false}""")).shared)
    }
}
