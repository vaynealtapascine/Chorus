package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLibraryTest {
    private fun file(id: String, mime: String, spoiler: Boolean = false) =
        ChatAttachment(id, "h$id", null, "$id.bin", mime, 10, "", spoiler)

    private fun message(id: String, at: Long, vararg files: ChatAttachment, cw: String? = null) =
        ChatMessage(id, "c", listOf("kai"), "", at, cw, "all", emptySet(), "acct", null, files.toList())

    @Test fun splitsMediaFromFilesNewestFirst() {
        val old = message("m1", 1, file("a", "image/png"), file("b", "application/pdf"))
        val new = message("m2", 2, file("c", "video/mp4"), file("d", "image/jpeg"))
        assertEquals(listOf("c", "d", "a"), ChatLibrary.media(listOf(old, new)).map { it.attachment.id })
        assertEquals(listOf("b"), ChatLibrary.files(listOf(old, new)).map { it.attachment.id })
        assertEquals("m1", ChatLibrary.files(listOf(old, new)).single().message.id)
    }

    @Test fun spoilersAndContentWarningsStayCovered() {
        assertTrue(ChatLibrary.covered(ChatFileItem(file("a", "image/png", spoiler = true), message("m", 1))))
        assertTrue(ChatLibrary.covered(ChatFileItem(file("a", "image/png"), message("m", 1, cw = "food"))))
        assertFalse(ChatLibrary.covered(ChatFileItem(file("a", "image/png"), message("m", 1))))
    }

    @Test fun readableSizes() {
        assertEquals("900 B", ChatLibrary.size(900))
        assertEquals("2 KB", ChatLibrary.size(2048))
        assertEquals("1.5 MB", ChatLibrary.size(1_572_864))
    }
}
