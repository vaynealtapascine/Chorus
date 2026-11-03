package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** UI11c (D-115): Android's bucket controls agree with web through the shared fixture. */
class BucketControlsTest {
    private val fixture = JSONObject(java.io.File(System.getProperty("chorus.fixtures"), "ui_bucket_controls.json").readText())

    @Test fun iconsFieldsAndChoicesMatchTheSharedFixture() {
        val icons = fixture.getJSONArray("icons")
        assertEquals((0 until icons.length()).map { icons.getString(it) }, BucketControls.icons)
        assertEquals(fixture.getString("default_icon"), BucketControls.DEFAULT_ICON)
        val fields = fixture.getJSONArray("fields")
        assertEquals(fields.length(), BucketControls.fields.size)
        for (i in 0 until fields.length()) {
            val f = fields.getJSONObject(i)
            val ours = BucketControls.fields[i]
            assertEquals(f.getString("key"), ours.key)
            assertEquals(f.getString("label"), ours.label)
            assertEquals(f.getString("kind"), ours.kind)
            assertEquals(f.optString("hint").ifEmpty { null }, ours.hint)
            val choices = f.optJSONArray("choices")
            assertEquals((0 until (choices?.length() ?: 0)).map { choices!!.getString(it) }, ours.choices)
            assertEquals(f.opt("default").toString(), (ours.default ?: JSONObject.NULL).toString())
            assertTrue(ours.key, BucketControls.valid(ours.key, ours.default))
        }
        val labels = fixture.getJSONObject("choice_labels")
        for (k in labels.keys()) assertEquals(labels.getString(k), BucketControls.choiceLabel(k))
    }

    @Test fun acceptExactlyWhatTheFixtureSays() {
        val cases = fixture.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            assertEquals("${c.getString("key")} ${c.opt("value")}", c.getBoolean("ok"), BucketControls.valid(c.getString("key"), c.opt("value")))
        }
    }

    @Test fun bucketsReadTheirIconAndSkipDeletedOnes() {
        val p = JSONObject("""{"rows":{"bucket":{
            "b1":{"exists":true,"fields":{"name":"Close","icon":"heart","ceiling":{"delay":{"min_s":0,"max_s":0}}}},
            "b2":{"exists":true,"fields":{"name":"Work","icon":"bogus"}},
            "b3":{"exists":true,"fields":{"name":"Gone","deleted_at":5}}}},
            "sets":{"bucket_assignment":{"b1|{\"follower_account_id\":\"f1\"}":true,"b1|{\"follower_account_id\":\"f2\"}":false}}}""")
        val buckets = BucketControls.buckets(p)
        assertEquals(listOf("Close" to "heart", "Work" to BucketControls.DEFAULT_ICON), buckets.map { it.name to it.icon })
        assertEquals(mapOf("b1" to setOf("f1")), BucketControls.assignments(p))
        val looks = buckets.associateBy { it.id }
        assertEquals("Close", BucketControls.single(Audience("buckets", listOf("b1", "b3")), looks)?.name)
        assertEquals(null, BucketControls.single(Audience("buckets", listOf("b3")), looks))
        assertEquals(null, BucketControls.single(Audience("buckets", listOf("b1", "b2")), looks))
        assertEquals(null, BucketControls.single(Audience("followers"), looks))
    }

    @Test fun iconsMayBeTheBucketsOwnEmojiAndColoursAreHex() {
        val icons = fixture.getJSONArray("icon_cases")
        for (i in 0 until icons.length()) icons.getJSONObject(i).let { c ->
            assertEquals("${c.opt("value")}", c.getString("shown"), BucketControls.icon(c.opt("value").takeIf { it != JSONObject.NULL }))
        }
        val colors = fixture.getJSONArray("color_cases")
        for (i in 0 until colors.length()) colors.getJSONObject(i).let { c ->
            assertEquals("${c.opt("value")}", c.opt("shown").takeIf { it != JSONObject.NULL }, BucketControls.color(c.opt("value").takeIf { it != JSONObject.NULL }))
        }
        val swatches = fixture.getJSONArray("swatches")
        assertEquals((0 until swatches.length()).map { swatches.getString(it) }, BucketControls.swatches)
        assertEquals(fixture.getInt("custom_icon_max"), BucketControls.CUSTOM_ICON_MAX)
        val fox = String(Character.toChars(0x1F98A))
        val p = JSONObject().put("rows", JSONObject().put("bucket", JSONObject().put("b1", JSONObject().put("exists", true)
            .put("fields", JSONObject().put("name", "Fox").put("icon", fox).put("color", "#42a397")))))
        assertEquals(listOf(fox to "#42A397"), BucketControls.buckets(p).map { it.icon to it.color })
    }

    @Test fun menusListEightBucketsThenFoldTheRestButNeverAChosenOne() {
        assertEquals(fixture.getInt("menu_collapse_after"), BucketControls.COLLAPSE_AFTER)
        val many = (0 until 11).map { "b$it" }
        assertEquals(many.take(8) to 0, BucketControls.listed(many.take(8), { it }, emptyList(), false))
        val (shown, folded) = BucketControls.listed(many, { it }, listOf("b10"), false)
        assertEquals(many.take(8) + "b10", shown)
        assertEquals(2, folded)
        assertEquals(0, BucketControls.listed(many, { it }, emptyList(), true).second)
    }

    @Test fun fieldsInheritByRemovingThemAndPresetsKeepTheRest() {
        val before = JSONObject().put("share_history", true).put("max_per_day", 3).put("fronts", JSONArray(listOf("f1")))
        val custom = BucketControls.withField(before, "stacking", "sequence")
        assertEquals("sequence", custom.getString("stacking"))
        assertFalse(BucketControls.withField(custom, "max_per_day", null, inherit = true).has("max_per_day"))
        val preset = FollowPresets.ceiling("gentle", custom) { """{"delay":{"min_s":300,"max_s":1200}}""" }
        assertEquals(3, preset.getInt("max_per_day"))
        assertEquals("sequence", preset.getString("stacking"))
        assertTrue(preset.getBoolean("share_history"))
        assertEquals(300, preset.getJSONObject("delay").getInt("min_s"))
    }
}
