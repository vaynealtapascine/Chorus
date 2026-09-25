package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

class OfflineFilesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun catalogueKeepsLiveSmallFilesAndSharedImagesOnce() {
        val original = "a".repeat(64)
        val thumbnail = "b".repeat(64)
        val avatar = "c".repeat(64)
        val emoji = "d".repeat(64)
        val tooLarge = "e".repeat(64)
        val deleted = "f".repeat(64)
        fun row(fields: JSONObject, exists: Boolean = true) = JSONObject()
            .put("exists", exists).put("fields", fields)
        val attachments = JSONObject()
            .put("small", row(JSONObject().put("blob_hash", original).put("thumb_blob_hash", thumbnail)
                .put("size", Blobs.MAX_KEPT)))
            .put("large", row(JSONObject().put("blob_hash", tooLarge).put("thumb_blob_hash", thumbnail)
                .put("size", Blobs.MAX_KEPT + 1)))
            .put("deleted", row(JSONObject().put("blob_hash", deleted).put("size", 1)
                .put("deleted_at", 1)))
            .put("tombstone", row(JSONObject().put("blob_hash", deleted).put("size", 1), false))
        val projection = JSONObject().put("rows", JSONObject()
            .put("attachment", attachments)
            .put("member", JSONObject().put("one", row(JSONObject().put("avatar_blob", avatar))))
            .put("member_group", JSONObject().put("group", row(JSONObject().put("avatar_blob", avatar))))
            .put("custom_emoji", JSONObject().put("one", row(JSONObject().put("blob_hash", emoji)))
                .put("bad", row(JSONObject().put("blob_hash", "../not-a-hash")))))
        assertEquals(listOf(original, thumbnail, avatar, emoji), OfflineFiles.filesOf(projection))
    }

    @Test fun durableCopyChecksHashAndLimitBeforePublishing() {
        val source = folder.newFile("source").apply { writeText("available without the server") }
        val hash = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
            .joinToString("") { "%02x".format(it) }
        val dir = folder.newFolder("kept")
        val good = File(dir, hash)
        assertTrue(Blobs.promoteVerified(source, good, hash))
        assertEquals(source.readText(), good.readText())

        val wrong = File(dir, "0".repeat(64))
        assertFalse(Blobs.promoteVerified(source, wrong, wrong.name))
        assertFalse(wrong.exists())

        val large = folder.newFile("large")
        RandomAccessFile(large, "rw").use { it.setLength(Blobs.MAX_KEPT + 1) }
        val tooLarge = File(dir, "1".repeat(64))
        assertFalse(Blobs.promoteVerified(large, tooLarge, tooLarge.name))
        assertFalse(tooLarge.exists())
    }
}
