package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PollsTest {
    private fun row(fields: String) = JSONObject().put("exists", true).put("fields", JSONObject(fields))

    @Test fun chatVotesAsItsComposerRatherThanTheBodyDefault() {
        val members = listOf("body", "channel", "selected").map { Member(it, it, null, null, "#fff", emptyList(), null, false) }
        val model = Model(members, emptyList(), emptyMap(), listOf(Entry("member", "body", "front", true)), null, emptyList())
        assertEquals("selected", Polls.chatMember(model, "selected"))
        assertEquals("channel", Polls.chatMember(model, "channel"))
        assertNull(Polls.chatMember(model, null))
        assertNull(Polls.chatMember(model, "missing"))
    }

    @Test fun percentsAndVoteCountsForOurOwnMembers() {
        assertEquals(67, Polls.percent(2, 3)); assertEquals(33, Polls.percent(1, 3)); assertEquals(0, Polls.percent(0, 0))
        assertEquals(100, Polls.percent(5, 4))
        val members = listOf("a", "b", "c").map { Member(it, it, null, null, "#fff", emptyList(), null, false) }
        val state = PollState(votes = mapOf("p|a" to listOf("x"), "p|b" to emptyList()))
        assertEquals(1, Polls.voted(state, "p", members))
        assertEquals(listOf("a" to listOf("x"), "b" to emptyList(), "c" to emptyList()),
            Polls.votesOf(state, "p", members).map { it.first.id to it.second })
    }

    @Test fun readsPollsTalliesAndOnlyOwnVotes() {
        val poll = """{"question":"Dinner?","options":[{"id":"a","text":"Soup"},{"id":"b","text":"Rice"}],"multiple":false}"""
        val p = JSONObject().put("rows", JSONObject()
            .put("message", JSONObject().put("m1", row("""{"text":"?","poll":$poll}""")))
            .put("poll_tally", JSONObject().put("m1", row("""{"channel_id":"c","counts":{"a":2,"b":1},"voters":3}""")))
            .put("poll_vote", JSONObject().put("m1|kai", row("""{"member_id":"kai","choices":["a"]}"""))))
        val state = Polls.fromProjection(p)
        assertEquals(listOf("Soup", "Rice"), state.polls["m1"]?.options?.map { it.text })
        assertEquals(PollTally(mapOf("a" to 2L, "b" to 1L), 3), state.tallies["m1"])
        assertEquals(listOf("a"), state.choices("m1", "kai"))
        assertTrue(state.choices("m1", "june").isEmpty())
        assertTrue(state.choices("m1", null).isEmpty())
        assertNull(Polls.parse(JSONObject("""{"options":[{"id":"a","text":"x"}]}""")))
    }

    @Test fun retainsOwnVotesWhenForeignParentsArriveOnlyThroughRest() {
        val projection = JSONObject().put("rows", JSONObject().put("poll_vote", JSONObject()
            .put("foreign|kai", row("""{"member_id":"kai","choices":["b"]}"""))
            .put("foreign|rin", row("""{"member_id":"rin","choices":["a"]}"""))))
        val state = Polls.fromProjection(projection)
        assertTrue(state.polls.isEmpty())
        assertEquals(listOf("b"), state.choices("foreign", "kai"))
        assertTrue(state.choices("foreign", "other").isEmpty())
    }

    @Test fun togglesAndBuildsDrafts() {
        val single = Poll(null, listOf(PollOption("a", "Soup"), PollOption("b", "Rice")), false)
        assertEquals(listOf("b"), Polls.toggled(single, listOf("a"), "b"))
        assertEquals(emptyList<String>(), Polls.toggled(single, listOf("a"), "a"))
        assertEquals(listOf("a", "b"), Polls.toggled(single.copy(multiple = true), listOf("a"), "b"))
        val draft = Polls.draft(listOf(" Soup ", "", "Rice"), true).getOrThrow()
        assertEquals("""{"options":[{"id":"a","text":"Soup"},{"id":"b","text":"Rice"}],"multiple":true}""", draft.toString())
        assertTrue(Polls.draft(listOf("Soup", " "), false).isFailure)
        assertEquals("""{"member_id":"kai","choices":[]}""", Polls.votePayload("kai", emptyList()).toString())
    }

    /** D-136: a closing time rides in the definition; the author's early close comes from the
     * projection or a REST row; the earlier one wins. */
    @Test fun closingTimesAndEarlyCloses() {
        val def = """{"options":[{"id":"a","text":"Soup"},{"id":"b","text":"Rice"}],"multiple":false,"closes_at":5000}"""
        val p = JSONObject().put("rows", JSONObject()
            .put("post", JSONObject().put("p1", row("""{"poll":$def}""")).put("p2", row("""{"poll":$def}""")))
            .put("poll_close", JSONObject().put("p2", row("""{"closed_at":3000}"""))))
        val state = Polls.fromProjection(p)
        assertEquals(5000L, state.closedAt("p1", state.polls.getValue("p1")))
        assertEquals(3000L, state.closedAt("p2", state.polls.getValue("p2")))
        // a REST row folds its early close into the definition
        val rest = Polls.parseRow(JSONObject("""{"poll":$def,"poll_closed_at":4000}"""))!!
        assertEquals(4000L, rest.closesAt)
        assertEquals(5000L, Polls.parseRow(JSONObject("""{"poll":$def,"poll_closed_at":null}"""))!!.closesAt)
        assertNull(Polls.parse(JSONObject("""{"options":[{"id":"a","text":"x"},{"id":"b","text":"y"}]}"""))!!.closesAt)

        assertNull(Polls.closing(null, 0))
        assertEquals("Closed", Polls.closing(5000, 5000))
        assertEquals("Closes in 1 min", Polls.closing(65_000, 30_000))
        assertEquals("Closes in 3 h", Polls.closing(3 * 3_600_000L + 60_000, 0))
        assertEquals("Closes in 1 day", Polls.closing(30 * 3_600_000L, 0))

        val now = 1_000_000L
        val draft = Polls.draft(listOf("Soup", "Rice"), false, closesAt = now + 60_000, now = now).getOrThrow()
        assertEquals(now + 60_000, draft.getLong("closes_at"))
        assertTrue(Polls.draft(listOf("Soup", "Rice"), false, closesAt = now, now = now).isFailure)
        val zone = java.time.ZoneOffset.UTC
        assertEquals("Thu 00:01", Polls.closingTime(60_000, 0, zone))
        assertEquals("15 Jan, 00:00", Polls.closingTime(14 * 86_400_000L, 0, zone))
    }

    @Test fun draftsKeepTheClosingTime() {
        val draft = JournalDraft("0192f8c2-0000-7000-8000-000000000001", poll = true, closesAt = 123_456L)
        assertEquals(123_456L, JournalDraft.decode(draft.encode()) { "x" }.closesAt)
        assertNull(JournalDraft.decode(draft.copy(closesAt = null).encode()) { "x" }.closesAt)
    }
}
