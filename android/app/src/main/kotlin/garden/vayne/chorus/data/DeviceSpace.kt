package garden.vayne.chorus.data

import java.io.File

/**
 * F9.6 On this device: what Chorus keeps on the phone, in three parts (the replica database,
 * downloaded files, everything else), and clearing the downloaded files. Clearing keeps every
 * message and post, files still waiting to upload, and copies kept for a restore; the files come
 * back from the server when they're opened (nothing on the server is deleted).
 */
object DeviceSpace {
    data class Usage(val messages: Long, val files: Long, val other: Long) {
        val total: Long get() = messages + files + other
    }

    /** [filesDir], [cacheDir] and the databases folder of the app. */
    fun measure(filesDir: File, cacheDir: File, databases: File): Usage {
        val messages = size(databases)
        val files = downloaded(filesDir, cacheDir).sumOf(::size)
        val other = size(filesDir) + size(cacheDir) - files
        return Usage(messages, files, other.coerceAtLeast(0))
    }

    /** Delete the downloaded copies → bytes freed. */
    fun clearDownloaded(filesDir: File, cacheDir: File): Long {
        var freed = 0L
        for (dir in downloaded(filesDir, cacheDir)) {
            dir.walkBottomUp().forEach { f ->
                if (f == dir) return@forEach
                val n = if (f.isFile) f.length() else 0L
                if (f.delete()) freed += n
            }
        }
        return freed
    }

    /** Kept files (`kept-blobs/<account>`) and the per-kind caches (`chat-blobs`, `avatar-blobs`…). */
    private fun downloaded(filesDir: File, cacheDir: File): List<File> =
        listOf(File(filesDir, "kept-blobs")) + (cacheDir.listFiles { f -> f.isDirectory && f.name.endsWith("-blobs") }?.toList() ?: emptyList())

    private fun size(root: File): Long = if (!root.exists()) 0 else root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
