package garden.vayne.chorus.ui

import android.util.Log
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.EditorDraftStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** Only the random reference is saved by Compose, including when its destination is off screen.
 * Serialization and database work run on the draft worker. Save-state joins the final checkpoint
 * before Android can kill the background process; it cannot race a delayed snapshotFlow autosave.
 */
internal class DurableEditor<T>(val reference: String, val storage: EditorDraftStore, val key: String,
    private val encode: (T) -> String, val restoring: Boolean = false) {
    var value by mutableStateOf<T?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var loadAttempt by mutableStateOf(0)
        private set
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    fun retry() { error = null; loadAttempt++ }
    var closed = false
        private set
    val captures = mutableMapOf<Any, () -> Unit>()
    fun change(next: T) {
        if (closed) return
        value = next
        storage.autosave(key, { failure -> main.post {
            if (!closed) error = if (failure == null) null else "Could not save this draft on this phone. Keep editing and try again."
        } }) { encode(next) }
    }
    fun checkpoint() {
        val started = System.nanoTime()
        captures.values.toList().forEach { it() }
        val snapshot = value ?: return
        if (!closed) storage.checkpoint(key) { encode(snapshot) }
        garden.vayne.chorus.recordEditorWork("checkpoint", started, 0)
    }
    fun finish() {
        storage.discard(key)
        closed = true
    }
}

internal fun <T, V> DurableEditor<T>.lens(get: (T) -> V, set: (T, V) -> T): MutableState<V> = object : MutableState<V> {
    override var value: V
        get() = get(requireNotNull(this@lens.value))
        set(value) { change(set(requireNotNull(this@lens.value), value)) }
    override fun component1() = value
    override fun component2(): (V) -> Unit = { value = it }
}

@Composable
internal fun <T> rememberDurableEditor(chorus: Chorus, purpose: String, encode: (T) -> String,
    decode: (String) -> T, initial: () -> T): DurableEditor<T> {
    val owner = chorus.editorOwner
    val storage = chorus.editorDrafts
    fun holder(ref: String, restoring: Boolean) = DurableEditor(ref, storage, storage.key(owner, purpose, ref), encode, restoring)
    val saver = remember(owner, purpose) { Saver<DurableEditor<T>, String>(save = {
        if (it.value == null || it.closed) null else {
            try { it.checkpoint() }
            catch (e: Exception) {
                it.error = "Could not save this draft on this phone. Keep editing and try again."
                Log.e("ChorusDraft", "Editor checkpoint failed", e)
            }
            it.reference
        }
    }, restore = { holder(it, true) }) }
    val editor = rememberSaveable(owner, purpose, saver = saver) { holder(UUID.randomUUID().toString(), false) }
    LaunchedEffect(editor, editor.loadAttempt) {
        try {
            val value = withContext(Dispatchers.IO) {
                val saved = storage.load(editor.key).get()
                if (saved != null) decode(saved) else {
                    check(!editor.restoring) { "This draft has already been closed or is unavailable." }
                    initial().also { first -> storage.checkpoint(editor.key) { encode(first) } }
                }
            }
            editor.change(value)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { editor.error = e.cause?.message ?: e.message ?: "Could not open this draft." }
    }
    return editor
}

@Composable
internal fun DraftLoading(error: String?, onRetry: (() -> Unit)? = null, onClose: () -> Unit) {
    Column {
        Text(error ?: "Opening draft…")
        if (error != null && onRetry != null) TextButton(onClick = onRetry) { Text("Try again") }
        TextButton(onClick = onClose) { Text("Back") }
    }
}
