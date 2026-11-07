package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import uniffi.chorus_ffi.parseMarkup

/** Build the same post.create shape as the web composer, with rich text parsed by chorus_core. */
object PostCompose {
    fun validatedAuthors(model: Model, accountId: String, ids: List<String>): List<String> {
        val ordered = ids.distinct()
        val eligible = model.active.filter { it.createdByAccountId == null || it.createdByAccountId == accountId }.map { it.id }.toSet()
        require(ordered.isNotEmpty()) { "Choose who is writing." }
        require(ordered.all { it in eligible }) { "Choose only your active members to write as." }
        return ordered
    }

    fun toggleAuthor(ids: List<String>, id: String): List<String> =
        if (id in ids) ids.filterNot { it == id } else ids + id

    /** Co-authors stay in the order chosen; the first is the main author. */
    fun moveAuthor(ids: List<String>, from: Int, to: Int): List<String> =
        if (from !in ids.indices || to !in ids.indices || from == to) ids
        else ids.toMutableList().apply { add(to, removeAt(from)) }

    fun payload(
        model: Model, accountId: String, kind: String, authorId: String, body: String,
        title: String, cw: String, audience: String, mood: String, tags: String,
        replyTo: String? = null,
        markup: (String, String) -> String = ::parseMarkup,
        attachmentIds: List<String> = emptyList(),
        quotePostId: String? = null,
        bucketIds: List<String> = emptyList(),
        authorIds: List<String> = listOf(authorId),
        /** `delayed` or `none` (D-127); anything else notifies as usual. */
        alerts: String = "",
    ): JSONObject {
        require(kind in setOf("note", "entry")) { "Choose a post type." }
        val selectedAudience = validatedAudience(Audience(audience, bucketIds), model.sharingBuckets.map { it.first }.toSet())
        require(body.isNotBlank() || attachmentIds.isNotEmpty()) { "Write a post or attach a file first." }
        val authors = validatedAuthors(model, accountId, authorIds)
        val rich = JSONObject(markup(body, ""))
        require(rich.getString("text").isNotBlank() || attachmentIds.isNotEmpty()) { "Write a post or attach a file first." }
        val result = JSONObject().put("kind", kind).put("authors", JSONArray(authors))
            .put("title", title.trim().takeIf { kind == "entry" && it.isNotBlank() } ?: JSONObject.NULL)
            .put("text", rich.getString("text")).put("entities", rich.getJSONArray("entities"))
            .put("mood", mood.trim().ifBlank { null } ?: JSONObject.NULL)
            .put("tags", JSONArray(tags.split(',').map { it.trim().removePrefix("#") }.filter { it.isNotBlank() }))
            .put("cw", cw.trim().ifBlank { null } ?: JSONObject.NULL)
            .put("visibility", selectedAudience.toJson())
        if (replyTo != null) result.put("reply_to", replyTo)
        if (quotePostId != null) result.put("quote", JSONObject().put("post_id", quotePostId))
        if (attachmentIds.isNotEmpty()) result.put("attachments", JSONArray(attachmentIds))
        if (alerts in JournalDraft.ALERTS) result.put("alerts", alerts)
        return result
    }

    /** `post.set_visibility`: who can read an existing post; nothing else about it changes. */
    fun visibilityPayload(audience: String): JSONObject {
        require(audience in JournalPresentation.AUDIENCES) { "Choose an audience." }
        return JSONObject().put("visibility", JSONObject().put("mode", audience))
    }

    fun validatedAudience(value: Audience, liveBuckets: Set<String>): Audience {
        require(value.mode in setOf("private", "followers", "buckets", "server")) { "Choose an audience." }
        require(value.mode != "buckets" || (value.buckets.isNotEmpty() && value.buckets.all { it.isNotBlank() && it in liveBuckets })) {
            "Choose existing buckets before sharing."
        }
        return value
    }

    fun visibilityPayload(value: Audience, liveBuckets: Set<String>): JSONObject =
        JSONObject().put("visibility", validatedAudience(value, liveBuckets).toJson())

    /** A repost is a text-less note that points at the original; it stays in the reposter's own
     * system unless a wider audience is chosen. Undoing it deletes (tombstones) this post. */
    fun repostPayload(model: Model, accountId: String, authorId: String, originalId: String,
        audience: String = "private"): JSONObject {
        require(audience in setOf("private", "followers", "server")) { "Choose an audience." }
        require(originalId.isNotBlank()) { "Choose a post to repost." }
        require(model.active.any { it.id == authorId && (it.createdByAccountId == null || it.createdByAccountId == accountId) }) {
            "Choose one of your members to repost as."
        }
        return JSONObject().put("kind", "note").put("authors", JSONArray().put(authorId))
            .put("title", JSONObject.NULL).put("text", "").put("entities", JSONArray())
            .put("mood", JSONObject.NULL).put("tags", JSONArray()).put("cw", JSONObject.NULL)
            .put("visibility", JSONObject().put("mode", audience)).put("repost_of", originalId)
    }
}
