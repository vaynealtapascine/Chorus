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
        // Chorus Home: certificates pinned by an invite are trusted; everything else as usual (Pins.kt)
        .sslSocketFactory(
            javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(Pins.trustManager), null) }.socketFactory,
            Pins.trustManager,
        )
        .hostnameVerifier(Pins.hostnameVerifier)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json".toMediaType()

    suspend fun post(base: String, path: String, body: JSONObject, bearer: String? = null): JSONObject =
        call("POST", base, path, body, bearer)

    suspend fun call(method: String, base: String, path: String, body: JSONObject?, bearer: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val payload = body?.toString()?.toRequestBody(JSON)
                ?: if (method == "POST" || method == "PUT") "".toRequestBody(null) else null
            val req = Request.Builder().url("$base/api/v1$path").method(method, payload)
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

    /**
     * Invite links look like `https://host/i/<code>`, or on a Chorus Home server
     * `chorus://<lan ip>:<port>/i/<code>#pin=sha256/<b64url>` (https underneath, D-071).
     */
    fun parseInvite(link: String): Invite? {
        val m = Regex("""^(https?|chorus)://([^/\s]+)/i/([A-Za-z0-9_-]+)(?:[^#\s]*)(?:#pin=(sha256/[A-Za-z0-9_-]+))?""")
            .find(link.trim()) ?: return null
        val scheme = if (m.groupValues[1] == "chorus") "https" else m.groupValues[1]
        return Invite("$scheme://${m.groupValues[2]}", m.groupValues[3], m.groupValues[4].ifEmpty { null })
    }

    /**
     * What an invite is for, without using it (D-093): `system` or `person` for a new account (the
     * invite's suggestion; the person joining picks), `device` for another device of an account.
     * Null when the server predates the question, so onboarding asks nothing.
     */
    suspend fun inviteKind(link: String): String? {
        val (base, code, pin) = parseInvite(link) ?: throw ApiException("That doesn't look like a Chorus invite link.")
        Pins.trust(pin)
        return try {
            post(base, "/auth/invite", JSONObject().put("code", code)).optString("kind").takeIf { it in setOf("system", "person", "device") }
        } catch (e: ApiException) {
            if (e.code == "bad_request") throw e else null
        }
    }

    /** Redeem an invite with a fresh device key. The account details are only used for new
     * accounts; [kind] is `system` or `person` (D-093). */
    suspend fun enrol(link: String, name: String?, handle: String? = null, kind: String? = null): DeviceRecord {
        val (base, code, pin) = parseInvite(link) ?: throw ApiException("That doesn't look like a Chorus invite link.")
        Pins.trust(pin)
        val body = JSONObject()
            .put("code", code)
            .put(
                "device",
                JSONObject().put("name", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                    .put("platform", "android").put("public_key", DeviceKey.create()),
            )
        if (!name.isNullOrBlank() || !handle.isNullOrBlank() || kind != null) body.put("account", JSONObject().apply {
            if (!name.isNullOrBlank()) put("display_name", name.trim())
            if (!handle.isNullOrBlank()) put("handle", handle.trim().removePrefix("@").lowercase())
            if (kind != null) put("kind", kind)
        })
        val e = post(base, "/auth/redeem", body)
        return DeviceRecord(
            base, e.getString("device_id"), e.getString("short_id"), e.getString("account_id"),
            e.getString("session"), e.getLong("expires_at"), pin,
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
