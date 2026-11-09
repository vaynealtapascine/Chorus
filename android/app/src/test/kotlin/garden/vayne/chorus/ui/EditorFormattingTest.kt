package garden.vayne.chorus.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import uniffi.chorus_ffi.parseMarkup

class EditorFormattingTest {
    @Test fun formattingPreservesUnicodeAndReversedSelectionAndTogglesWithoutNesting() {
        val value = TextFieldValue("A 🌿 quiet day", TextRange(10, 5))
        val formatted = EditorFormatting.inline(value, "**")
        assertEquals("A 🌿 **quiet** day", formatted.text)
        assertEquals(TextRange(12, 7), formatted.selection)
        assertTrue(EditorFormatting.active(formatted, "**"))
        assertEquals(value, EditorFormatting.inline(formatted, "**"))
    }
    @Test fun emptyCaretStaysInsideMarkersAndImeCompositionIsNeverDiscarded() {
        assertEquals(TextFieldValue("a ****b", TextRange(4)), EditorFormatting.inline(TextFieldValue("a b", TextRange(2)), "**"))
        val composing = TextFieldValue("draft", TextRange(5), composition = TextRange(0, 5))
        assertEquals(composing, EditorFormatting.inline(composing, "**"))
        assertEquals(composing, EditorFormatting.lines(composing, "> "))
        assertEquals(composing, EditorFormatting.link(composing, "https://example.org"))
    }
    @Test fun everyInlineToolProducesTheExistingCoreEntity() {
        for ((marker, type) in listOf("**" to "bold", "_" to "italic", "~~" to "strikethrough", "`" to "code", "||" to "spoiler")) {
            val formatted = EditorFormatting.inline(TextFieldValue("quiet", TextRange(0, 5)), marker)
            val parsed = JSONObject(parseMarkup(formatted.text, ""))
            assertEquals("quiet", parsed.getString("text"))
            assertEquals(type, parsed.getJSONArray("entities").getJSONObject(0).getString("type"))
        }
    }
    @Test fun linksAndQuotesUseCoreWhileListsRemainReadablePlainText() {
        val linked = EditorFormatting.link(TextFieldValue("garden", TextRange(0, 6)), "https://example.org")
        val link = JSONObject(parseMarkup(linked.text, "")).getJSONArray("entities").getJSONObject(0)
        assertEquals("text_link", link.getString("type"))
        assertEquals("https://example.org", link.getString("url"))
        val atCaret = EditorFormatting.link(TextFieldValue("Go ", TextRange(3)), "https://example.org")
        assertEquals("Go https://example.org", JSONObject(parseMarkup(atCaret.text, "")).getString("text"))
        assertTrue(runCatching { EditorFormatting.link(TextFieldValue("label"), "javascript:bad") }.isFailure)
        val quote = EditorFormatting.lines(TextFieldValue("first\nsecond", TextRange(0, 12)), "> ")
        assertEquals("blockquote", JSONObject(parseMarkup(quote.text, "")).getJSONArray("entities").getJSONObject(0).getString("type"))
        val list = EditorFormatting.lines(TextFieldValue("first\nsecond", TextRange(0, 12)), "", numbered = true)
        assertEquals("1. first\n2. second", JSONObject(parseMarkup(list.text, "")).getString("text"))
        assertEquals("first\n> second", EditorFormatting.lines(TextFieldValue("first\nsecond", TextRange(9)), "> ").text)
    }

    @Test fun paragraphStylesReplaceEachOtherOnTheCaretLine() {
        val v = TextFieldValue("one\ntwo", TextRange(5))
        val h1 = EditorFormatting.style(v, "heading1")
        assertEquals("one\n# two", h1.text); assertEquals(TextRange(7), h1.selection)
        assertEquals("heading1", lineStyle(h1))
        val h2 = EditorFormatting.style(h1, "heading2")
        assertEquals("one\n## two", h2.text); assertEquals("heading2", lineStyle(h2))
        val quote = EditorFormatting.style(h2, "quote")
        assertEquals("one\n> two", quote.text)
        val body = EditorFormatting.style(quote, "")
        assertEquals("one\ntwo", body.text); assertEquals(TextRange(5), body.selection)
        val both = EditorFormatting.style(TextFieldValue("a\nb", TextRange(0, 3)), "quote")
        assertEquals("> a\n> b", both.text)
        assertEquals("a heading takes one line", "# a\nb", EditorFormatting.style(TextFieldValue("a\nb", TextRange(0, 3)), "heading1").text)
    }

    @Test fun codeBlocksAndSpoilerBlocksWrapWholeLines() {
        val fenced = EditorFormatting.fence(TextFieldValue("say\nlet x", TextRange(5)))
        assertEquals("say\n```\nlet x\n```", fenced.text)
        assertTrue(EditorFormatting.inFence(fenced))
        assertEquals("say\nlet x", EditorFormatting.fence(fenced).text)
        assertEquals("||hide this||", EditorFormatting.spoilerBlock(TextFieldValue("hide this", TextRange(2))).text)
    }
}
