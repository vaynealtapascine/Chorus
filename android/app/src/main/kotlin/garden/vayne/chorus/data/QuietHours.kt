package garden.vayne.chorus.data

import org.json.JSONObject

data class QuietWindow(val from: String, val to: String)

object QuietHours {
    private val CLOCK = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")

    fun read(prefs: JSONObject): QuietWindow? {
        val raw = prefs.optJSONObject("quiet_hours") ?: return null
        val from = raw.optString("from").takeIf { CLOCK.matches(it) } ?: return null
        val to = raw.optString("to").takeIf { CLOCK.matches(it) } ?: return null
        return QuietWindow(from, to)
    }

    fun valid(from: String, to: String): Boolean = CLOCK.matches(from) && CLOCK.matches(to)

    /** Change only quiet hours and current UTC offset; keep every other follow preference. */
    fun updated(previous: JSONObject, window: QuietWindow?, offsetMinutes: Int): JSONObject {
        val next = JSONObject(previous.toString())
        next.put("quiet_hours", window?.let { JSONObject().put("from", it.from).put("to", it.to) } ?: JSONObject.NULL)
        next.put("tz_offset_min", offsetMinutes)
        return next
    }
}
