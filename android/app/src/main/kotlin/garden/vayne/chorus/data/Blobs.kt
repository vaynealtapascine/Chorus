package garden.vayne.chorus.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Files (attachments, avatars, emoji) on Android, like the web's `sync/uploads.ts` + `sync/blobs.ts`
 * (CLIENTS.md §4.3): a picked file is copied into app storage under its SHA-256 before its message
 * is queued, uploaded in the background by [UploadWork] (resuming from the server's offset, 4 MB
 * chunks), and kept in a cache afterwards so it shows offline. Reads look in the queue, then the
 * cache, then the server, always through [Api.http] so a Chorus Home certificate pin applies.
 */
object Blobs {
    private val HASH = Regex("^[0-9a-f]{64}$")
    private val ACCOUNT = Regex("^[0-9a-f-]{36}$")
    private const val CHUNK = 4 * 1024 * 1024
    const val MAX_UPLOAD = 100L * 1024 * 1024
    const val MAX_KEPT = 20L * 1024 * 1024

    data class Staged(val hash: String, val size: Long, val mime: String)

    private fun pendingDir(ctx: Context) = File(ctx.filesDir, "pending-blobs").apply { mkdirs() }
    private fun cacheDir(ctx: Context, kind: String) = File(ctx.cacheDir, "$kind-blobs").apply { mkdirs() }
    private fun keptDir(ctx: Context, accountId: String): File? =
        if (ACCOUNT.matches(accountId)) File(ctx.filesDir, "kept-blobs/$accountId").apply { mkdirs() } else null

    /** Copy [input] into the upload queue, hashing as it goes. Throws past [MAX_UPLOAD]. */
    fun stage(ctx: Context, input: InputStream, mime: String, accountId: String): Staged {
        val dir = pendingDir(ctx)
        val tmp = File.createTempFile("stage", ".part", dir)
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            input.use { src ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        size += n
                        if (size > MAX_UPLOAD) throw IOException("That file is over ${MAX_UPLOAD / 1024 / 1024} MB.")
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val target = File(dir, hash)
            if (target.exists()) tmp.delete() else if (!tmp.renameTo(target)) throw IOException("could not queue the file")
            File(dir, "$hash.json").writeText(JSONObject().put("mime", mime).put("account", accountId).put("size", size).toString())
            return Staged(hash, size, mime)
        } finally {
            tmp.delete()
        }
    }

    /** A smaller JPEG (≤ 480 px) to show while an image loads, or null. (WebP encoding needs API 30.) */
    fun thumbnail(ctx: Context, source: File, accountId: String): Staged? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 480 || bounds.outHeight / (sample * 2) >= 480) sample *= 2
        val decoded = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = minOf(1f, 480f / maxOf(decoded.width, decoded.height))
        val small = Bitmap.createScaledBitmap(decoded, maxOf(1, (decoded.width * scale).toInt()), maxOf(1, (decoded.height * scale).toInt()), true)
        val bytes = java.io.ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
        stage(ctx, bytes.inputStream(), "image/jpeg", accountId)
    }.getOrNull()

    fun pendingFile(ctx: Context, hash: String): File? = File(pendingDir(ctx), hash).takeIf { HASH.matches(hash) && it.isFile }

    /** The blob as a local file: queued, cached, or downloaded now (≤ [maxBytes]); null offline. */
    fun file(ctx: Context, hash: String, device: DeviceRecord?, kind: String = "chat", maxBytes: Long = MAX_UPLOAD): File? {
        if (!HASH.matches(hash)) return null
        pendingFile(ctx, hash)?.let { return it }
        device?.let { dev -> keptDir(ctx, dev.accountId)?.let { File(it, hash).takeIf(File::isFile) } }?.let { return it }
        val target = File(cacheDir(ctx, kind), hash)
        if (target.isFile) return target
        device ?: return null
        // A foreground open and the background keeper may fetch the same hash together.
        // Give each request its own partial so neither can corrupt the other's download.
        val partial = try { File.createTempFile("$hash-", ".part", target.parentFile) }
            catch (e: IOException) { Log.w("ChorusBlobs", "could not start download", e); return null }
        return try {
            val req = Request.Builder().url("${device.base.trimEnd('/')}/api/v1/blobs/$hash")
                .header("Authorization", "Bearer ${device.session}").build()
            Api.http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                val body = r.body ?: return null
                if (body.contentLength() > maxBytes) return null
                body.byteStream().use { input ->
                    partial.outputStream().use { out ->
                        val buf = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) throw IOException("blob over the cache limit")
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
            if (target.isFile) return target
            if (!partial.renameTo(target)) throw IOException("could not finish the cache file")
            target
        } catch (e: Exception) {
            Log.w("ChorusBlobs", "download failed", e)
            null
        } finally {
            partial.delete()
        }
    }

    /** Promote a verified, account-visible file out of Android's evictable cache. */
    fun keep(ctx: Context, hash: String, device: DeviceRecord): Boolean {
        if (!HASH.matches(hash)) return false
        val dir = keptDir(ctx, device.accountId) ?: return false
        val target = File(dir, hash)
        if (target.isFile) return true
        val source = pendingFile(ctx, hash)
            ?: listOf("chat", "avatar", "emoji").firstNotNullOfOrNull { kind ->
                File(cacheDir(ctx, kind), hash).takeIf(File::isFile)
            }
            ?: file(ctx, hash, device, maxBytes = MAX_KEPT)
            ?: return false
        return promoteVerified(source, target, hash)
    }

    /** A failed copy never leaves a visible durable file under the content hash. */
    internal fun promoteVerified(source: File, target: File, hash: String): Boolean {
        if (!HASH.matches(hash) || source.length() > MAX_KEPT) return false
        target.parentFile?.mkdirs()
        val tmp = File.createTempFile("keep-", ".part", target.parentFile)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            source.inputStream().use { input -> tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_KEPT) return false
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            } }
            if (digest.digest().joinToString("") { "%02x".format(it) } != hash) return false
            if (target.isFile) return true
            return tmp.renameTo(target)
        } catch (e: Exception) {
            Log.w("ChorusBlobs", "could not keep file", e)
            return false
        } finally {
            tmp.delete()
        }
    }

    /** Decode an image blob no larger than [maxPx] on its long side. */
    fun image(ctx: Context, hash: String, device: DeviceRecord?, kind: String = "chat", maxPx: Int = 1024, maxBytes: Long = MAX_UPLOAD): Bitmap? {
        val f = file(ctx, hash, device, kind, maxBytes) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxPx || bounds.outHeight / sample > maxPx) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Upload every queued blob of this account; each finished one moves into the chat cache. */
    suspend fun flush(ctx: Context, device: DeviceRecord): Boolean = withContext(Dispatchers.IO) {
        var ok = true
        for (meta in pendingDir(ctx).listFiles { f -> f.name.endsWith(".json") }.orEmpty()) {
            val hash = meta.name.removeSuffix(".json")
            val file = File(pendingDir(ctx), hash)
            val info = runCatching { JSONObject(meta.readText()) }.getOrNull()
            if (info == null || !file.isFile) {
                meta.delete()
                continue
            }
            if (info.optString("account") != device.accountId) continue
            try {
                send(device, hash, file, info.optString("mime", "application/octet-stream"))
                val cached = File(cacheDir(ctx, "chat"), hash)
                if (!cached.exists() && !file.renameTo(cached)) file.copyTo(cached, overwrite = true)
                file.delete()
                meta.delete()
            } catch (e: Exception) {
                Log.w("ChorusBlobs", "upload paused", e)
                ok = false
            }
        }
        ok
    }

    private fun send(device: DeviceRecord, hash: String, file: File, mime: String) {
        val url = "${device.base.trimEnd('/')}/api/v1/blobs/$hash"
        val auth = "Bearer ${device.session}"
        val head = Api.http.newCall(Request.Builder().url(url).head().header("Authorization", auth).build()).execute()
        val offset0 = head.use { r ->
            when (r.code) {
                200 -> return
                206 -> r.header("upload-offset")?.toLongOrNull() ?: 0L
                404 -> 0L
                else -> throw IOException("Blob HEAD: ${r.code}")
            }
        }
        val size = file.length()
        if (offset0 !in 0..size) throw IOException("invalid upload offset")
        var offset = offset0
        file.inputStream().use { input ->
            var skipped = 0L
            while (skipped < offset) skipped += input.skip(offset - skipped)
            val buf = ByteArray(CHUNK)
            while (offset < size) {
                val want = minOf(CHUNK.toLong(), size - offset).toInt()
                var read = 0
                while (read < want) {
                    val n = input.read(buf, read, want - read)
                    if (n < 0) throw IOException("file shrank")
                    read += n
                }
                val body = buf.copyOf(read).toRequestBody(mime.toMediaTypeOrNull())
                val req = Request.Builder().url(url).put(body).header("Authorization", auth)
                    .header("Content-Range", "bytes $offset-${offset + read - 1}/$size").build()
                Api.http.newCall(req).execute().use { r ->
                    if (r.code !in listOf(200, 201, 202)) throw IOException("Blob PUT: ${r.code}")
                }
                offset += read
            }
        }
    }
}

/** Uploads queued blobs when there's a network; retried with backoff until they're all up. */
class UploadWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val device = Chorus.get(applicationContext).device ?: return Result.success()
        return if (Blobs.flush(applicationContext, device)) Result.success() else Result.retry()
    }

    companion object {
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<UploadWork>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("chorus-uploads", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
