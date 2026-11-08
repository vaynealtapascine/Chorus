package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * F10.4 Sync issues in words: what a refused op was ("Edit to a message in #general"), why it
 * wasn't saved, the text it carried and who wrote it; and the *Fixed* list, ops sent again from
 * here, kept on this device for a week.
 */
object SyncIssues {
    /** One retried issue: its [title] when it was retried, the new op, and when. */
    data class Fixed(val title: String, val opId: String, val at: Long)

    const val KEY = "sync:fixed"
    private const val WEEK = 7 * 86_400_000L
    private const val KEEP = 20

    /** "Edit to a message in #general", "Post “A quiet afternoon”", "Change to Kai". */
    fun title(issue: SyncIssue, model: Model): String {
        val p = issue.payload
        val channelId = p.optString("channel_id").ifEmpty { null }
            ?: issue.entityId?.let { id -> model.searchMessages.firstOrNull { it.id == id }?.channelId }
        val where = channelId?.let { id -> model.channels.firstOrNull { it.id == id }?.let { " in #${it.name}" } }.orEmpty()
        val post = issue.entityId?.let { id -> model.posts.firstOrNull { it.id == id } }
        val postName = (p.optString("title").ifEmpty { null } ?: post?.title?.ifEmpty { null })?.let { "“$it”" }
        val memberName = (p.optString("name").ifEmpty { null } ?: issue.entityId?.let { model.member(it)?.shownName })
        return when (issue.kind) {
            "message.send" -> "Message$where"
            "message.forward" -> "Forwarded message$where"
            "message.edit" -> "Edit to a message$where"
            "message.delete" -> "Deleting a message$where"
            "post.create" -> postName?.let { "Post $it" } ?: "Journal post"
            "post.edit" -> "Edit to ${postName ?: "a post"}"
            "post.delete" -> "Deleting ${postName ?: "a post"}"
            "member.create" -> memberName?.let { "New member $it" } ?: "New member"
            "member.update" -> memberName?.let { "Change to $it" } ?: "Change to a member"
            "switch", "front.switch" -> "Switch"
            else -> {
                val (thing, verb) = issue.kind.split('.', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                val noun = thing.replace('_', ' ')
                when (verb) {
                    "create" -> "New $noun"
                    "delete" -> "Deleting a $noun"
                    else -> "Change to a $noun"
                }
            }
        }
    }

    /** "Not saved · the channel was deleted": the server's reason, as a sentence's tail. */
    fun reason(issue: SyncIssue): String {
        val why = issue.message.trim().trimEnd('.').ifEmpty { issue.code.replace('_', ' ') }
        return "Not saved · ${why.replaceFirstChar { it.lowercase() }}"
    }

    /** The words the person wrote, if the op carried any. */
    fun text(issue: SyncIssue): String = issue.payload.let { p ->
        p.optString("text").ifEmpty { p.optString("body") }
    }

    /** Who wrote it: the acting member, else the first author. */
    fun author(issue: SyncIssue): String? = issue.memberId
        ?: issue.payload.optJSONArray("authors")?.optString(0)?.ifEmpty { null }

    fun parseFixed(json: String?): List<Fixed> = runCatching {
        val rows = JSONArray(json ?: return emptyList())
        (0 until rows.length()).mapNotNull { n ->
            val r = rows.optJSONObject(n) ?: return@mapNotNull null
            Fixed(r.optString("title"), r.optString("op").ifEmpty { return@mapNotNull null }, r.optLong("at"))
        }
    }.getOrDefault(emptyList())

    fun encodeFixed(rows: List<Fixed>): String = JSONArray().apply {
        rows.forEach { put(JSONObject().put("title", it.title).put("op", it.opId).put("at", it.at)) }
    }.toString()

    /** Newest first, a week at most, [KEEP] at most. */
    fun withFixed(rows: List<Fixed>, added: Fixed, now: Long): List<Fixed> =
        (listOf(added) + rows.filter { it.opId != added.opId }).filter { now - it.at < WEEK }.take(KEEP)

    /** What's still worth showing: a retry refused again is an issue again, not fixed. */
    fun shownFixed(rows: List<Fixed>, now: Long, status: (String) -> String): List<Pair<Fixed, Boolean>> =
        rows.filter { now - it.at < WEEK }.mapNotNull { f ->
            // the op is written before it's listed here, so one the replica no longer holds was saved
            when (status(f.opId)) {
                "pending" -> f to false
                "refused" -> null
                else -> f to true
            }
        }
}
