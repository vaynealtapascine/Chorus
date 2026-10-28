package garden.vayne.chorus.ui

import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.foundation.lazy.LazyListState

/** Navigation state only. Picked URIs retain the existing grant/staging rules: bytes are copied
 * by the normal attachment path on Send. Nothing here creates a post, upload or typing frame. */
internal val AttachmentDraftSaver = listSaver<SnapshotStateList<PendingAttachment>, String>(
    save = { rows -> rows.map { it.encode() } },
    restore = { rows -> rows.map { decodeAttachment(it) }.toMutableStateList() })

private fun PendingAttachment.encode(): String = JSONObject().put("uri", uri.toString()).put("name", name)
    .put("mime", mime).put("alt", alt).put("spoiler", spoiler).toString()
private fun decodeAttachment(encoded: String): PendingAttachment = JSONObject(encoded).let {
    PendingAttachment(Uri.parse(it.getString("uri")), it.getString("name"), it.getString("mime"), it.getString("alt"), it.getBoolean("spoiler"))
}

internal val PollOptionsSaver = listSaver<SnapshotStateList<String>, String>(
    save = { it.toList() }, restore = { it.toMutableStateList() })

internal class ChatDraft {
    val text = mutableStateOf("")
    val cw = mutableStateOf("")
    val author = mutableStateOf("")
    val audience = mutableStateOf("all")
    val visibleTo = mutableStateOf<List<String>>(emptyList())
    val replyTo = mutableStateOf<String?>(null)
    val poll = mutableStateOf(false)
    val multiple = mutableStateOf(false)
    val options = mutableStateListOf("", "")
    val files = mutableStateListOf<PendingAttachment>()
    fun encode(): String = JSONObject().put("text", text.value).put("cw", cw.value).put("author", author.value)
        .put("audience", audience.value).put("visible", JSONArray(visibleTo.value)).put("reply", replyTo.value)
        .put("poll", poll.value).put("multiple", multiple.value).put("options", JSONArray(options))
        .put("files", JSONArray(files.map { it.encode() })).toString()
    companion object {
        fun decode(encoded: String): ChatDraft = ChatDraft().apply {
            val j = JSONObject(encoded)
            fun strings(key: String) = j.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) } }
            text.value = j.getString("text"); cw.value = j.getString("cw"); author.value = j.getString("author")
            audience.value = j.getString("audience"); visibleTo.value = strings("visible")
            replyTo.value = if (j.isNull("reply")) null else j.getString("reply")
            poll.value = j.getBoolean("poll"); multiple.value = j.getBoolean("multiple")
            options.clear(); options.addAll(strings("options"))
            files.addAll(strings("files").map(::decodeAttachment))
        }
    }
}

internal val ChatDraftsSaver = Saver<SnapshotStateMap<String, ChatDraft>, Map<String, String>>(
    save = { drafts -> drafts.mapValues { it.value.encode() } },
    restore = { saved -> saved.mapValues { ChatDraft.decode(it.value) }.toList().toMutableStateMap() })

internal class ChatReadingPosition(val list: LazyListState, var newest: String?)
internal val ChatReadPositionsSaver = Saver<SnapshotStateMap<String, ChatReadingPosition>, Map<String, String>>(
    save = { positions -> positions.mapValues { (_, position) ->
        JSONObject().put("index", position.list.firstVisibleItemIndex).put("offset", position.list.firstVisibleItemScrollOffset)
            .put("newest", position.newest).toString()
    } },
    restore = { positions -> positions.mapValues { (_, encoded) -> JSONObject(encoded).let {
        ChatReadingPosition(LazyListState(it.getInt("index"), it.getInt("offset")), it.optString("newest").takeIf(String::isNotBlank))
    } }.toList().toMutableStateMap() })
