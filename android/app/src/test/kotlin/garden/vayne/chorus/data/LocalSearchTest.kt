package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSearchTest {
    @Test fun prefixAndFiltersFindAllThreeLocalKinds() {
        val model = Model(listOf(Member("kai", "Kai", null, null, "#888888", emptyList(), null, false)),
            emptyList(), emptyMap(), emptyList(), null,
            listOf(SwitchRow("sw", "switch", 300, listOf(Entry("member", "kai", "front", true)),
                emptyList(), "Violet garden", false)),
            channels = listOf(ChatChannel("general", "s", "text", "General", null)),
            posts = listOf(JournalPost("post", "note", listOf("kai"), "Violet notes", "Inside the garden",
                200, null, "private", null, null, listOf("daily"))),
            searchMessages = listOf(SearchDocument("msg", "Messages", 100, "A violet garden", authors = listOf("kai"),
                channelId = "general", hasImage = true, hasAttachment = true)))
        val search = LocalSearch.fromModel(model)
        assertEquals(listOf("msg"), search.search("viol from:Kai in:Gen has:image", "Messages").map { it.id })
        assertEquals(listOf("post"), search.search("vio from:kai before:300", "Posts").map { it.id })
        assertEquals(listOf("sw"), search.search("vio after:200", "Switches").map { it.id })
        assertEquals(listOf("sw"), search.search("vio from:someone", "Switches").map { it.id })
        assertTrue(search.search("vio after:300", "Switches").isEmpty())
        assertTrue(search.search("vio has:file", "Messages").isEmpty())
    }

    @Test fun searchIncludesLocalMessagesOlderThanTheChatDisplayWindow() {
        val messages = JSONObject()
        for (i in 0..120) messages.put("m$i", JSONObject().put("exists", true).put("fields",
            JSONObject().put("channel_id", "c").put("authors", org.json.JSONArray().put("kai"))
                .put("text", if (i == 0) "earliest violet" else "later").put("occurred_at", i)))
        val p = JSONObject().put("rows", JSONObject()
            .put("space", JSONObject().put("s", JSONObject().put("exists", true).put("fields",
                JSONObject().put("kind", "internal").put("name", "Home"))))
            .put("channel", JSONObject().put("c", JSONObject().put("exists", true).put("fields",
                JSONObject().put("space_id", "s").put("name", "general"))))
            .put("message", messages))
        val model = Model.parse(p, "acct")
        assertEquals(100, model.chatMessages["c"]?.size)
        assertEquals("m0", LocalSearch.fromModel(model).search("vio", "Messages").single().id)
    }

    @Test fun largeJournalQueriesStayWithinTheInteractionBudget() {
        val posts = (0 until 5_000).map { i -> JournalPost("p$i", "note", listOf("kai"), null,
            "Garden note $i", i.toLong(), null, "private", null, null, emptyList()) }
        val switches = (0 until 10_000).map { i -> SwitchRow("s$i", "switch", i.toLong(),
            listOf(Entry("member", "kai", "front", true)), emptyList(), "Garden switch $i", false) }
        val model = Model(listOf(Member("kai", "Kai", null, null, "#888888", emptyList(), null, false)),
            emptyList(), emptyMap(), emptyList(), null, switches, posts = posts)
        val index = LocalSearch.fromModel(model)
        repeat(10) { index.search("garden", "Posts"); index.search("garden", "Switches") }
        val start = System.nanoTime()
        repeat(100) { index.search("garden", "Posts"); index.search("garden", "Switches") }
        val averageMs = (System.nanoTime() - start) / 200.0 / 1_000_000.0
        assertTrue("average journal search was $averageMs ms", averageMs < 16.0)
    }
}
