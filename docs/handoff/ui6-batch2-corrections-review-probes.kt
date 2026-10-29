// Independent review probes. Copy to app/src/test/.../data/CorrectionReviewProbeTest.kt.
package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class CorrectionReviewProbeTest {
    private val es = """[{"type":"mention","offset":0,"length":4,"target_type":"member","target_id":"alpha"},{"type":"mention","offset":5,"length":4,"target_type":"group","target_id":"beta"}]"""
    private val names = """{"mentions":{"kai":{"target_type":"member","target_id":"directory"}}}"""
    private fun ids(r: JSONObject) = r.getJSONArray("entities").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("type") == "mention" }.map { it.getString("target_id") } }

    @Test fun latestOrphanValueAtReservedIdMustPreventOtherIdAdoption() {
        val details = ProfileDetails()
        val fields = JSONArray()
        for ((name, type) in ProfileDetails.slots) {
            val f = details.field("mine", name, type)
            fields.put(JSONObject().put("id", f.id).put("name", name).put("type", type).put("exists", false).put("value", ""))
        }
        val reserved = fields.getJSONObject(1).getString("id")
        val latestDetails = ProfileDetails(listOf(ProfileDetail("other", "Website", "url", "public")),
            mapOf("m" to mapOf(reserved to "https://concurrent.example")))
        val member = Member("m", "Kai", null, null, "#123456", emptyList(), null, false)
        val model = Model(listOf(member), emptyList(), emptyMap(), emptyList(), null, emptyList(), profileDetails = latestDetails)
        val result = runCatching { MemberProfileSave.plan("m", true, JSONObject().put("name", "Edited"), fields,
            listOf("", "https://draft.example", "", ""), model, "mine") }
        println("LATEST_ORPHAN plan=" + result.getOrNull())
        assertTrue("Latest reserved-ID value must reject rebinding before any operation", result.isFailure)
    }

    @Test fun formattingAndBoundaryChangesDoNotRetargetOtherOccurrence() {
        val source = "@Kai @Kai"
        val state = RichTextEdit.open(source, es, source)
        for ((edited, expected) in listOf(
            "**@Ka**i @Kai" to listOf("alpha", "beta"),
            "@K**a**i @Kai" to listOf("alpha", "beta"),
            "\\@Kai @Kai" to listOf("beta"),
            "`@Kai` @Kai" to listOf("beta"),
            "@Kax @Kai" to listOf("beta"),
            "x@Kai @Kai" to listOf("beta"),
            "[**@Kai**](https://example.org) @Kai" to listOf("alpha", "beta")
        )) assertEquals(edited, expected, ids(RichTextEdit.parse(state, edited, names)))
    }

    @Test fun undoOfFormattingPreservesOriginalTargets() {
        val source = "@Kai @Kai"
        var state = RichTextEdit.open(source, es, source)
        val formatted = "**$source**"
        state = RichTextEdit.update(state, formatted, 0, source.length, source.length + 2, true)
        // TextField undo is an ordinary onValueChange event, with the selected text retained.
        state = RichTextEdit.update(state, source, 2, source.length + 2, source.length)
        println("UNDO_FORMAT=" + RichTextEdit.parse(state, source, names))
        assertEquals(listOf("alpha", "beta"), ids(RichTextEdit.parse(state, source, names)))
    }

    @Test fun textUndoMustRestoreDeletedOccurrenceIdentityBeforeFurtherTyping() {
        val source = "@Kai @Kai"
        var state = RichTextEdit.open(source, es, source)
        state = RichTextEdit.update(state, "@Kai", 0, 5, 0)
        assertEquals(listOf("beta"), ids(RichTextEdit.parse(state, "@Kai", names)))
        // Undo restores the text deleted at [0,5). Unlike retyping, undo should restore its ID.
        // The UI currently sends both operations through this same text-only callback.
        state = RichTextEdit.update(state, source, 0, 0, 5)
        state = RichTextEdit.update(state, "$source!", source.length, source.length, source.length + 1)
        val actual = ids(RichTextEdit.parse(state, "$source!", names))
        println("UNDO_DELETE=$actual")
        assertEquals(listOf("alpha", "beta"), actual)
    }

    @Test fun matchingNeverInventsOrDuplicatesUnitOrigins() {
        val random = Random(1591)
        repeat(500) {
            val old = (0 until random.nextInt(1, 50)).map { "@Kai* >😀"[random.nextInt(9)] }.joinToString("")
            val new = (0 until random.nextInt(1, 50)).map { "@Kai* >😀"[random.nextInt(9)] }.joinToString("")
            val map = RichTextEdit.matches(old, new, null, null)
            val mapped = map.filter { it >= 0 }
            assertEquals(mapped.distinct(), mapped)
            assertTrue(mapped.zipWithNext().all { (a,b) -> a < b })
            for (i in map.indices) if (map[i] >= 0) assertEquals(old[map[i]], new[i])
        }
    }

    @Test fun mappedParserOutputAndOffsetsAgreeOnAdversarialMarkup() {
        val random = Random(61030)
        val tokens = listOf("@Kai", "😀", "\\@Kai", "\\\\", "\\&", "**", "__", "`", "```\n", "\n> ", "\n>> ", "[", "](https://example.org)", "a", " ", "\n", ":wave:")
        repeat(600) {
            val source = (0 until random.nextInt(1, 60)).joinToString("") { tokens[random.nextInt(tokens.size)] }
            val mapped = JSONObject(uniffi.chorus_ffi.parseMarkupMapped(source, names))
            val plain = JSONObject(uniffi.chorus_ffi.parseMarkup(source, names))
            assertEquals(source, plain.getString("text"), mapped.getString("text"))
            fun entityRows(a: JSONArray) = (0 until a.length()).map { i -> a.getJSONObject(i).let { e ->
                e.keys().asSequence().associateWith { e.get(it).toString() }
            } }
            assertEquals(source, entityRows(plain.getJSONArray("entities")), entityRows(mapped.getJSONArray("entities")))
            val text = mapped.getString("text")
            val positions = mapped.getJSONArray("sources")
            assertEquals(source, text.length, positions.length())
            for (i in text.indices) {
                val at = positions.getInt(i)
                assertTrue(source, at in source.indices)
                assertEquals(source, text[i], source[at])
                if (i > 0) assertTrue(source, positions.getInt(i - 1) < at)
            }
        }
    }

    @Test fun nativeEditorFeatureMatchesNoEditorWasmCorpus() {
        val rows = JSONArray(java.io.File("F:/DunBuild/chorus-ui6-evidence/batch2/corrections/review/parser-corpus.json").readText())
        fun entities(a: JSONArray) = (0 until a.length()).map { i -> a.getJSONObject(i).let { e -> e.keys().asSequence().associateWith { e.get(it).toString() } } }
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val actual = JSONObject(uniffi.chorus_ffi.parseMarkupMapped(row.getString("source"), row.getString("names")))
            val expected = row.getJSONObject("rich")
            assertEquals(row.getString("source"), expected.getString("text"), actual.getString("text"))
            assertEquals(row.getString("source"), entities(expected.getJSONArray("entities")), entities(actual.getJSONArray("entities")))
        }
    }

    @Test fun maxBioQuoteCostAndSavedStateSize() {
        // Valid 9,999-unit bio; distinct real-size IDs stress the saved-state and parser path.
        val source = List(2000) { "@Kai" }.joinToString("\n")
        val entities = JSONArray()
        repeat(2000) { i -> entities.put(JSONObject().put("type", "mention").put("offset", i * 5).put("length", 4)
            .put("target_type", "member").put("target_id", "00000000-0000-7000-8000-" + i.toString().padStart(12, '0'))) }
        val state = RichTextEdit.open(source, entities.toString(), source)
        val quote = source.lines().joinToString("\n") { "> $it" }
        val start = System.nanoTime()
        val updated = RichTextEdit.update(state, quote, 0, source.length, quote.length, true)
        val updateMs = (System.nanoTime() - start) / 1_000_000
        val parseStart = System.nanoTime()
        val rich = RichTextEdit.parse(updated, quote, names)
        println("MAX_BIO update_ms=$updateMs parse_ms=" + ((System.nanoTime() - parseStart) / 1_000_000) +
            " state_chars=${state.length} entities_chars=${entities.toString().length} draft_chars=${updated.length}")
        assertEquals(2000, ids(rich).size)
    }
}
