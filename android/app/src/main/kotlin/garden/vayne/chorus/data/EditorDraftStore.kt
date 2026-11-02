package garden.vayne.chorus.data

import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

/** Local editor checkpoints use the existing encrypted replica KV, never an evictable cache or
 * an activity Bundle. A single writer coalesces autosaves; a lifecycle checkpoint joins a write
 * of the exact latest accepted value, after earlier writes. SQLite supplies crash atomicity.
 * Keys include server/account/purpose/session; a close deletes only that exact own draft.
 */
class EditorDraftStore(private val read: (String) -> String?, private val write: (String, String) -> Unit,
    private val remove: (String) -> Unit) {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "chorus-editor-drafts").apply { isDaemon = true } }
    private data class Checkpoint(val revision: Long, val encode: () -> String, val result: (Throwable?) -> Unit = {})
    private val sequence = AtomicLong()
    private val written = mutableMapOf<String, Long>() // worker only
    private val pending = mutableMapOf<String, Checkpoint>()
    private val scheduled = mutableSetOf<String>()
    private val closed = mutableSetOf<String>()
    private val failures = mutableMapOf<String, Throwable>()
    private val leases = mutableMapOf<String, Long>()
    /** Stable composer keys can be reopened by a new Activity before the old one stops. Its
     * lease rejects future old writes/completions. Already accepted writes drain BEFORE the
     * new owner's load, so transferring an editor never drops its last pending keystrokes. */
    fun claim(key: String): Long = synchronized(pending) {
        sequence.incrementAndGet().also { leases[key] = it }
    }
    fun owns(key: String, lease: Long): Boolean = synchronized(pending) { leases[key] == lease }
    fun key(owner: String, purpose: String, reference: String): String {
        require(owner.isNotBlank() && purpose.isNotBlank() && java.util.UUID.fromString(reference).toString() == reference)
        // Length-prefix the scope, so separators in a URL cannot alias another account.
        return "editor-draft-v1:${owner.length}:$owner:${purpose.length}:$purpose:$reference"
    }
    fun load(key: String): Future<String?> = worker.submit<String?> { read(key) }
    fun autosave(key: String, result: (Throwable?) -> Unit = {}, lease: Long? = null, encode: () -> String) {
        synchronized(pending) {
            if (key in closed || lease != null && leases[key] != lease) return
            pending[key] = Checkpoint(sequence.incrementAndGet(), encode, result)
            if (!scheduled.add(key)) return
            // Submission is part of acceptance: claim+load cannot slip ahead of this write.
            worker.execute {
                while (true) {
                    val snapshot = synchronized(pending) {
                        pending.remove(key).takeIf { key !in closed }.also { if (it == null) scheduled.remove(key) }
                    } ?: break
                    try { persist(key, snapshot); synchronized(pending) { failures.remove(key) }; snapshot.result(null) }
                    catch (e: Exception) { synchronized(pending) { failures[key] = e }; snapshot.result(e) }
                }
            }
        }
    }
    /** Call with an immutable captured value, not a closure reading mutable Compose state. */
    fun checkpoint(key: String, encode: () -> String) {
        checkpointAsync(key, encode = encode).get()
    }
    internal fun checkpointAsync(key: String, lease: Long? = null, encode: () -> String): Future<*> = synchronized(pending) {
        if (lease != null && leases[key] != lease) return@synchronized worker.submit { }
        val checkpoint = Checkpoint(sequence.incrementAndGet(), encode)
        worker.submit {
            check(synchronized(pending) { key !in closed }) { "This draft is closed." }
            persist(key, checkpoint)
            synchronized(pending) { failures.remove(key) }
        }
    }
    private fun persist(key: String, checkpoint: Checkpoint) {
        if ((written[key] ?: -1L) >= checkpoint.revision) return
        write(key, checkpoint.encode())
        written[key] = checkpoint.revision
    }
    fun discard(key: String) {
        synchronized(pending) { closed.add(key); pending.remove(key) }
        try { worker.submit { remove(key) }.get() }
        catch (e: Exception) { synchronized(pending) { closed.remove(key) }; throw e }
    }
    internal fun failure(key: String): Throwable? = synchronized(pending) { failures[key] }
}
