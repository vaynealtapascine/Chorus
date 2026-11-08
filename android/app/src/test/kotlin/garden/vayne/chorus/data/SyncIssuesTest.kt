package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncIssuesTest {
    private val kai = Member("kai", "Kai", null, null, "#aabbcc", emptyList(), null, false)
    private val model = Model(listOf(kai), emptyList(), emptyMap(), emptyList(), null, emptyList(),
        channels = listOf(ChatChannel("general", "home", "text", "general", null)))

    private fun issue(kind: String, payload: String, entity: String? = null, member: String? = null, message: String = "The channel was deleted.") =
        SyncIssue("op1", kind, 1_000L, "forbidden", message, "", "space:home", entity, member, JSONObject(payload))

    @Test
    fun titlesSayWhatTheChangeWas() {
        assertEquals("Edit to a message in #general", SyncIssues.title(issue("message.edit", """{"channel_id":"general","text":"hi"}"""), model))
        assertEquals("Message", SyncIssues.title(issue("message.send", """{"channel_id":"gone","text":"hi"}"""), model))
        assertEquals("Edit to “A quiet afternoon”", SyncIssues.title(issue("post.edit", """{"title":"A quiet afternoon"}"""), model))
        assertEquals("Change to Kai", SyncIssues.title(issue("member.update", "{}", entity = "kai"), model))
        assertEquals("New custom field", SyncIssues.title(issue("custom_field.create", "{}"), model))
        assertEquals("Change to a channel", SyncIssues.title(issue("channel.update", "{}"), model))
    }

    @Test
    fun reasonTextAndAuthor() {
        val i = issue("message.send", """{"text":"Keys are by the door","authors":["kai"]}""")
        assertEquals("Not saved · the channel was deleted", SyncIssues.reason(i))
        assertEquals("Not saved · not found", SyncIssues.reason(issue("x.y", "{}", message = "").copy(code = "not_found")))
        assertEquals("Keys are by the door", SyncIssues.text(i))
        assertEquals("kai", SyncIssues.author(i))
        assertEquals("rin", SyncIssues.author(issue("message.edit", "{}", member = "rin")))
    }

    @Test
    fun fixedKeepsAWeekAndDropsRetriesRefusedAgain() {
        val day = 86_400_000L
        val now = 10 * day
        var rows = SyncIssues.withFixed(emptyList(), SyncIssues.Fixed("Old", "a", now - 8 * day), now)
        assertEquals(emptyList<SyncIssues.Fixed>(), rows)
        rows = SyncIssues.withFixed(rows, SyncIssues.Fixed("One", "b", now - day), now)
        rows = SyncIssues.withFixed(rows, SyncIssues.Fixed("Two", "c", now), now)
        rows = SyncIssues.withFixed(rows, SyncIssues.Fixed("Three", "d", now), now)
        assertEquals(listOf("d", "c", "b"), SyncIssues.parseFixed(SyncIssues.encodeFixed(rows)).map { it.opId })
        val status = mapOf("b" to "confirmed", "c" to "refused", "d" to "pending")
        assertEquals(listOf("d" to false, "b" to true), SyncIssues.shownFixed(rows, now) { status.getValue(it) }.map { it.first.opId to it.second })
        assertEquals(emptyList<SyncIssues.Fixed>(), SyncIssues.parseFixed("not json"))
    }
}
