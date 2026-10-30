package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RichEditHistoryTest {
    private val source = "@Kai @Kai :wave: :wave:"
    private val entities = """[{"type":"mention","offset":0,"length":4,"target_type":"member","target_id":"alpha"},{"type":"mention","offset":5,"length":4,"target_type":"group","target_id":"beta"},{"type":"custom_emoji","offset":10,"length":6,"emoji_id":"one"},{"type":"custom_emoji","offset":17,"length":6,"emoji_id":"two"}]"""
    private val names = """{"mentions":{"kai":{"target_type":"member","target_id":"directory"}},"emoji":{"wave":"new"}}"""
    private fun open() = RichEditHistory.open(source, entities, source)
    private fun replace(h: RichEditHistory, start: Int, end: Int, inserted: String): RichEditHistory =
        h.input(h.current.text.replaceRange(start, end, inserted), start, end, start + inserted.length, start + inserted.length,
            listOf(RichInputChange(start, end, start, start + inserted.length)))
    private fun ids(h: RichEditHistory): List<String> = h.parse(names).getJSONArray("entities").let { a ->
        List(a.length()) { a.getJSONObject(it) }.filter { it.optString("type") in setOf("mention", "custom_emoji") }
            .map { if (it.optString("type") == "mention") it.getString("target_id") else it.getString("emoji_id") }
    }
    private fun restore(h: RichEditHistory) = RichEditHistory.decode(JSONObject(h.json().toString()))

    /** Promoted independent failing probe, with explicit UI Undo semantics rather than a guessed
     * ordinary text callback. RichEditorField routes native Ctrl+Z and IME undo here. */
    @Test fun textUndoMustRestoreDeletedOccurrenceIdentityBeforeFurtherTyping() {
        var h = replace(open(), 0, 5, "")
        assertEquals(listOf("beta", "one", "two"), ids(h))
        h = restore(h).undo()
        assertEquals(0, h.current.start); assertEquals(5, h.current.end)
        h = replace(h, h.current.text.length, h.current.text.length, "!")
        assertEquals(listOf("alpha", "beta", "one", "two"), ids(restore(h)))
    }
    @Test fun selectedReplacementUndoRedoAndBranchesRetainExactTargets() {
        val h = replace(open(), 0, 5, "New ")
        assertEquals(listOf("beta", "one", "two"), ids(h))
        val undo = restore(h).undo()
        assertEquals(listOf("alpha", "beta", "one", "two"), ids(undo))
        val redo = restore(undo).redo()
        assertEquals(h.current, redo.current)
        assertEquals(listOf("beta", "one", "two"), ids(redo))
        val branch = replace(undo, 5, 9, "@Kai")
        assertTrue(branch.future.isEmpty())
        assertEquals(listOf("alpha", "directory", "one", "two"), ids(branch.redo()))
    }
    @Test fun deliberateDeleteRetypeAndIdenticalSelectedRetypingNeverResurrect() {
        var h = replace(open(), 0, 5, "")
        h = replace(h, 0, 0, "@Kai ")
        assertEquals(listOf("directory", "beta", "one", "two"), ids(h))
        h = replace(open(), 0, 4, "@Kai") // real ChangeList reports replacement even though text is equal
        assertEquals(source, h.current.text)
        assertTrue(h.dirty)
        assertFalse(h.undo().dirty)
        assertEquals(listOf("directory", "beta", "one", "two"), ids(restore(h)))
        h = replace(open(), 10, 16, ":wave:")
        assertEquals(listOf("alpha", "beta", "new", "two"), ids(h))
        assertEquals(listOf("alpha", "beta", "one", "two"), ids(h.undo()))
        // A selection-only event cannot become an edit just because a label/history entry matches.
        assertEquals(ids(open()), ids(open().input(source, 0, 4, 4, 4, emptyList())))
    }
    @Test fun sameTextRetypingIsSavedAndOpeningProfileBaselineStaysImmutableAcrossRecreation() {
        val member = Member("m", "Kai", null, null, "#123456", emptyList(), source, false, descriptionEntities = entities)
        val model = Model(listOf(member), emptyList(), emptyMap(), emptyList(), null, emptyList())
        val opening = ProfileEditorDraft.open("m", member, model, "account")
        val edited = ProfileEditorDraft.decode(opening.copy(bio = replace(opening.bio, 0, 4, "@Kai"), fields = opening.fields + ("name" to "Changed")).encode())
        assertEquals("Kai", edited.openingMember?.name)
        assertEquals(source, edited.openingMember?.description)
        val patch = MemberCustomization.patch(edited.openingMember, "Changed", "", "", "", "#123456", source,
            edited.bio.opening.text, null, null, "", names, bioDraft = edited.bio.legacyState(), bioDirty = edited.bio.dirty)
        assertEquals("directory", patch.getJSONArray("description_entities").getJSONObject(0).getString("target_id"))
        assertEquals("beta", patch.getJSONArray("description_entities").getJSONObject(1).getString("target_id"))
        assertEquals("Changed", patch.getString("name"))
    }
    @Test fun repeatedDuplicateLabelsKeepOccurrenceIdentityThroughManyUndoRedoAndRecreation() {
        var h = open()
        repeat(10) { h = replace(h, 0, 5, ""); h = restore(h).undo(); assertEquals(listOf("alpha", "beta", "one", "two"), ids(h)) }
        h = replace(h, 17, 23, "")
        assertEquals(listOf("alpha", "beta", "one"), ids(h))
        assertEquals(listOf("alpha", "beta", "one", "two"), ids(restore(h).undo()))
        assertEquals(listOf("alpha", "beta", "one"), ids(restore(h).undo().redo()))
    }
    @Test fun imeReplacementRangesRetainSurroundingOriginsAndUndoAsAnExplicitAction() {
        val h = replace(open(), 5, 9, "海")
        assertEquals(listOf("alpha", "one", "two"), ids(h))
        assertEquals(listOf("alpha", "beta", "one", "two"), ids(restore(h).undo()))
        val committed = replace(h, 5, 6, "界")
        assertEquals(listOf("alpha", "one", "two"), ids(committed))
        assertEquals(h.current.text, committed.undo().current.text)
    }
    @Test fun sharedAuthorSegmentsRestoreHistoryWithoutChangingAuthorsOrOffsets() {
        val original = ChatMessage("msg", "c", listOf("a"), "$source\n$source", 0, null, "all", emptySet(), "acct", null,
            emptyList(), entities = JSONArray().also { all ->
                repeat(2) { part -> val es = JSONArray(entities); repeat(es.length()) { i -> val e = es.getJSONObject(i)
                    all.put(e.put("offset", e.getInt("offset") + part * (source.length + 1))) } }
            }.toString(), segments = listOf(ChatSegment(0, source.length, listOf("a", "b")), ChatSegment(source.length + 1, source.length, listOf("b"))))
        var d = MessageEditorDraft.open(original)
        val first = replace(d.parts[0], 0, 5, "").undo()
        d = MessageEditorDraft.decode(d.copy(parts = listOf(replace(first, source.length, source.length, "!"), d.parts[1])).encode())
        val payload = ChatEdit.payload(d.original, d.parts.map { it.current.text }, names, d.parts.map { it.legacyState() })
        assertEquals("[\"a\",\"b\"]", payload.getJSONArray("segments").getJSONObject(0).getJSONArray("authors").toString())
        assertEquals(source.length + 2, payload.getJSONArray("segments").getJSONObject(1).getInt("offset"))
        assertEquals(8, payload.getJSONArray("entities").length())
        assertEquals("alpha", payload.getJSONArray("entities").getJSONObject(0).getString("target_id"))
    }
    @Test fun historyHasByteAndFrameBoundsWithoutTruncatingCurrentTextOrRichBaseline() {
        val text = "@Kai ".repeat(2000)
        val es = JSONArray().also { a -> repeat(2000) { a.put(JSONObject().put("type", "mention").put("offset", it * 5).put("length", 4).put("target_type", "member").put("target_id", "original-$it")) } }.toString()
        var h = RichEditHistory.open(text, es, text)
        repeat(80) { h = replace(h, 0, 1, if (it % 2 == 0) "x" else "@") }
        assertTrue(h.past.size <= RichEditHistory.MAX_UNDO)
        assertTrue((h.past + h.current).sumOf { it.cost } <= RichEditHistory.MAX_HISTORY_BYTES)
        h = restore(h)
        assertEquals(10000, h.current.text.length)
        assertEquals(es, h.originalEntities)
        assertEquals(2000, h.parse(names).getJSONArray("entities").length())
    }
}
