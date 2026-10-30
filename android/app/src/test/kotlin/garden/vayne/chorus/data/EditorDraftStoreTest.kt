package garden.vayne.chorus.data

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EditorDraftStoreTest {
    private val rows = ConcurrentHashMap<String, String>()
    private fun store() = EditorDraftStore(rows::get, { k, v -> rows[k] = v }, { rows.remove(it) })
    @Test fun accountAndPurposeAndSessionScopeCannotAliasOrDeleteAnotherDraft() {
        val store = store(); val ref = UUID.randomUUID().toString()
        val a = store.key("server|a", "profile", ref); val b = store.key("server|b", "profile", ref)
        val c = store.key("server|a", "chat-composers", ref)
        store.checkpoint(a) { "a" }; store.checkpoint(b) { "b" }; store.checkpoint(c) { "c" }
        store.discard(a)
        store.autosave(a) { "must not resurrect" }
        assertNull(store.load(a).get()); assertEquals("b", store.load(b).get()); assertEquals("c", store.load(c).get())
        assertNotEquals(store.key("x:y", "z", ref), store.key("x", "y:z", ref))
    }
    @Test fun latestLifecycleCheckpointWinsOverPendingAutosavesAndFreshStoreRestoresIt() {
        val store = store(); val key = store.key("a", "profile", UUID.randomUUID().toString())
        repeat(200) { i -> store.autosave(key) { "edit-$i" } }
        store.checkpoint(key) { "latest selection and edit" }
        assertEquals("latest selection and edit", store().load(key).get())
    }
    @Test fun anAutosaveAcceptedAfterCheckpointCannotBeOverwrittenByThatOlderCheckpoint() {
        val blocked = CountDownLatch(1); val release = CountDownLatch(1); var first = true
        val store = EditorDraftStore(rows::get, { k, v ->
            if (first) { first = false; blocked.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
            rows[k] = v
        }, { rows.remove(it) })
        val key = store.key("a", "profile", UUID.randomUUID().toString())
        store.autosave(key) { "one" }; assertTrue(blocked.await(5, TimeUnit.SECONDS))
        try {
            val checkpoint = store.checkpointAsync(key) { "two" }
            store.autosave(key) { "three" }; release.countDown(); checkpoint.get()
            assertEquals("three", store.load(key).get())
        } finally { release.countDown() }
    }
    @Test fun failureKeepsPreviousAtomicRecordAndRetryDoesNotDiscardDraft() {
        var fail = false
        val store = EditorDraftStore(rows::get, { k, v -> if (fail) error("disk full") else rows[k] = v }, { rows.remove(it) })
        val key = store.key("a", "profile", UUID.randomUUID().toString())
        store.checkpoint(key) { "opening" }; fail = true
        assertTrue(runCatching { store.checkpoint(key) { "edited" } }.isFailure)
        assertEquals("opening", store.load(key).get())
        fail = false; store.checkpoint(key) { "edited" }
        assertEquals("edited", store.load(key).get())
    }
}
