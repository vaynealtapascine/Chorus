package garden.vayne.chorus.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import garden.vayne.chorus.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RichEditorControllerTest {
    @Test fun realFieldStateSelectionAndHistoryAreRestoredTogether() {
        val text = "@Kai @Kai"
        val entities = """[{"type":"mention","offset":0,"length":4,"target_type":"member","target_id":"alpha"},{"type":"mention","offset":5,"length":4,"target_type":"group","target_id":"beta"}]"""
        var saved = RichEditHistory.open(text, entities, text)
        val controller = RichEditorController(saved) { saved = it }
        controller.input("@Kai", TextRange(0, 5), TextRange.Zero, listOf(RichInputChange(0, 5, 0, 0)))
        controller.field.edit { replace(0, length, "@Kai"); selection = TextRange.Zero }
        controller.undo()
        assertEquals(text, controller.field.text.toString()); assertEquals(TextRange(0, 5), controller.field.selection)
        controller.redo()
        assertEquals("@Kai", controller.field.text.toString()); assertEquals(TextRange.Zero, controller.field.selection)
        controller.undo()
        val recreated = RichEditorController(RichEditHistory.decode(JSONObject(saved.json().toString()))) { saved = it }
        recreated.redo()
        assertEquals("@Kai", recreated.field.text.toString())
        assertEquals("beta", saved.parse("").getJSONArray("entities").getJSONObject(0).getString("target_id"))
    }
    @Test fun manyLineDenseFormattingUsesExactLinearMapsAndPreservesEveryEntityThroughUndo() {
        val text = "@Kai\n".repeat(1999) + "@Kai"
        val entities = JSONArray().also { a -> repeat(2000) { a.put(JSONObject().put("type", "mention").put("offset", it * 5)
            .put("length", 4).put("target_type", if (it % 2 == 0) "member" else "group").put("target_id", "target-$it")) } }.toString()
        val initial = RichEditHistory.open(text, entities, text).selection(0, text.length)
        for (format in listOf<(TextFieldValue) -> MappedFormatting>(
            { EditorFormatting.mappedLines(it, "> ") }, { EditorFormatting.mappedLines(it, "• ") },
            { EditorFormatting.mappedLines(it, "", true) }, { EditorFormatting.mappedInline(it, "**") },
            { EditorFormatting.mappedLink(it, "https://example.org") })) {
            val start = System.nanoTime()
            val edit = format(TextFieldValue(text, TextRange(0, text.length)))
            val history = initial.format(edit.value.text, edit.value.selection.start, edit.value.selection.end, edit.sources)
            val updateMs = (System.nanoTime() - start) / 1_000_000
            val parseStart = System.nanoTime()
            val rich = history.parse("")
            val parseMs = (System.nanoTime() - parseStart) / 1_000_000
            println("DENSE_FORMAT units=${edit.value.text.length} update_ms=$updateMs parse_ms=$parseMs")
            val atoms = rich.getJSONArray("entities").let { a -> List(a.length()) { a.getJSONObject(it) }.filter { it.optString("type") == "mention" } }
            assertEquals(2000, atoms.size)
            atoms.forEachIndexed { i, e -> assertEquals("target-$i", e.getString("target_id")) }
            assertEquals(initial.current, history.undo().current)
            assertEquals(history.current, history.undo().redo().current)
            assertTrue("Linear mapping of a 10k-unit field should complete within 100 ms on the host", updateMs < 100)
        }
    }
}
