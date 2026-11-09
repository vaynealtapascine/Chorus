package garden.vayne.chorus.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class JournalDraftState(val draft: JournalDraft, val loaded: Boolean = false,
    val busy: Boolean = false, val error: String? = null)

/** The actual Journal editor lifecycle. All UI edits/launches/send completions go through this
 * instance. EditorDraftStore orders disk IO; identity+revision guards order async UI results.
 * Detaching an editor closes its write lease before another editor may load the stable key. */
class JournalDraftSession(private val storage: EditorDraftStore, val owner: String,
    private val currentOwner: () -> String, private val newId: () -> String,
    private val committed: suspend (String) -> Boolean) {
    private val key = storage.key(owner, "journal-composer", REFERENCE)
    private val lease = storage.claim(key)
    private val mutable = MutableStateFlow(JournalDraftState(JournalDraft(newId())))
    val state: StateFlow<JournalDraftState> = mutable
    /** Drafts kept for later (F6.2 "Drafts"), newest first; the composer holds one at a time. */
    private val shelfKey = storage.key(owner, "journal-shelf", SHELF)
    private val shelfLease = storage.claim(shelfKey)
    private val shelved = MutableStateFlow<List<JournalDraft>>(emptyList())
    val shelf: StateFlow<List<JournalDraft>> = shelved
    private val loadLock = Mutex()
    @Volatile private var attached = true

    suspend fun restore() = loadLock.withLock {
        if (state.value.loaded || !attached) return@withLock
        try {
            val raw = withContext(Dispatchers.IO) { storage.load(key).get() }
            var draft = raw?.let { JournalDraft.decode(it, newId) } ?: state.value.draft
            val kept = withContext(Dispatchers.IO) { storage.load(shelfKey).get() }
            shelved.value = decodeShelf(kept)
            val post = draft.sendingPost
            if (post != null) draft = if (committed(post)) JournalDraft(newId(), launches = draft.launches, receipts = draft.receipts)
                else draft.copy(sendingPost = null)
            synchronized(this) {
                if (!attached || owner != currentOwner() || !storage.owns(key, lease)) return@withLock
                mutable.value = JournalDraftState(draft, loaded = true)
                persist(draft)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { report("Could not restore this draft. Retry before writing: ${e.message}") }
    }

    @Synchronized fun edit(change: JournalDraft.() -> JournalDraft) {
        val before = state.value
        if (!writable() || before.busy || before.draft.sendingPost != null) return
        accept(before.draft.change().copy(revision = before.draft.revision + 1))
    }

    fun report(message: String?) {
        if (attached) mutable.update { it.copy(error = message) }
    }

    @Synchronized fun enqueue(launch: JournalLaunch) {
        if (!writable() || state.value.draft.launches.any { it.id == launch.id }) return
        val draft = state.value.draft
        // Shares/new-entry shortcuts have unique event IDs. Keep bounded receipt metadata
        // across consumption/send so a retry or Intent redelivery cannot append a share twice.
        // Reply IDs name a post, so they must remain repeatable on a later explicit launch.
        val deduplicate = launch.kind in setOf("share", "entry")
        if (deduplicate && launch.id in draft.receipts) return
        // Queue separately from the generation being sent. A send may only consume its own
        // draft, and must carry all launches accepted during staging into the next draft.
        accept(draft.copy(launches = draft.launches + launch,
            receipts = if (deduplicate) (draft.receipts + launch.id).takeLast(64) else draft.receipts))
    }

    /** External launch owners may acknowledge consumption only after this receipt is durable.
     * Reserve the immutable checkpoint on the calling thread, before suspension. */
    suspend fun receive(launch: JournalLaunch): Boolean {
        val receipt = synchronized(this) {
            if (!writable()) return false
            enqueue(launch)
            val captured = state.value.draft
            storage.checkpointAsync(key, lease) { captured.encode() }
        }
        return try {
            withContext(Dispatchers.IO) { receipt.get() }
            true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { report("The incoming draft could not be saved. Retry saving before closing: ${e.message}"); false }
    }

    @Synchronized fun resolveLaunch(use: Boolean) {
        if (!writable() || state.value.busy || state.value.draft.sendingPost != null) return
        val before = state.value.draft
        val launch = before.launches.firstOrNull() ?: return
        var next = before.copy(launches = before.launches.drop(1))
        if (use) {
            val occupied = before.occupied
            next = when (launch.kind) {
                "share" -> next.copy(body = when {
                    before.body.isEmpty() -> launch.text
                    launch.text.isEmpty() -> before.body
                    else -> "${before.body}\n${launch.text}"
                }, files = before.files + launch.files)
                "entry" -> next.copy(kind = "entry")
                // the account's default is for new posts: a reply starts from what was chosen, or Only us
                "reply" -> next.copy(reply = launch.post, quote = null,
                    audience = launch.audience ?: if (before.audienceChosen) before.audience else "private",
                    buckets = if (before.audienceChosen) before.buckets else emptyList())
                "quote" -> next.copy(quote = launch.post, reply = null)
                else -> next
            }
            // Explicit launch author intent only initializes an empty editor. Existing text
            // never silently acquires the member of a newly opened profile.
            next = next.copy(editing = true, authors = if (!occupied && launch.author != null) listOf(launch.author) else next.authors)
        }
        accept(next.copy(revision = before.revision + 1))
    }

    /** UI lifecycle checkpoints join the exact last accepted immutable value. No write occurs
     * before restore. IO failures remain visible and do not turn an unread draft into empty. */
    @Synchronized fun checkpoint() {
        if (!attached || !state.value.loaded || !storage.owns(key, lease)) return
        try { val snapshot = state.value.draft; storage.checkpointAsync(key, lease) { snapshot.encode() }.get() }
        catch (e: Exception) { report("Draft could not be saved on this phone: ${e.message}") }
    }

    @Synchronized fun detach() { checkpoint(); attached = false }

    @Synchronized fun keepRecoveredCopy() {
        if (!writable() || state.value.busy) return
        val draft = state.value.draft
        val raw = draft.recovery ?: return
        // Keep the original even after a successful send consumes the salvaged composer.
        storage.checkpoint(storage.key(owner, "journal-recovery", draft.id)) { raw }
        edit { copy(recovery = null) }
    }

    /** Validate before any file staging AND after it. The durable chosen id precedes create;
     * a serial persisted-op query resolves cancellation/failure after SQLite commit. */
    suspend fun send(prepare: (JournalDraft) -> JournalDraft = { it }, validate: suspend (JournalDraft) -> Unit, stage: suspend (JournalDraft) -> List<String>,
        create: suspend (JournalDraft, String, List<String>) -> Unit): String? {
        var captured = synchronized(this) {
            if (!writable() || state.value.busy) return null
            mutable.value = state.value.copy(busy = true, error = null)
            state.value.draft
        }
        var post = captured.sendingPost
        var sent: String? = null
        try {
            if (post != null) {
                if (committed(post)) { sent = post; return post }
                synchronized(this) { if (matches(captured)) accept(state.value.draft.copy(sendingPost = null)) }
            }
            checkCurrent(captured)
            captured = prepare(captured)
            checkCurrent(captured)
            require(captured.recovery == null) { "Review the recovered draft details before posting." }
            validate(captured)
            checkCurrent(captured)
            val files = stage(captured)
            checkCurrent(captured)
            validate(captured)
            post = newId()
            val markerWrite = synchronized(this) {
                checkCurrent(captured)
                val marked = state.value.draft.copy(sendingPost = post)
                accept(marked)
                // Reserve the write revision BEFORE suspending. A launch accepted while IO
                // is delayed must not be overwritten by an older marker checkpoint.
                storage.checkpointAsync(key, lease) { marked.encode() }
            }
            withContext(Dispatchers.IO) { markerWrite.get() }
            checkCurrent(captured)
            create(captured, post, files)
            // Even a normal return is reconciled against disk, rather than optimistic Model.
            check(committed(post)) { "Posting was not saved. Your draft is kept." }
            sent = post
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { report(e.message ?: "Could not post. Your draft is kept.") }
        finally {
            withContext(NonCancellable) {
                try {
                    if (post != null && committed(post)) sent = post
                    synchronized(this@JournalDraftSession) {
                        if (attached && storage.owns(key, lease) && matches(captured)) {
                            if (sent != null) {
                                accept(JournalDraft(newId(), launches = state.value.draft.launches, receipts = state.value.draft.receipts))
                                report(null)
                            }
                            else accept(state.value.draft.copy(sendingPost = null))
                        }
                    }
                    // Wait for the final state before the caller handles another launch. If
                    // detached, leave the marker for the next restore to reconcile instead.
                    if (attached) withContext(Dispatchers.IO) { checkpoint() }
                } catch (e: Exception) { report("Could not confirm posting. Reopen this draft before retrying: ${e.message}") }
                finally { synchronized(this@JournalDraftSession) { mutable.value = state.value.copy(busy = false) } }
            }
        }
        return sent
    }

    /** Put the composer's draft on the shelf and start a fresh one. The shelf is written before
     * the composer, so a crash between the two keeps the draft twice, never zero times. */
    @Synchronized fun shelve(): Boolean {
        val before = state.value
        if (!writable() || before.busy || before.draft.sendingPost != null || !before.draft.occupied) return false
        saveShelf(listOf(before.draft.copy(editing = false, launches = emptyList(), receipts = emptyList())) + shelved.value)
        accept(JournalDraft(newId(), revision = before.draft.revision + 1, editing = true,
            launches = before.draft.launches, receipts = before.draft.receipts))
        return true
    }

    /** Open a shelved draft in the composer; what the composer held goes onto the shelf. */
    @Synchronized fun unshelve(id: String) {
        val before = state.value
        if (!writable() || before.busy || before.draft.sendingPost != null) return
        val picked = shelved.value.find { it.id == id } ?: return
        val rest = shelved.value.filter { it.id != id }
        saveShelf(if (before.draft.occupied) listOf(before.draft.copy(editing = false, launches = emptyList(), receipts = emptyList())) + rest else rest)
        accept(picked.copy(revision = before.draft.revision + 1, editing = true,
            launches = before.draft.launches, receipts = before.draft.receipts))
    }

    @Synchronized fun discardShelved(id: String) {
        if (!writable()) return
        saveShelf(shelved.value.filter { it.id != id })
    }

    private fun saveShelf(drafts: List<JournalDraft>) {
        val list = drafts.take(MAX_SHELF)
        shelved.value = list
        val raw = org.json.JSONArray(list.map { it.encode() }).toString()
        storage.checkpointAsync(shelfKey, shelfLease) { raw }.get()
    }

    private fun writable() = attached && state.value.loaded && owner == currentOwner() && storage.owns(key, lease)
    private fun matches(draft: JournalDraft) = state.value.draft.id == draft.id && state.value.draft.revision == draft.revision
    private fun checkCurrent(draft: JournalDraft) {
        check(writable() && matches(draft)) { "The account or draft changed. Reopen the draft in its account." }
    }
    private fun accept(draft: JournalDraft) { mutable.value = state.value.copy(draft = draft); persist(draft) }
    private fun persist(draft: JournalDraft) {
        storage.autosave(key, result = { failure ->
            if (failure != null && storage.owns(key, lease)) report("Draft could not be saved on this phone: ${failure.message}")
        }, lease = lease) { draft.encode() }
    }

    companion object {
        private val REFERENCE = java.util.UUID.nameUUIDFromBytes("journal-composer".toByteArray()).toString()
        private val SHELF = java.util.UUID.nameUUIDFromBytes("journal-shelf".toByteArray()).toString()
        const val MAX_SHELF = 50

        /** A damaged entry is kept as a recovery draft rather than dropped. */
        fun decodeShelf(raw: String?): List<JournalDraft> {
            if (raw == null) return emptyList()
            val list = try { org.json.JSONArray(raw) } catch (_: Exception) {
                return listOf(JournalDraft(java.util.UUID.randomUUID().toString(), body = raw, authors = emptyList(), recovery = raw))
            }
            return List(list.length()) { i -> JournalDraft.decode(list.optString(i), { java.util.UUID.randomUUID().toString() }) }
        }
    }
}
