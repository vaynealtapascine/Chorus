package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

data class MessageEditorDraft(val original: ChatMessage, val parts: List<RichEditHistory>) {
    fun encode(): String = JSONObject().put("id", original.id).put("channel", original.channelId)
        .put("account", original.accountId).put("text", original.text).put("entities", JSONArray(original.entities))
        .put("authors", JSONArray(original.authors)).put("segments", JSONArray(original.segments.map {
            JSONObject().put("offset", it.offset).put("length", it.length).put("authors", JSONArray(it.authors))
        })).put("parts", JSONArray(parts.map { it.json() })).toString()
    companion object {
        fun open(original: ChatMessage): MessageEditorDraft = MessageEditorDraft(original,
            ChatEdit.drafts(original).map { state ->
                val j = JSONObject(state); val origins = j.getJSONArray("origins")
                RichEditHistory(j.getString("text"), j.getJSONArray("entities").toString(),
                    RichEditFrame(j.getString("markup"), List(origins.length()) { origins.getInt(it) }))
            })
        fun decode(encoded: String): MessageEditorDraft = JSONObject(encoded).let { j ->
            fun strings(a: JSONArray) = List(a.length()) { a.getString(it) }
            val segments = j.getJSONArray("segments")
            val original = ChatMessage(j.getString("id"), j.getString("channel"), strings(j.getJSONArray("authors")),
                j.getString("text"), 0, null, "all", emptySet(), if (j.isNull("account")) null else j.getString("account"),
                null, emptyList(), entities = j.getJSONArray("entities").toString(),
                segments = List(segments.length()) { i -> segments.getJSONObject(i).let {
                    ChatSegment(it.getInt("offset"), it.getInt("length"), strings(it.getJSONArray("authors")))
                } })
            MessageEditorDraft(original, j.getJSONArray("parts").let { a -> List(a.length()) { RichEditHistory.decode(a.getJSONObject(it)) } })
        }
    }
}
