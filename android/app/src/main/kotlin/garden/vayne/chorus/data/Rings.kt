package garden.vayne.chorus.data

import android.content.Context
import org.json.JSONObject

/**
 * What this phone rings for (F7.13): a choice per kind, kept on this device, and one Android
 * channel per kind so its sound and vibration can be set in the system's settings.
 */
object Rings {
    /** [account]: the kind also has an account-wide switch (`notify_chat`) that stops it on the server. */
    data class Kind(val key: String, val label: String, val channel: String, val urgent: Boolean, val account: Boolean)

    val KINDS = listOf(
        Kind("switch", "Friends’ switches", Push.CHANNEL_SWITCHES, urgent = false, account = false),
        Kind("mention", "Mentions", "mentions", urgent = true, account = true),
        Kind("dm", "Direct messages", "direct_messages", urgent = true, account = true),
        Kind("reply", "Replies", "replies", urgent = true, account = true),
        Kind("post", "New posts you asked for", Push.CHANNEL_POSTS, urgent = false, account = false),
        Kind("poll_nudge", "Poll nudges", "poll_nudges", urgent = false, account = true),
        Kind("own_switch", "Our own switches", "own_switches", urgent = false, account = true),
    )

    /** The kind a push rings as; null for what always rings (a test, a request for alerts, a chat set to all). */
    fun keyOf(p: JSONObject): String? = when (p.optString("t")) {
        "message" -> when (p.optString("kind")) {
            "mention" -> "mention"
            "dm", "member_dm" -> "dm"
            "reply" -> "reply"
            else -> null
        }
        "switch" -> if (p.optString("account_id") == "own") "own_switch" else "switch"
        "own_switch" -> "own_switch"
        "post" -> if (p.optString("kind") == "post_reply") "reply" else "post"
        "poll" -> "poll_nudge"
        else -> null
    }

    /** The channel a push rings on, or null when it has no kind of its own. */
    fun channelOf(p: JSONObject): String? = keyOf(p)?.let { key -> KINDS.first { it.key == key }.channel }

    /** "just now", "2 min ago", "3 h ago", "2 days ago". */
    fun ago(at: Long, now: Long): String {
        val minutes = (now - at) / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 24 * 60 -> "${minutes / 60} h ago"
            else -> "${minutes / (24 * 60)} ${if (minutes < 2 * 24 * 60) "day" else "days"} ago"
        }
    }

    private const val PREFS = "chorus.rings"
    private const val LAST = "last_shown"

    fun isOn(ctx: Context, key: String): Boolean = ctx.getSharedPreferences(PREFS, 0).getBoolean(key, true)

    fun set(ctx: Context, key: String, on: Boolean) { ctx.getSharedPreferences(PREFS, 0).edit().putBoolean(key, on).apply() }

    fun lastShown(ctx: Context): Long? = ctx.getSharedPreferences(PREFS, 0).getLong(LAST, 0).takeIf { it > 0 }

    fun noteShown(ctx: Context, at: Long) { ctx.getSharedPreferences(PREFS, 0).edit().putLong(LAST, at).apply() }
}
