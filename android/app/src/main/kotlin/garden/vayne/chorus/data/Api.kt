package garden.vayne.chorus.data

import android.os.Build
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class ApiException(message: String, val code: String = "") : Exception(message)

/** The few REST calls a device needs (API.md §2). Everything else goes over the sync socket. */
object Api {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json".toMediaType()

    suspend fun post(base: String, path: String, body: JSONObject, bearer: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url("$base/api/v1$path").post(body.toString().toRequestBody(JSON))
            if (bearer != null) req.header("Authorization", "Bearer $bearer")
            http.newCall(req.build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                val j = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
                if (!r.isSuccessful) {
                    val e = j.optJSONObject("error")
                    throw ApiException(e?.optString("message")?.ifEmpty { null } ?: "HTTP ${r.code}", e?.optString("code").orEmpty())
                }
                j
            }
        }

    /** Invite links look like `https://host/i/<code>`. → (server base, code). */
    fun parseInvite(link: String): Pair<String, String>? {
        val m = Regex("""^(https?://[^/\s]+)/i/([A-Za-z0-9_-]+)""").find(link.trim()) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    /** Redeem an invite with a fresh device key. `name` is only used for new accounts. */
    suspend fun enrol(link: String, name: String?): DeviceRecord {
        val (base, code) = parseInvite(link) ?: throw ApiException("That doesn't look like a Chorus invite link.")
        val body = JSONObject()
            .put("code", code)
            .put(
                "device",
                JSONObject().put("name", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                    .put("platform", "android").put("public_key", DeviceKey.create()),
            )
        if (!name.isNullOrBlank()) body.put("account", JSONObject().put("display_name", name.trim()))
        val e = post(base, "/auth/redeem", body)
        return DeviceRecord(
            base, e.getString("device_id"), e.getString("short_id"), e.getString("account_id"),
            e.getString("session"), e.getLong("expires_at"),
        )
    }

    /** A fresh session by signing the server's challenge with the device key. */
    suspend fun renew(dev: DeviceRecord): DeviceRecord {
        val c = post(dev.base, "/auth/challenge", JSONObject().put("device_id", dev.deviceId))
        val nonce = c.getString("nonce")
        val msg = "chorus-auth\n$nonce\n${dev.deviceId}\n${c.getString("instance_id")}".toByteArray()
        val s = post(
            dev.base, "/auth/session",
            JSONObject().put("device_id", dev.deviceId).put("nonce", nonce).put("signature", DeviceKey.sign(msg)),
        )
        return dev.copy(session = s.getString("session"), expiresAt = s.getLong("expires_at"))
    }
}
