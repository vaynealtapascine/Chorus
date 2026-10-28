package garden.vayne.chorus.data

import org.junit.Assert.*
import org.junit.Test

class RichTextPresentationTest {
    @Test fun hiddenSpoilerCannotBeReadOrOpenedThroughAnOverlappingLink() {
        val text = "See https://private.example"
        val length = text.length - 4
        val entities = """[{"type":"spoiler","offset":4,"length":$length},{"type":"url","offset":4,"length":$length}]"""
        val body = RichTextPresentation.prepare(text, entities, emptyList())
        assertFalse(body.text.contains("private"))
        assertFalse(body.spans.any { it.type == "url" })
        assertTrue(body.spans.any { it.type == "spoiler" })
        val revealed = RichTextPresentation.prepare(text, entities, listOf(4))
        assertEquals(text, revealed.text)
        assertTrue(revealed.spans.any { it.type == "url" })
    }
    @Test fun malformedRangesCannotCrashOrMaskUnrelatedText() {
        val body = RichTextPresentation.prepare("hello", """[{"type":"spoiler","offset":-1,"length":4},{"type":"bold","offset":1,"length":2147483647}]""", emptyList())
        assertEquals("hello", body.text)
        assertTrue(body.spans.isEmpty())
    }
    @Test fun audienceNeverWidensUnknownVisibilityAndReadTimeHasAMinimum() {
        assertEquals("Chosen audience", JournalPresentation.audience("buckets"))
        assertEquals("Only me", JournalPresentation.audience("private", true))
        assertEquals("Only our system", JournalPresentation.audience("private"))
        assertEquals("1 min read", JournalPresentation.readTime(""))
        assertEquals("2 min read", JournalPresentation.readTime(List(201) { "word" }.joinToString(" ")))
    }
}
