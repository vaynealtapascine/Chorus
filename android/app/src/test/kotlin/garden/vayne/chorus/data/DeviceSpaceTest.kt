package garden.vayne.chorus.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSpaceTest {
    private fun write(f: File, bytes: Int) { f.parentFile?.mkdirs(); f.writeBytes(ByteArray(bytes)) }

    @Test
    fun measuresThreePartsAndClearsOnlyDownloadedFiles() {
        val root = Files.createTempDirectory("space").toFile()
        try {
            val files = File(root, "files"); val cache = File(root, "cache"); val db = File(root, "databases")
            write(File(db, "chorus.db"), 300)
            write(File(files, "kept-blobs/acct/aa"), 500)
            write(File(cache, "chat-blobs/bb"), 200)
            write(File(cache, "avatar-blobs/cc"), 100)
            write(File(files, "pending-blobs/dd"), 50) // waiting to upload: never cleared
            write(File(files, "restore-blobs/acct/ee"), 40)
            write(File(cache, "update-9.apk"), 70)
            assertEquals(DeviceSpace.Usage(300, 800, 160), DeviceSpace.measure(files, cache, db))
            assertEquals(800L, DeviceSpace.clearDownloaded(files, cache))
            assertEquals(DeviceSpace.Usage(300, 0, 160), DeviceSpace.measure(files, cache, db))
            assertTrue(File(files, "pending-blobs/dd").isFile && File(files, "kept-blobs").isDirectory)
        } finally { root.deleteRecursively() }
    }
}
