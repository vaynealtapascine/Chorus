package garden.vayne.chorus.data

import org.json.JSONObject

/** Account settings are independent pref rows; pending rows use an empty account id (D-063). */
data class AccountPrefs(val notifyChat: JSONObject = JSONObject(), val followCeiling: JSONObject = JSONObject(),
    val cwAutoExpand: Boolean = false, val segmentParsing: Boolean = true) {
    fun chatEnabled(key: String): Boolean = if (key == "own_switch" || key == "reply_as_mentioned")
        notifyChat.optBoolean(key, false) else notifyChat.optBoolean(key, true)

    fun withChatKind(key: String, enabled: Boolean): JSONObject =
        JSONObject(notifyChat.toString()).put(key, enabled)

    companion object {
        fun fromProjection(p: JSONObject, accountId: String): AccountPrefs {
            val prefs = p.optJSONObject("rows")?.optJSONObject("pref")
            fun value(key: String): Any? {
                val pending = prefs?.optJSONObject("||$key")?.takeIf { it.optBoolean("exists") }
                val stamped = prefs?.optJSONObject("$accountId||$key")?.takeIf { it.optBoolean("exists") }
                return (pending ?: stamped)?.optJSONObject("fields")?.opt("value")
            }
            val legacy = p.optJSONObject("rows")?.optJSONObject("account")?.optJSONObject(accountId)
                ?.optJSONObject("fields")?.optJSONObject("settings")
            return AccountPrefs(value("notify_chat") as? JSONObject ?: JSONObject(),
                value("follow_ceiling") as? JSONObject ?: legacy?.optJSONObject("follow_ceiling") ?: JSONObject(),
                value("chat.cw_auto_expand") == true, value("chat.segment_parsing") != false)
        }

        fun payload(key: String, value: Any): JSONObject = JSONObject().put("device", "")
            .put("key", key).put("value", value)
    }
}
