package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RichTextEditTest {
    private val text = "😀 @Kai / @Kai :wave: :wave:"
    private val entities = """[
      {"type":"mention","offset":3,"length":4,"target_type":"member","target_id":"alpha"},
      {"type":"mention","offset":10,"length":4,"target_type":"group","target_id":"beta"},
      {"type":"custom_emoji","offset":15,"length":6,"emoji_id":"wave-a"},
      {"type":"custom_emoji","offset":22,"length":6,"emoji_id":"wave-b"}]
    """
    private val names = """{"mentions":{"k":{"target_type":"member","target_id":"prefix-k"},"kai":{"target_type":"member","target_id":"new-kai"},"kaix":{"target_type":"member","target_id":"new-kaix"}},"emoji":{"wave":"new-wave"}}"""
    private fun atoms(r: JSONObject) = r.getJSONArray("entities").let { a -> (0 until a.length()).map { a.getJSONObject(it) }
        .filter { it.getString("type") in setOf("mention", "custom_emoji") } }
    private fun ids(r: JSONObject) = atoms(r).map { it.optString("target_id", it.optString("emoji_id")) }
    private fun verify(r: JSONObject, expected: List<String>) {
        assertEquals(expected, ids(r))
        for (e in atoms(r)) {
            val token = r.getString("text").substring(e.getInt("offset"), e.getInt("offset") + e.getInt("length"))
            assertTrue(token == "@Kai" || token == "@KaiX" || token == ":wave:")
        }
    }
    @Test fun originalDuplicateLabelsSurviveBeforeBetweenAfterEditsAndFormatting() {
        val initial = RichTextEdit.open(text, entities, text)
        for (edited in listOf("!$text", "$text!", text.replace(" / ", " between 😀 "), text.replace(" / ", " "),
            "**$text**", text.replace("@Kai", "@K**ai**"), "> $text", "[$text](https://example.org)")) {
            verify(RichTextEdit.parse(initial, edited, names), listOf("alpha", "beta", "wave-a", "wave-b"))
        }
    }
    @Test fun sequentialDeletesAndReinsertionsDoNotResurrectStaleIdentities() {
        var state = RichTextEdit.open(text, entities, text)
        val withoutFirst = "😀  / @Kai :wave: :wave:"
        state = RichTextEdit.update(state, withoutFirst, 3, 7, 3)
        verify(RichTextEdit.parse(state, withoutFirst, names), listOf("beta", "wave-a", "wave-b"))
        state = RichTextEdit.update(state, text, 3, 3, 7)
        verify(RichTextEdit.parse(state, text, names), listOf("new-kai", "beta", "wave-a", "wave-b"))
        val extended = text.replaceFirst("@Kai", "@KaiX")
        verify(RichTextEdit.parse(state, extended, names), listOf("new-kaix", "beta", "wave-a", "wave-b"))
    }
    @Test fun duplicateDeletionUsesSelectionRatherThanAnEqualPrefix() {
        val source = "@Kai @Kai"
        val es = """[{"type":"mention","offset":0,"length":4,"target_type":"member","target_id":"alpha"},{"type":"mention","offset":5,"length":4,"target_type":"member","target_id":"beta"}]"""
        val state = RichTextEdit.open(source, es, source)
        verify(RichTextEdit.parse(RichTextEdit.update(state, "@Kai", 0, 5, 0), "@Kai", names), listOf("beta"))
        verify(RichTextEdit.parse(RichTextEdit.update(state, "@Kai", 4, 9, 4), "@Kai", names), listOf("alpha"))
        verify(RichTextEdit.parse(RichTextEdit.update(state, "@Kai @Kai @Kai", 0, 0, 5), "@Kai @Kai @Kai", names), listOf("new-kai", "alpha", "beta"))
    }
    @Test fun escapedAndLiteralAtomsLoseIdentityAndNewTokensUseDirectory() {
        val state = RichTextEdit.open(text, entities, text)
        verify(RichTextEdit.parse(state, "$text @Kai :wave:", names), listOf("alpha", "beta", "wave-a", "wave-b", "new-kai", "new-wave"))
        verify(RichTextEdit.parse(state, "`$text`", names), emptyList())
        verify(RichTextEdit.parse(state, text.replace("@Kai", "\\@Kai").replace(":wave:", "\\:wave:"), names), emptyList())
    }
    @Test fun serializedStylesEscapesAndUtf16RetainOriginalRanges() {
        val originalText = "*😀* @Kai @Kai"
        val es = """[{"type":"bold","offset":5,"length":9},{"type":"mention","offset":5,"length":4,"target_type":"member","target_id":"alpha"},{"type":"mention","offset":10,"length":4,"target_type":"member","target_id":"beta"}]"""
        val member = Member("m", "Kai", null, null, "#123456", emptyList(), originalText, false, descriptionEntities = es)
        val markup = MemberCustomization.markup(member)
        val r = RichTextEdit.parse(RichTextEdit.open(originalText, es, markup), "😀 $markup!", names)
        verify(r, listOf("alpha", "beta"))
        assertEquals(listOf(8, 13), atoms(r).map { it.getInt("offset") })
        assertEquals("😀 $originalText!", r.getString("text"))
    }
    @Test fun memberPatchPromotesTheActualFailingDuplicateMentionProbe() {
        val original = Member("m", "Kai", null, null, "#123456", emptyList(), "@Kai @Kai", false,
            descriptionEntities = """[{"type":"mention","offset":0,"length":4,"target_type":"member","target_id":"alpha"},{"type":"mention","offset":5,"length":4,"target_type":"member","target_id":"beta"}]""")
        val markup = MemberCustomization.markup(original)
        val patch = MemberCustomization.patch(original, original.name, "", "", "", original.color, "$markup!", markup, null, null, "",
            names = ChatEdit.names(original.description!!, original.descriptionEntities, Model.Empty))
        assertEquals(listOf("alpha", "beta"), ids(JSONObject().put("text", patch.getString("description")).put("entities", patch.getJSONArray("description_entities"))))
    }
    @Test fun sharedChatSaveKeepsIdentityAndAuthorSegmentsAfterOffsetChanges() {
        val original = ChatMessage("m", "c", listOf("alpha", "beta"), text + "\n@Kai", 1, null, "all", emptySet(), "account", null, emptyList(),
            entities = JSONArray(entities).put(JSONObject().put("type", "mention").put("offset", text.length + 1).put("length", 4)
                .put("target_type", "member").put("target_id", "gamma")).toString(),
            segments = listOf(ChatSegment(0, text.length, listOf("alpha")), ChatSegment(text.length + 1, 4, listOf("beta"))))
        val payload = ChatEdit.payload(original, listOf("**$text**!", "😀 @Kai!"), names)
        verify(payload, listOf("alpha", "beta", "wave-a", "wave-b", "gamma"))
        val segments = payload.getJSONArray("segments")
        assertEquals(text.length + 2, segments.getJSONObject(1).getInt("offset"))
        assertEquals("beta", segments.getJSONObject(1).getJSONArray("authors").getString(0))
        assertEquals(text.length + 5, atoms(payload).last().getInt("offset"))
    }
    @Test fun supplementaryMentionUnitsStayTogetherAcrossNearbyEdits() {
        val source = "😀 @𐐀 @𐐀"
        val es = """[{"type":"mention","offset":3,"length":3,"target_type":"member","target_id":"alpha"},{"type":"mention","offset":7,"length":3,"target_type":"member","target_id":"beta"}]"""
        val state = RichTextEdit.open(source, es, source)
        val rich = RichTextEdit.parse(state, "**😀 $source**!", "")
        assertEquals(listOf("alpha", "beta"), ids(rich))
        assertEquals(listOf(6, 10), atoms(rich).map { it.getInt("offset") })
        assertEquals(listOf(3, 3), atoms(rich).map { it.getInt("length") })
    }
    @Test fun selectedReplacementIsNewInputWhileToolbarFormattingKeepsOccurrences() {
        val state = RichTextEdit.open(text, entities, text)
        val pasted = RichTextEdit.update(state, "@Kai :wave:", 0, text.length, 11)
        verify(RichTextEdit.parse(pasted, "@Kai :wave:", names), listOf("new-kai", "new-wave"))
        val formatted = RichTextEdit.update(state, "**$text**", 0, text.length, text.length + 4, preserveReplacement = true)
        verify(RichTextEdit.parse(formatted, "**$text**", names), listOf("alpha", "beta", "wave-a", "wave-b"))
    }
    @Test fun restoredAtomsRetainCoreCanonicalOrderingWithEqualRangeStyles() {
        val es = """[{"type":"mention","offset":0,"length":4,"target_type":"member","target_id":"alpha"}]"""
        val rich = RichTextEdit.parse(RichTextEdit.open("@Kai", es, "@Kai"), "[**@Kai**](https://example.org)", names)
        val array = rich.getJSONArray("entities")
        assertEquals(listOf("text_link", "bold", "mention"), (0 until array.length()).map { array.getJSONObject(it).getString("type") })
        verify(rich, listOf("alpha"))
    }
    @Test fun bulkLineFormattingNeverDropsIdentitiesPastTheFastPathLimit() {
        val source = List(180) { "@Kai" }.joinToString("\n")
        val es = JSONArray()
        repeat(180) { i -> es.put(JSONObject().put("type", "mention").put("offset", i * 5).put("length", 4)
            .put("target_type", "member").put("target_id", "m$i")) }
        val quoted = source.lines().joinToString("\n") { "> $it" }
        val state = RichTextEdit.update(RichTextEdit.open(source, es.toString(), source), quoted, 0, source.length,
            preserveReplacement = true)
        val rich = RichTextEdit.parse(state, quoted, names)
        assertEquals(source, rich.getString("text"))
        assertEquals(List(180) { "m$it" }, ids(rich))
        assertEquals(List(180) { it * 5 }, atoms(rich).map { it.getInt("offset") })
    }
    @Test fun savedDraftProvenanceSurvivesJsonRoundTripAndLongFormatting() {
        val long = "@Kai " + "x".repeat(9000) + " @Kai"
        val es = """[{"type":"mention","offset":0,"length":4,"target_type":"member","target_id":"alpha"},{"type":"mention","offset":9006,"length":4,"target_type":"member","target_id":"beta"}]"""
        val state = JSONObject(RichTextEdit.update(RichTextEdit.open(long, es, long), "**$long**", 0, long.length, preserveReplacement = true)).toString()
        verify(RichTextEdit.parse(state, "**$long**!", names), listOf("alpha", "beta"))
    }
}
