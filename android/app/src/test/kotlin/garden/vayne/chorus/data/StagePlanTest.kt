package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class StagePlanTest {
    private fun message(id: String, author: String, at: Long, reply: String? = null) = ChatMessage(
        id, "c", listOf(author), id, at, null, "all", emptySet(), "mine", reply, emptyList())

    @Test fun sendsTheCoreStageDefinitionAndReadsItsPlan() {
        val rows = listOf(message("first", "a", 1000), message("middle", "b", 2000),
            message("last", "a", 3000, "first"))
        val plan = StagePlan.forMessages(rows, StagePlan.Settings(selected = setOf("first", "last"),
            redactNames = true, fakeNames = mapOf("a" to "Blue"), timeMode = "shift", shiftMinutes = 2)) { items, definition ->
            val parsedItems = JSONArray(items)
            val parsedDef = JSONObject(definition)
            assertEquals("last", parsedItems.getJSONObject(2).getString("id"))
            assertEquals("first", parsedItems.getJSONObject(2).getString("reply_to"))
            assertEquals("context", parsedDef.getString("unselected"))
            assertEquals(120000L, parsedDef.getJSONObject("time").getLong("offset_ms"))
            assertEquals("Blue", parsedDef.getJSONObject("fake_names").getJSONObject("a").getString("label"))
            """{"rows":[{"kind":"item","id":"first","selected":true,"at":121000,"reply_shown":false},{"kind":"context","ids":["middle"],"count":1},{"kind":"item","id":"last","selected":true,"at":123000,"reply_shown":true}],"names":{"a":{"label":"Blue"}}}"""
        }
        assertEquals(listOf("first", null, "last"), plan.rows.map { it.id })
        assertEquals(1, plan.rows[1].contextCount)
        assertEquals(121000L, plan.rows[0].at)
        assertEquals("Blue", plan.names["a"])
        assertFalse(plan.names.containsKey("b"))
        assertEquals(true, plan.rows[2].replyShown)
    }

    @Test fun savedStagesKeepSupportedPlansAndFlagRicherWebViews() {
        val saved = StagePlan.definition("channel", StagePlan.Settings(selected = setOf("first"),
            unselected = "hidden", redactNames = true, fakeNames = mapOf("a" to "Blue"),
            timeMode = "shift", shiftMinutes = -15, onlyMembers = setOf("a"), replyDepth = 1,
            blurAttachments = true, hideHeader = true, hideReplyBars = true))
        assertEquals("channel", saved.getString("channel_id"))
        assertEquals(-900000L, saved.getJSONObject("time").getLong("offset_ms"))
        assertEquals(-15, StagePlan.supported(saved)?.shiftMinutes)
        assertEquals("Blue", StagePlan.supported(saved)?.fakeNames?.get("a"))
        assertEquals(setOf("a"), StagePlan.supported(saved)?.onlyMembers)
        assertEquals(1, StagePlan.supported(saved)?.replyDepth)
        assertEquals(true, StagePlan.supported(saved)?.blurAttachments)
        assertEquals(true, StagePlan.supported(saved)?.hideHeader)
        assertEquals(true, StagePlan.supported(saved)?.hideReplyBars)
        assertEquals(true, saved.getJSONObject("render").getBoolean("blur_attachments"))
        assertEquals(true, saved.getJSONObject("render").getBoolean("hide_header"))
        assertEquals(true, saved.getJSONObject("render").getBoolean("hide_reply_bars"))
        saved.put("render", JSONObject().put("style", "discord"))
        assertEquals(null, StagePlan.supported(saved))
    }

    @Test fun modelShowsOnlyLiveSavedStages() {
        val model = Model.parse("""{"rows":{"stage":{
            "live":{"exists":true,"fields":{"name":"Quiet","definition":{"channel_id":"c","selected":[]}}},
            "gone":{"exists":true,"fields":{"name":"Old","definition":{"channel_id":"c"},"deleted_at":123}},
            "removed":{"exists":false,"fields":{"name":"Removed","definition":{"channel_id":"c"}}}
        }}}""", "mine")
        assertEquals(listOf("Quiet"), model.savedStages.map { it.name })
        assertEquals("c", model.savedStages.single().channelId)
    }
}
