package garden.vayne.chorus.data

import org.json.JSONObject

/** What a Trash row restores (F9.5); [op] is the restore op, the same names as the web's. */
enum class TrashKind(val op: String) {
    Message("message.restore"), Post("post.restore"), Member("member.restore"), Group("group.restore"), Channel("channel.restore"),
}

/**
 * One deleted thing this account may restore (D-053): [title] is the row's line ("Message in #vent",
 * "Post “Rough night”"), [deletedBy] the acting member's name when the delete named one (projection v6).
 */
data class TrashItem(val id: String, val kind: TrashKind, val title: String, val deletedAt: Long, val deletedBy: String?,
    val scope: String, val channelId: String? = null, val photos: Int = 0)

/** Trash rows from the core projection, mirroring web `data.ts` `trashItems`. */
object Trash {
    private const val DAY = 86_400_000L

    fun items(p: JSONObject, accountId: String): List<TrashItem> {
        val rows = p.optJSONObject("rows") ?: return emptyList()
        fun table(name: String): List<Pair<String, JSONObject>> {
            val t = rows.optJSONObject(name) ?: return emptyList()
            return t.keys().asSequence().mapNotNull { id ->
                val r = t.optJSONObject(id) ?: return@mapNotNull null
                if (r.optBoolean("exists")) id to (r.optJSONObject("fields") ?: JSONObject()) else null
            }.toList()
        }
        val channels = table("channel").toMap()
        val attachments = table("attachment").toMap()
        // deleted members keep their names, so "Deleted by" still reads after the deleter is gone
        val names = table("member").associate { (id, f) -> id to (f.str("display_name") ?: f.str("name") ?: "") }
        val out = ArrayList<TrashItem>()
        for ((name, kind) in listOf("message" to TrashKind.Message, "post" to TrashKind.Post, "member" to TrashKind.Member,
            "member_group" to TrashKind.Group, "channel" to TrashKind.Channel)) {
            for ((id, f) in table(name)) {
                if (f.isNull("deleted_at") || !f.has("deleted_at")) continue
                val deleter = f.str("deleted_by_account_id")
                if (f.str("created_by_account_id") != accountId && deleter != accountId && deleter != null) continue
                val channelId = f.str("channel_id")
                val channel = channelId?.let { channels[it] }
                val spaceId = if (kind == TrashKind.Message) channel?.str("space_id") else f.str("space_id")
                val scope = when (kind) {
                    TrashKind.Message, TrashKind.Channel -> spaceId?.let { "space:$it" }.orEmpty()
                    else -> "account:$accountId"
                }
                val photos = if (kind == TrashKind.Message && f.str("text").isNullOrBlank()) photoCount(f, attachments) else 0
                val channelName = "#" + (channel?.str("name") ?: "channel")
                val title = when (kind) {
                    TrashKind.Message -> when (photos) {
                        0 -> "Message in $channelName"
                        1 -> "Photo in $channelName"
                        else -> "$photos photos in $channelName"
                    }
                    TrashKind.Post -> (if (f.str("kind") == "entry") "Entry" else "Post") + quoted(f.str("title") ?: f.str("text"))
                    TrashKind.Member -> "Member " + (f.str("display_name") ?: f.str("name") ?: "")
                    TrashKind.Group -> "Group " + (f.str("name") ?: "")
                    TrashKind.Channel -> "Channel #" + (f.str("name") ?: "channel")
                }.trim()
                out += TrashItem(id, kind, title, f.optLong("deleted_at"),
                    f.str("deleted_by_member_id")?.let { names[it] }?.takeIf { it.isNotBlank() }, scope,
                    if (kind == TrashKind.Message) channelId else null, photos)
            }
        }
        return out.sortedWith(compareByDescending<TrashItem> { it.deletedAt }.thenBy { it.id })
    }

    private val DELETES = TrashKind.entries.map { it.op.replace(".restore", ".delete") }.toSet()

    /** The member a Trash delete is made by: whoever is here (the speaker), else none. */
    fun actingMember(kind: String, model: Model): String? = if (kind in DELETES) Reply.speaker(model)?.id else null

    /** "Deleted by Nairon · 2 days ago", "Deleted 12 days ago · history kept" (F9.5). */
    fun detail(item: TrashItem, now: Long): String {
        val ago = ago(item.deletedAt, now)
        val base = item.deletedBy?.let { "Deleted by $it · $ago" } ?: "Deleted $ago"
        return if (item.kind == TrashKind.Member) "$base · history kept" else base
    }

    /** Whether a row goes under *This week* rather than *Earlier*. */
    fun thisWeek(item: TrashItem, now: Long): Boolean = now - item.deletedAt < 7 * DAY

    /** The tab a row shows under besides All: groups sit with members, channels with messages. */
    fun tab(kind: TrashKind): Int = when (kind) {
        TrashKind.Message, TrashKind.Channel -> 1
        TrashKind.Post -> 2
        TrashKind.Member, TrashKind.Group -> 3
    }

    fun ago(at: Long, now: Long): String {
        val s = ((now - at) / 1000).coerceAtLeast(0)
        val d = s / 86_400
        return when {
            s < 60 -> "just now"
            s < 3600 -> plural(s / 60, "minute")
            s < 86_400 -> plural(s / 3600, "hour")
            d < 14 -> plural(d, "day")
            d < 60 -> plural(d / 7, "week")
            d < 365 -> plural(d / 30, "month")
            else -> plural(d / 365, "year")
        }
    }

    private fun plural(n: Long, unit: String) = if (n == 1L) "1 $unit ago" else "$n ${unit}s ago"

    private fun quoted(text: String?): String {
        val line = text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: return ""
        return " “" + (if (line.length > 40) line.take(39).trimEnd() + "…" else line) + "”"
    }

    private fun photoCount(f: JSONObject, attachments: Map<String, JSONObject>): Int {
        val ids = f.optJSONArray("attachments") ?: return 0
        if (ids.length() == 0) return 0
        for (i in 0 until ids.length()) {
            val mime = attachments[ids.optString(i)]?.str("mime") ?: return 0
            if (!mime.startsWith("image/")) return 0
        }
        return ids.length()
    }

    private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) optString(k).ifEmpty { null } else null
}
