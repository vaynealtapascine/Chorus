package garden.vayne.chorus.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import garden.vayne.chorus.data.DeviceRecord
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A small file cache for authenticated avatar blobs; only cache files are replaced. */
internal object AvatarBlobs {
    private const val MAX_BYTES = 10 * 1024 * 1024
    private val HASH = Regex("^[0-9a-f]{64}$")

    @Synchronized
    fun load(ctx: Context, hash: String, device: DeviceRecord): Bitmap? {
        if (!HASH.matches(hash)) return null
        val dir = File(ctx.cacheDir, "avatar-blobs")
        if (!dir.exists() && !dir.mkdirs()) return null
        val target = File(dir, hash)
        if (!target.isFile) {
            val partial = File(dir, "$hash.part")
            try {
                val url = URL("${device.base.trimEnd('/')}/api/v1/blobs/$hash")
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 20_000
                    connection.setRequestProperty("Authorization", "Bearer ${device.session}")
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
                    connection.inputStream.use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var total = 0
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > MAX_BYTES) throw IOException("avatar exceeds cache limit")
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    if (!partial.renameTo(target)) throw IOException("could not finish avatar cache file")
                } finally { connection.disconnect() }
            } catch (error: Exception) {
                Log.w("ChorusAvatar", "Avatar download failed", error)
                return null
            } finally { partial.delete() }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(target.path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
        return BitmapFactory.decodeFile(target.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
