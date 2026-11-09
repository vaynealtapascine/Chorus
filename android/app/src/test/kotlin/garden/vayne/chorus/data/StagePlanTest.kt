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
            blurAttachments = true, hideHeader = true, hideReplyBars = true, blurAvatars = true))
        assertEquals("channel", saved.getString("channel_id"))
        assertEquals(-900000L, saved.getJSONObject("time").getLong("offset_ms"))
        assertEquals(-15, StagePlan.supported(saved)?.shiftMinutes)
        assertEquals("Blue", StagePlan.supported(saved)?.fakeNames?.get("a"))
        assertEquals(setOf("a"), StagePlan.supported(saved)?.onlyMembers)
        assertEquals(1, StagePlan.supported(saved)?.replyDepth)
        assertEquals(true, StagePlan.supported(saved)?.blurAttachments)
        assertEquals(true, StagePlan.supported(saved)?.hideHeader)
        assertEquals(true, StagePlan.supported(saved)?.hideReplyBars)
        assertEquals(true, StagePlan.supported(saved)?.blurAvatars)
        assertEquals(true, saved.getJSONObject("render").getBoolean("blur_attachments"))
        assertEquals(true, saved.getJSONObject("render").getBoolean("hide_header"))
        assertEquals(true, saved.getJSONObject("render").getBoolean("hide_reply_bars"))
        assertEquals(true, saved.getJSONObject("render").getBoolean("blur_avatars"))
        saved.getJSONObject("render").put("style", "transcript")
        assertEquals("transcript", StagePlan.supported(saved)?.style)
        saved.getJSONObject("render").put("style", "minimal")
        assertEquals("minimal", StagePlan.supported(saved)?.style)
        saved.getJSONObject("render").put("style", "card")
        assertEquals("card", StagePlan.supported(saved)?.style)
        saved.getJSONObject("render").put("style", "discord")
        assertEquals("discord", StagePlan.supported(saved)?.style)
        saved.getJSONObject("render").put("style", "bubbles")
        assertEquals("bubbles", StagePlan.supported(saved)?.style)
        assertEquals("minimal", StagePlan.definition("channel", StagePlan.Settings(style = "minimal"))
            .getJSONObject("render").getString("style"))
        val start = StagePlan.definition("channel", StagePlan.Settings(timeMode = "start", startAt = 123_456L))
        assertEquals(123_456L, start.getJSONObject("time").getLong("start"))
        assertEquals(123_456L, StagePlan.supported(start)?.startAt)
        start.getJSONObject("time").remove("start")
        assertEquals(null, StagePlan.supported(start))
        saved.getJSONObject("render").put("theme", "dark")
        assertEquals("dark", StagePlan.supported(saved)?.theme)
        assertEquals("light", StagePlan.definition("channel", StagePlan.Settings(theme = "light"))
            .getJSONObject("render").getString("theme"))
        saved.put("render", JSONObject().put("style", "unknown"))
        assertEquals(null, StagePlan.supported(saved))
    }

    @Test fun richerDefinitionsRoundTripAndReadTheirPlan() {
        val settings = StagePlan.Settings(theme = "custom", palette = mapOf("bg" to "#102030", "ink" to "#FFFFFF", "accent" to "#42A397"),
            width = "wide", hideReactions = true, hideThreadPreviews = true, hideDates = true,
            fakeNames = mapOf("a" to "Rose"), fakeColors = mapOf("a" to "#E07A7A"),
            timeMode = "manual", manualAt = mapOf("m1" to 5_000L), blackout = mapOf("m1" to listOf(0 until 4, 6 until 9)))
        val saved = StagePlan.definition("c", settings)
        assertEquals(listOf(0, 4), saved.getJSONObject("blackout").getJSONArray("m1").getJSONArray(0).let { listOf(it.getInt(0), it.getInt(1)) })
        assertEquals("#E07A7A", saved.getJSONObject("fake_names").getJSONObject("a").getString("color"))
        assertEquals("manual", saved.getJSONObject("time").getString("mode"))
        assertEquals("#102030", saved.getJSONObject("render").getJSONObject("palette").getString("bg"))
        assertEquals(settings, StagePlan.supported(saved))
        saved.getJSONObject("render").put("width", "poster")
        assertEquals(null, StagePlan.supported(saved))

        val plan = StagePlan.forMessages(listOf(message("m1", "a", 1000)), settings) { _, _ ->
            """{"rows":[{"kind":"item","id":"m1","selected":true,"at":5000,"reply_shown":false,"blackout":[[0,4],[6,9]]}],
               "names":{"a":{"label":"Rose","color":"#e07a7a"}},"palette":{"dark":true,"bg":"#102030","ink":"#ffffff"}}"""
        }
        assertEquals(listOf(0 until 4, 6 until 9), plan.rows.single().blackout)
        assertEquals("#e07a7a", plan.nameColors["a"])
        assertEquals(true, plan.palette?.dark)
        assertEquals("#102030", plan.palette?.colors?.get("bg"))
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

    @Test fun replyPreviewUsesStagedNamesAndRespectsContentWarning() {
        val parent = message("first", "a", 1000).copy(text = "Hidden private text", cw = "Sensitive")
        assertEquals("↪ Alias: Content warning: Sensitive",
            StagePlan.replyPreview(parent, mapOf("a" to "Alias")) { "Real name" })
        assertEquals("↪ Real name: Public text",
            StagePlan.replyPreview(parent.copy(text = "Public\ntext", cw = null), emptyMap()) { "Real name" })
    }

    @Test fun incompleteSavedSelectionIsNotReadyForCapture() {
        val current = listOf(message("new", "a", 2000))
        assertEquals(setOf("old"), StagePlan.missingSelected(setOf("old", "new"), current))
        assertEquals(emptySet<String>(), StagePlan.missingSelected(setOf("old", "new"),
            listOf(message("old", "a", 1000)) + current))
    }
}
