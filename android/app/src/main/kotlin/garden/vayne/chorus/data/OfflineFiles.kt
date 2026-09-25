package garden.vayne.chorus.data

import android.content.Context
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/** The projection's file catalogue for D-070. Keep the same 20 MB rule as web/sync/keep.ts. */
object OfflineFiles {
    data class Progress(val done: Int, val total: Int, val missing: Int)

    private val hashPattern = Regex("^[0-9a-f]{64}$")

    fun filesOf(projection: JSONObject): List<String> {
        val files = sortedSetOf<String>()
        val rows = projection.optJSONObject("rows") ?: return emptyList()
        fun add(hash: String?) { if (hash != null && hashPattern.matches(hash)) files.add(hash) }
        fun fields(table: String, visit: (JSONObject) -> Unit) {
            val entries = rows.optJSONObject(table) ?: return
            for (id in entries.keys()) {
                val row = entries.optJSONObject(id) ?: continue
                if (row.optBoolean("exists")) row.optJSONObject("fields")?.let(visit)
            }
        }
        fun JSONObject.hash(key: String): String? =
            if (has(key) && !isNull(key)) optString(key).ifEmpty { null } else null

        fields("attachment") { f ->
            if (f.has("deleted_at") && !f.isNull("deleted_at")) return@fields
            add(f.hash("thumb_blob_hash"))
            if (f.optLong("size") <= Blobs.MAX_KEPT) add(f.hash("blob_hash"))
        }
        fields("member") { add(it.hash("avatar_blob")) }
        fields("member_group") { add(it.hash("avatar_blob")) }
        fields("custom_emoji") { add(it.hash("blob_hash")) }
        return files.toList()
    }

    /** Keep at most three downloads in flight and report files the server could not supply. */
    suspend fun fill(ctx: Context, device: DeviceRecord, hashes: List<String>,
        onProgress: (Progress) -> Unit): Progress = coroutineScope {
        val next = AtomicInteger()
        val progressLock = Any()
        var done = 0
        var missing = 0
        onProgress(Progress(0, hashes.size, 0))
        List(3) {
            async(Dispatchers.IO) {
                while (true) {
                    val index = next.getAndIncrement()
                    if (index >= hashes.size) break
                    val kept = runCatching { Blobs.keep(ctx, hashes[index], device) }.getOrDefault(false)
                    synchronized(progressLock) {
                        done++
                        if (!kept) missing++
                        onProgress(Progress(done, hashes.size, missing))
                    }
                }
            }
        }.awaitAll()
        Progress(done, hashes.size, missing)
    }
}
