package garden.vayne.chorus.data

import org.json.JSONObject

/** Account settings are independent pref rows; pending rows use an empty account id (D-063). */
data class AccountPrefs(val notifyChat: JSONObject = JSONObject(), val followCeiling: JSONObject = JSONObject(),
    val cwAutoExpand: Boolean = false, val segmentParsing: Boolean = true, val readPerMember: Boolean = false, val shareTyping: Boolean = false,
    /** channel id → `notify_channel:<id>` (all · mentions · none, NOTIFICATIONS §7). */
    val notifyChannels: Map<String, String> = emptyMap(),
    /** Saved journal posts: `bookmark:<post id>` = true. Private to this account's devices. */
    val bookmarks: Set<String> = emptySet(),
    /** Members who are subsystems: `member_subsystem:<member id>` = the subsystem group's id (D-092). */
    val memberSubsystems: Map<String, String> = emptyMap(),
    /** Pinned chats in their arranged order (`chat_pins`, D-097). */
    val chatPins: List<String> = emptyList(),
    /** Members' private chats only while one of them fronts (`chat.hide_private`, on by default). */
    val hidePrivate: Boolean = true,
    /** Chats cleared for this account: channel id → when (`chat_hidden:<id>`). */
    val hiddenChats: Map<String, Long> = emptyMap()) {
    fun isSaved(postId: String): Boolean = postId in bookmarks

    /** A channel's chat notifications; DMs default to everything, other channels to mentions (as on web). */
    fun channelNotify(channelId: String, spaceKind: String): String =
        notifyChannels[channelId] ?: defaultNotify(spaceKind)

    fun defaultNotify(spaceKind: String): String = if (spaceKind == "dm") "all" else "mentions"

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
                value("chat.cw_auto_expand") == true, value("chat.segment_parsing") != false,
                value("chat.read_per_member") == true, value("share_typing") == true, channelLevels(prefs, accountId),
                savedPosts(prefs, accountId), keyed(prefs, accountId, MEMBER_SUBSYSTEM).mapNotNull { (member, value) ->
                    (value as? String)?.takeIf { it.isNotBlank() }?.let { member to it } }.toMap(),
                (value(ChatOrganize.PINS) as? org.json.JSONArray)?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) } }.orEmpty(),
                value(ChatOrganize.HIDE_PRIVATE) != false,
                keyed(prefs, accountId, ChatOrganize.HIDDEN).mapNotNull { (channel, at) -> (at as? Number)?.let { channel to it.toLong() } }.toMap())
        }

        /** Account-wide (no device) prefs under [prefix], by the rest of the key; a pending local
         * change wins over the stamped row, so a cleared (null) value hides an older one. */
        private fun keyed(prefs: JSONObject?, accountId: String, prefix: String): Map<String, Any?> {
            val pending = HashMap<String, Any?>()
            val stamped = HashMap<String, Any?>()
            for (k in prefs?.keys()?.asSequence().orEmpty()) {
                val parts = k.split('|', limit = 3)
                if (parts.size != 3 || parts[1].isNotEmpty() || (parts[0].isNotEmpty() && parts[0] != accountId)) continue
                val id = parts[2].takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.takeIf { it.isNotBlank() } ?: continue
                val row = prefs?.optJSONObject(k)?.takeIf { it.optBoolean("exists") } ?: continue
                (if (parts[0].isEmpty()) pending else stamped)[id] = row.optJSONObject("fields")?.opt("value")?.takeIf { it != JSONObject.NULL }
            }
            return stamped + pending
        }

        const val MEMBER_SUBSYSTEM = "member_subsystem:"

        /** Account-wide (no device) `bookmark:` prefs set to true; a pending local change wins, so
         * saving then un-saving before the op is stamped never resurrects the stamped value. */
        private fun savedPosts(prefs: JSONObject?, accountId: String): Set<String> {
            val pending = HashMap<String, Boolean>()
            val stamped = HashMap<String, Boolean>()
            for (k in prefs?.keys()?.asSequence().orEmpty()) {
                val parts = k.split('|', limit = 3)
                if (parts.size != 3 || parts[1].isNotEmpty() || (parts[0].isNotEmpty() && parts[0] != accountId)) continue
                val post = parts[2].takeIf { it.startsWith(BOOKMARK) }?.removePrefix(BOOKMARK)?.takeIf { it.isNotBlank() } ?: continue
                val row = prefs?.optJSONObject(k)?.takeIf { it.optBoolean("exists") } ?: continue
                (if (parts[0].isEmpty()) pending else stamped)[post] = row.optJSONObject("fields")?.opt("value") == true
            }
            return (stamped + pending).filterValues { it }.keys
        }

        const val BOOKMARK = "bookmark:"

        /** Saving writes `true`; un-saving writes null (D-063), so the row is just cleared. */
        fun bookmarkPayload(postId: String, saved: Boolean): JSONObject =
            payload(BOOKMARK + postId, if (saved) true else JSONObject.NULL)

        /** Account-wide (no device) `notify_channel:` prefs; a pending local change wins. */
        private fun channelLevels(prefs: JSONObject?, accountId: String): Map<String, String> {
            val out = HashMap<String, String>()
            for (k in prefs?.keys()?.asSequence().orEmpty()) {
                val parts = k.split('|', limit = 3)
                if (parts.size != 3 || parts[1].isNotEmpty() || (parts[0].isNotEmpty() && parts[0] != accountId)) continue
                val channel = parts[2].takeIf { it.startsWith("notify_channel:") }?.removePrefix("notify_channel:") ?: continue
                val row = prefs?.optJSONObject(k)?.takeIf { it.optBoolean("exists") } ?: continue
                val level = row.optJSONObject("fields")?.opt("value") as? String ?: continue
                if (parts[0].isEmpty() || channel !in out) out[channel] = level
            }
            return out
        }

        fun payload(key: String, value: Any): JSONObject = JSONObject().put("device", "")
            .put("key", key).put("value", value)
    }
}
