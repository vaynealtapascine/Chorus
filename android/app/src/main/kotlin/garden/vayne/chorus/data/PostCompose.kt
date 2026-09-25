package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import uniffi.chorus_ffi.parseMarkup

/** Build the same post.create shape as the web composer, with rich text parsed by chorus_core. */
object PostCompose {
    fun payload(
        model: Model, accountId: String, kind: String, authorId: String, body: String,
        title: String, cw: String, audience: String, mood: String, tags: String,
        replyTo: String? = null,
        markup: (String, String) -> String = ::parseMarkup,
        attachmentIds: List<String> = emptyList(),
    ): JSONObject {
        require(kind in setOf("note", "entry")) { "Choose a post type." }
        require(audience in setOf("private", "followers", "server")) { "Choose an audience." }
        require(body.isNotBlank() || attachmentIds.isNotEmpty()) { "Write a post or attach a file first." }
        require(model.active.any { it.id == authorId && (it.createdByAccountId == null || it.createdByAccountId == accountId) }) {
            "Choose one of your members to write as."
        }
        val rich = JSONObject(markup(body, ""))
        require(rich.getString("text").isNotBlank() || attachmentIds.isNotEmpty()) { "Write a post or attach a file first." }
        val result = JSONObject().put("kind", kind).put("authors", JSONArray().put(authorId))
            .put("title", title.trim().takeIf { kind == "entry" && it.isNotBlank() } ?: JSONObject.NULL)
            .put("text", rich.getString("text")).put("entities", rich.getJSONArray("entities"))
            .put("mood", mood.trim().ifBlank { null } ?: JSONObject.NULL)
            .put("tags", JSONArray(tags.split(',').map { it.trim().removePrefix("#") }.filter { it.isNotBlank() }))
            .put("cw", cw.trim().ifBlank { null } ?: JSONObject.NULL)
            .put("visibility", JSONObject().put("mode", audience))
        if (replyTo != null) result.put("reply_to", replyTo)
        if (attachmentIds.isNotEmpty()) result.put("attachments", JSONArray(attachmentIds))
        return result
    }
}
