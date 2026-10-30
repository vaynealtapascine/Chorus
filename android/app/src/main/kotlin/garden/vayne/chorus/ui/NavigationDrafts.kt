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
    fun snapshot() = ChatDraftSnapshot(text.value, cw.value, author.value, audience.value, visibleTo.value.toList(), replyTo.value,
        poll.value, multiple.value, options.toList(), files.toList())
    fun encode(): String = snapshot().encode()
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

/** Captures immutable values on the UI thread without serializing large text there. */
internal data class ChatDraftSnapshot(val text: String, val cw: String, val author: String, val audience: String,
    val visibleTo: List<String>, val replyTo: String?, val poll: Boolean, val multiple: Boolean,
    val options: List<String>, val files: List<PendingAttachment>) {
    fun encode(): String = JSONObject().put("text", text).put("cw", cw).put("author", author)
        .put("audience", audience).put("visible", JSONArray(visibleTo)).put("reply", replyTo)
        .put("poll", poll).put("multiple", multiple).put("options", JSONArray(options))
        .put("files", JSONArray(files.map { it.encode() })).toString()
}

/** Composer drafts for every chat, per account. They live for the process here, so leaving and
 * returning to Chat never reloads or blocks, and persist in the encrypted replica under one stable
 * key per account, so a draft survives a restart like in other messengers. */
private object ChatDraftCache {
    val byOwner = mutableMapOf<String, SnapshotStateMap<String, ChatDraft>>()
    val reference: String = java.util.UUID.nameUUIDFromBytes("chat-composers".toByteArray()).toString()
}

@Composable
internal fun rememberDurableChatDrafts(chorus: garden.vayne.chorus.data.Chorus): SnapshotStateMap<String, ChatDraft>? {
    val owner = chorus.editorOwner
    val storage = chorus.editorDrafts
    val key = remember(owner) { if (owner.isBlank()) null else storage.key(owner, "chat-composers", ChatDraftCache.reference) }
    var drafts by remember(owner) { mutableStateOf(ChatDraftCache.byOwner[owner]) }
    val loaded = drafts
    if (loaded == null) {
        LaunchedEffect(owner) {
            val saved = if (key == null) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    storage.load(key).get()?.let { encoded ->
                        val j = JSONObject(encoded)
                        j.keys().asSequence().associateWith { ChatDraft.decode(j.getString(it)) }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { android.util.Log.e("ChorusDraft", "Chat drafts could not be read", e); null }
            }
            drafts = ChatDraftCache.byOwner.getOrPut(owner) { (saved ?: emptyMap()).toList().toMutableStateMap() }
        }
        return null
    }
    if (key != null) LaunchedEffect(key, loaded) {
        // Coalesced on the draft worker; the first emission rewrites what was just read, harmlessly.
        snapshotFlow { loaded.mapValues { it.value.snapshot() } }.collect { snapshot ->
            storage.autosave(key) { JSONObject(snapshot.mapValues { it.value.encode() }).toString() }
        }
    }
    return loaded
}

internal class ChatReadingPosition(val list: LazyListState, var newest: String?)
internal val ChatReadPositionsSaver = Saver<SnapshotStateMap<String, ChatReadingPosition>, Map<String, String>>(
    save = { positions -> positions.mapValues { (_, position) ->
        JSONObject().put("index", position.list.firstVisibleItemIndex).put("offset", position.list.firstVisibleItemScrollOffset)
            .put("newest", position.newest).toString()
    } },
    restore = { positions -> positions.mapValues { (_, encoded) -> JSONObject(encoded).let {
        ChatReadingPosition(LazyListState(it.getInt("index"), it.getInt("offset")), it.optString("newest").takeIf(String::isNotBlank))
    } }.toList().toMutableStateMap() })
