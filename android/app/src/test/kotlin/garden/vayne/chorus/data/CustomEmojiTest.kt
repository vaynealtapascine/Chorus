package garden.vayne.chorus.data

import garden.vayne.chorus.data.D132Ops.ME
import garden.vayne.chorus.data.D132Ops.ME_MEMBER
import garden.vayne.chorus.data.D132Ops.Spec
import garden.vayne.chorus.data.D132Ops.u
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Custom emoji (F5.12, D-054) over a model built from ops through the real projection, and the same core markup web writes. */
class CustomEmojiTest {
    private val wave = u(0x701); private val tea = u(0x702); private val old = u(0x703)
    private val hash = "a".repeat(64)

    private fun create(id: String, name: String, aliases: List<String> = emptyList(), category: String? = null, blob: String = hash) =
        Spec("emoji.create", "server", id, JSONObject().put("name", name).put("aliases", JSONArray(aliases))
            .put("blob_hash", blob).put("is_animated", false).also { if (category != null) it.put("category", category) })

    private val ops = D132Ops.person() + listOf(
        create(wave, "ka_wave", listOf("hello"), "Kai"),
        create(tea, "tea"),
        create(old, "old_one", blob = "b".repeat(64)),
        Spec("emoji.delete", "server", old, JSONObject()),
    )
    private val model = D132Ops.model(ops)

    @Test fun theProjectionGivesEveryEmojiRetiredOnesToo() {
        val byName = model.customEmoji.associateBy { it.name }
        assertEquals(listOf("hello"), byName.getValue("ka_wave").aliases)
        assertEquals("Kai", byName.getValue("ka_wave").category)
        assertEquals("Custom", byName.getValue("tea").category)
        assertTrue(byName.getValue("old_one").retired)
        assertFalse(byName.getValue("tea").retired)
        assertEquals(setOf("tea", "ka_wave"), CustomEmojis.active(model.customEmoji).map { it.name }.toSet())
        assertEquals("old_one", model.emojiById.getValue(old).name)
        // ordered by category then name, as web's customEmojis
        assertEquals(listOf("old_one", "tea", "ka_wave"), model.customEmoji.map { it.name })
    }

    @Test fun composingATokenWritesTheCustomEmojiEntityTheCoreReadsBack() {
        val payload = ChatCompose.payload(model, D132Ops.GENERAL, ME_MEMBER, "hi :ka_wave: and :hello: and :nope:", "", "all", emptySet(),
            "shared", accountId = ME)
        assertEquals("hi :ka_wave: and :hello: and :nope:", payload.getString("text"))
        val entities = payload.getJSONArray("entities")
        val found = (0 until entities.length()).map { entities.getJSONObject(it) }.filter { it.getString("type") == "custom_emoji" }
        assertEquals(listOf(wave, wave), found.map { it.getString("emoji_id") })
        assertEquals(listOf(3, 17), found.map { it.getInt("offset") })
        assertEquals(listOf(9, 7), found.map { it.getInt("length") })
        // a retired emoji is not offered to the parser, an unknown token stays text
        val retired = ChatCompose.payload(model, D132Ops.GENERAL, ME_MEMBER, ":old_one:", "", "all", emptySet(), "shared", accountId = ME)
        assertEquals(0, retired.getJSONArray("entities").length())
        // editing hands the original markup back
        val markup = uniffi.chorus_ffi.toMarkup(JSONObject().put("text", payload.getString("text")).put("entities", entities).toString())
        // the core escapes the token it could not resolve, so it stays text on the way back in
        assertEquals("hi :ka_wave: and :hello: and \\:nope:", markup)
    }

    @Test fun anEditResolvesLiveNamesAndKeepsALegacyOne() {
        val names = JSONObject(ChatEdit.names("x :gone:", """[{"type":"custom_emoji","offset":2,"length":6,"emoji_id":"gone-id"}]""", model))
        assertEquals(wave, names.getJSONObject("emoji").getString("ka_wave"))
        assertEquals(wave, names.getJSONObject("emoji").getString("hello"))
        assertEquals("gone-id", names.getJSONObject("emoji").getString("gone"))
        assertFalse(names.getJSONObject("emoji").has("old_one"))
    }

    @Test fun aNameWinsAnAliasCollision() {
        val a = CustomEmoji("a", "one", listOf("two"), "Custom", hash, false, false)
        val b = CustomEmoji("b", "two", emptyList(), "Custom", hash, false, false)
        assertEquals("b", CustomEmojis.names(listOf(a, b)).getString("two"))
        assertEquals("a", CustomEmojis.names(listOf(a, b)).getString("one"))
    }

    @Test fun typingFindsTheTokenAtTheEndAfterASpaceOrTheStart() {
        assertEquals("ka", CustomEmojis.typing("hi :ka"))
        assertEquals("ka", CustomEmojis.typing(":ka"))
        assertNull(CustomEmojis.typing("12:30"))
        assertNull(CustomEmojis.typing("hi :ka_wave: "))
        assertNull(CustomEmojis.typing("hi :"))
        assertNull(CustomEmojis.typing("hi :KA"))
    }

    @Test fun suggestionsMatchNamesAndAliasesOfLiveEmojiOnly() {
        assertEquals(listOf("ka_wave"), CustomEmojis.matches(model.customEmoji, "hel").map { it.name })
        assertEquals(listOf("tea"), CustomEmojis.matches(model.customEmoji, "te").map { it.name })
        assertEquals(emptyList<String>(), CustomEmojis.matches(model.customEmoji, "old"))
        val many = (1..12).map { CustomEmoji("e$it", "same$it", emptyList(), "Custom", hash, false, false) }
        assertEquals(8, CustomEmojis.matches(many, "same").size)
        val e = model.customEmoji.first { it.name == "ka_wave" }
        assertEquals("hi :ka_wave: ", CustomEmojis.complete("hi :ka", e))
        assertEquals("hi", CustomEmojis.complete("hi", e))
    }

    @Test fun theTrayAddsATokenWithASpaceBeforeItWhenNeeded() {
        val e = model.customEmoji.first { it.name == "tea" }
        assertEquals(":tea: ", CustomEmojis.append("", e))
        assertEquals("hi :tea: ", CustomEmojis.append("hi", e))
        assertEquals("hi :tea: ", CustomEmojis.append("hi ", e))
        assertEquals(listOf("Custom", "Kai"), CustomEmojis.grouped(model.customEmoji).map { it.first }.sorted())
        assertEquals(listOf("tea"), CustomEmojis.grouped(model.customEmoji).first { it.first == "Custom" }.second.map { it.name })
    }

    @Test fun theManagersRulesAreWebs() {
        val all = model.customEmoji
        assertNull(CustomEmojis.problem(all, "new_one", true))
        assertEquals("Choose an image and a 2–32 character lowercase name.", CustomEmojis.problem(all, "new_one", false))
        for (bad in listOf("", "a", "Kai", "two-words", "x".repeat(33))) {
            assertEquals("Choose an image and a 2–32 character lowercase name.", CustomEmojis.problem(all, bad, true))
        }
        assertEquals("That emoji name is already in use.", CustomEmojis.problem(all, "tea", true))
        // a retired name can be reused
        assertNull(CustomEmojis.problem(all, "old_one", true))
        assertNull(CustomEmojis.gifProblem(128, 128, 1000))
        assertEquals("Animated GIFs must already be square and at most 128 px.", CustomEmojis.gifProblem(120, 128, 1000))
        assertEquals("Animated GIFs must already be square and at most 128 px.", CustomEmojis.gifProblem(256, 256, 1000))
        assertEquals("Cropped emoji exceeds 256 KB.", CustomEmojis.gifProblem(128, 128, 256L * 1024 + 1))
    }

    @Test fun theCreatePayloadIsWhatWebSendsAndTheCoreAccepts() {
        assertEquals(listOf("hi", "ka_hi"), CustomEmojis.parseAliases(" hi, KA, ka_hi ,x,hi"))
        val payload = CustomEmojis.createPayload(" new_one ", "hi, ka_hi", " ", hash, false)
        assertEquals("new_one", payload.getString("name"))
        assertEquals("Custom", payload.getString("category"))
        assertEquals(listOf("hi", "ka_hi"), (0 until payload.getJSONArray("aliases").length()).map { payload.getJSONArray("aliases").getString(it) })
        // through the real projection, so the core's own rules apply
        val after = D132Ops.model(ops + Spec("emoji.create", "server", u(0x704), payload))
        assertEquals("new_one", after.emojiById.getValue(u(0x704)).name)
        assertEquals(hash, after.emojiById.getValue(u(0x704)).blobHash)
    }

    @Test fun aBodyDrawsEachCustomEmojiEntityInPlaceOfItsToken() {
        val entities = """[{"type":"bold","offset":0,"length":2},{"type":"custom_emoji","offset":3,"length":9,"emoji_id":"$wave"}]"""
        val body = RichTextPresentation.prepare("hi :ka_wave: x", entities, emptyList())
        assertEquals("hi :ka_wave: x", body.text)
        assertEquals(wave, body.spans.single { it.type == "custom_emoji" }.emojiId)
        // inside a hidden spoiler it is dropped with the rest
        val hidden = RichTextPresentation.prepare("hi :ka_wave: x", """[{"type":"spoiler","offset":0,"length":12},{"type":"custom_emoji","offset":3,"length":9,"emoji_id":"$wave"}]""", emptyList())
        assertTrue(hidden.spans.none { it.type == "custom_emoji" })
    }

    @Test fun aDeviceRecordKeepsTheAdminFlagItWasGiven() {
        val dev = DeviceRecord("http://x", "d", "00000001", "a", "s", 0, null, true)
        assertEquals(true, DeviceRecord.fromJson(dev.toJson()).isAdmin)
        assertNull(DeviceRecord.fromJson(dev.copy(isAdmin = null).toJson()).isAdmin)
        assertEquals(false, DeviceRecord.fromJson(dev.copy(isAdmin = false).toJson()).isAdmin)
    }
}
