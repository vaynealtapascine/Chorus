package garden.vayne.chorus.data

import garden.vayne.chorus.data.FrontingDisplay.Preset
import garden.vayne.chorus.data.FrontingDisplay.Style
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontingDisplayTest {
    private val members = listOf("fox", "ama", "nai", "vio", "sab").map { Member(it, it.replaceFirstChar(Char::uppercase), null, null, "#C0694E", emptyList(), null, false) }
    private fun model(current: List<Entry>, display: FrontingDisplay.Settings = FrontingDisplay.Settings()) =
        Model(members, emptyList(), emptyMap(), current, null, emptyList(), accountPrefs = AccountPrefs(frontingDisplay = display))

    @Test fun missingOrMalformedPrefKeepsDefaults() {
        val s = FrontingDisplay.parse(null)
        assertEquals(Preset.Detailed, s.front("body").preset)
        assertTrue(s.front("body").hideEmpty && s.front("body").collapseAway && s.front("body").showTime && s.front("body").showOthers)
        val odd = FrontingDisplay.parse(JSONObject("""{"layers":{"x":{"style":"wavy","color":"red"}},"fronts":{"b":{"preset":"huge","icon":"rocket"}},"order":["b","",3]}"""))
        assertNull(odd.layer("x").style)
        assertNull(odd.layer("x").color)
        assertEquals(Preset.Detailed, odd.front("b").preset)
        assertNull(odd.front("b").icon)
        assertEquals(listOf("b", "3"), odd.order)
    }

    @Test fun roundTripsThroughThePrefPayload() {
        val s = FrontingDisplay.Settings(
            layers = mapOf("cocon" to FrontingDisplay.LayerLook(Style.Faint, "close", true, "#E8776D")),
            fronts = mapOf("b" to FrontingDisplay.FrontView(icon = "book", about = "Inner world", hidden = true, preset = Preset.Simple,
                hiddenLayers = setOf("away", "present"), hideEmpty = false, collapseAway = false, showTime = false, showOthers = false)),
            order = listOf("b", "a"), hintDismissed = true)
        val payload = FrontingDisplay.payload(s)
        assertEquals("fronting.display", payload.getString("key"))
        assertEquals(s, FrontingDisplay.parse(payload.getJSONObject("value")))
    }

    @Test fun prefAndMemberStatusReadFromProjection() {
        val p = JSONObject("""{"rows":{"pref":{
            "acct||fronting.display":{"exists":true,"fields":{"value":{"fronts":{"acct":{"preset":"complete"}}}}},
            "acct||member_status:fox":{"exists":true,"fields":{"value":{"text":"Tired","at":5}}},
            "||member_status:ama":{"exists":true,"fields":{"value":{"text":"  "}}}
        }}}""")
        val prefs = AccountPrefs.fromProjection(p, "acct")
        assertEquals(Preset.Complete, prefs.frontingDisplay.front("acct").preset)
        assertEquals("Tired", prefs.memberStatus["fox"]?.text)
        assertFalse("ama" in prefs.memberStatus)
        assertEquals(JSONObject.NULL, FrontingDisplay.statusPayload("fox", " ", 1).get("value"))
    }

    @Test fun sceneSortsEntriesIntoNestedSeparateAndAway() {
        val display = FrontingDisplay.Settings(layers = mapOf("present" to FrontingDisplay.LayerLook(null, null, true, null)),
            fronts = mapOf("acct" to FrontingDisplay.FrontView(hiddenLayers = setOf("away"))))
        val current = listOf(Entry("member", "fox", "front", true), Entry("member", "ama", "front", false),
            Entry("member", "nai", "cocon", false), Entry("member", "vio", "present", false), Entry("member", "ghost", "quiet", false))
        val scene = FrontScene.build(model(current, display), "acct", current, "acct")
        assertEquals(listOf("front", "cocon"), scene.nested.map { it.id })
        assertEquals(listOf(Style.Solid, Style.Dashed), scene.nested.map { it.style })
        assertEquals(listOf("present"), scene.separate.map { it.id })
        assertEquals(Style.Plain, scene.separate.single().style)
        assertEquals(listOf("fox", "ama"), scene.innermost?.entries?.map { it.subjectId })
        assertEquals(listOf("ghost"), scene.unlisted.map { it.subjectId })
        assertEquals(listOf("sab"), scene.away.map { it.id })
        assertFalse(scene.awayShown)
        assertEquals("In control · counts as fronting", scene.nested.first().about)
        assertEquals("in control", FrontingDisplay.shortAbout(scene.nested.first().about))
    }

    @Test fun hiddenLayersLeaveTheBoxesButStayPickable() {
        val display = FrontingDisplay.Settings(fronts = mapOf("acct" to FrontingDisplay.FrontView(hiddenLayers = setOf("cocon"))))
        val scene = FrontScene.build(model(emptyList(), display), "acct", emptyList(), "acct")
        assertEquals(listOf("front", "present"), scene.nested.map { it.id })
        assertEquals(listOf("front", "cocon", "present"), scene.all.map { it.id })
        // display order still decides the default look, hidden or not
        assertEquals(Style.Faint, scene.nested.last().style)
    }

    @Test fun orderedAndIconFallbacks() {
        val f = { id: String, name: String -> NamedFront(id, name, emptyList(), null, false, false, null, null, emptyMap()) }
        val fronts = listOf(f("z", "Work"), f("body", "Body"), f("a", "Inner world"))
        assertEquals(listOf("body", "a", "z"), FrontingDisplay.ordered(fronts, "body", FrontingDisplay.Settings()).map { it.id })
        assertEquals(listOf("a", "body", "z"), FrontingDisplay.ordered(fronts, "body", FrontingDisplay.Settings(order = listOf("a"))).map { it.id })
        assertEquals("target", FrontingDisplay.icon("body", "Body", "body", FrontingDisplay.Settings()))
        assertEquals("briefcase", FrontingDisplay.icon("z", "Work", "body", FrontingDisplay.Settings()))
        assertEquals("layers", FrontingDisplay.icon("q", "Elsewhere", "body", FrontingDisplay.Settings()))
        assertEquals("1h 24m", FrontingDisplay.duration(84 * 60_000L))
        assertEquals("32m", FrontingDisplay.duration(32 * 60_000L))
    }

    @Test fun storyNamesWhatChanged() {
        val order = listOf("front", "cocon", "present")
        val states = listOf(
            FrontState("1", 100, listOf(Entry("member", "ama", "front", true))),
            FrontState("2", 200, listOf(Entry("member", "fox", "front", true))),
            FrontState("3", 300, listOf(Entry("member", "fox", "front", true), Entry("member", "vio", "present", false))),
            FrontState("4", 400, listOf(Entry("member", "fox", "front", true), Entry("member", "vio", "present", false), Entry("member", "nai", "present", false))),
            FrontState("5", 500, listOf(Entry("member", "fox", "front", true), Entry("member", "ama", "front", false),
                Entry("member", "vio", "present", false), Entry("member", "nai", "cocon", false))),
            FrontState("6", 600, listOf(Entry("member", "fox", "front", true))),
        )
        val lines = FrontStory.lines(states, order, "present", { it }, { _, id -> id.replaceFirstChar(Char::uppercase) })
        assertEquals(listOf("Ama started fronting", "Fox took over from Ama", "Vio came close", "Nai came close",
            "Ama joined Fox", "Nai moved inward", "Ama left", "Vio left", "Nai left"), lines.map { it.text })
        assertEquals(listOf("front", "front", "present", "present", "front", "cocon", null, null, null), lines.map { it.layerId })
    }
}
