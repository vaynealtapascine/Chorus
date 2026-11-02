package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import uniffi.chorus_ffi.toMarkup

/** Original rich occurrences survive edits through the same provenance path as chat/bios. */
object PostEditing {
    fun open(post: JournalPost): String {
        val rich = JSONObject().put("text", post.text).put("entities", JSONArray(post.entities))
        return RichTextEdit.open(post.text, post.entities, toMarkup(rich.toString()))
    }
    fun payload(post: JournalPost, state: String, body: String, title: String, cw: String): JSONObject {
        val rich = RichTextEdit.parse(state, body, "")
        require(rich.getString("text").isNotBlank()) { "Write some text before saving." }
        return JSONObject().put("post_id", post.id).put("text", rich.getString("text"))
            .put("entities", rich.getJSONArray("entities"))
            .put("title", title.trim().takeIf { post.kind == "entry" && it.isNotBlank() } ?: JSONObject.NULL)
            .put("cw", cw.trim().ifBlank { null } ?: JSONObject.NULL)
    }
}
