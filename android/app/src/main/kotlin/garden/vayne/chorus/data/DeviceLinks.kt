package garden.vayne.chorus.data

import org.json.JSONObject

/** A one-use link for another device (`POST /devices/invite`) and its QR code. */
data class DeviceInvite(val url: String, val qr: DeviceLinks.Qr?)

/** One of this account's devices (`GET /me`). */
data class LinkedDevice(val id: String, val name: String, val platform: String, val lastSeen: Long?, val current: Boolean)

/**
 * F1.6 Link a device: the server's QR (`qr_svg`, one rectangle per run of dark modules, `qr.rs`)
 * drawn as modules, and the account's devices, this one first.
 */
object DeviceLinks {
    /** `POST /devices/{id}/revoke`'s body: `{"wipe": true}` to erase that device's copy at once (D-152). */
    fun revokeBody(wipe: Boolean): JSONObject = JSONObject().apply { if (wipe) put("wipe", true) }

    /** What signing a device out does to what it holds (D-152), under the "Erase now" switch. */
    fun revokeText(wipe: Boolean): String =
        if (wipe) "Its copy is erased the next time it connects, including anything it never synced."
        else "It keeps its copy, hidden, for two days, then erases it, unless it's linked to this account again before then."

    /** A [size]-module square (quiet zone included); each run is `x, y, width` in modules. */
    data class Qr(val size: Int, val runs: List<Triple<Int, Int, Int>>)

    private val VIEWBOX = Regex("""viewBox="0 0 (\d+) (\d+)"""")
    private val PATH = Regex("""<path d="([^"]*)"""")
    private val RUN = Regex("""M(\d+),(\d+)h(\d+)v1h-\d+z""")

    fun parseQr(svg: String?): Qr? {
        if (svg.isNullOrEmpty()) return null
        val size = VIEWBOX.find(svg)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val path = PATH.find(svg)?.groupValues?.get(1) ?: return null
        val runs = RUN.findAll(path).map { m -> Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.toList()
        return Qr(size, runs).takeIf { runs.isNotEmpty() && size in 21..200 }
    }

    fun invite(json: JSONObject): DeviceInvite =
        DeviceInvite(json.getString("url"), parseQr(json.optString("qr_svg").takeIf { !json.isNull("qr_svg") }))

    /** This device first, then the others by when they were linked. */
    fun devices(me: JSONObject, thisDevice: String?): List<LinkedDevice> {
        val rows = me.optJSONArray("devices") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.map { r ->
            LinkedDevice(r.getString("id"), r.optString("name").ifBlank { r.optString("platform") }, r.optString("platform"),
                r.optLong("last_seen_at").takeIf { !r.isNull("last_seen_at") && it > 0 }, r.getString("id") == thisDevice)
        }.sortedByDescending { it.current }
    }

    /** What a new device calls itself: the phone's model, as Android names it ("SM-A566B" → as is). */
    fun defaultName(manufacturer: String?, model: String?): String {
        val m = model.orEmpty().trim()
        val brand = manufacturer.orEmpty().trim()
        return when {
            m.isEmpty() -> brand.replaceFirstChar { it.uppercase() }.ifEmpty { "Android" }
            brand.isEmpty() || m.startsWith(brand, ignoreCase = true) -> m
            else -> "${brand.replaceFirstChar { it.uppercase() }} $m"
        }
    }
}
