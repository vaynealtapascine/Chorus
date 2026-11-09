package garden.vayne.chorus.data

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class JournalDraftSessionTest {
    private class Fixture {
        val rows = ConcurrentHashMap<String, String>()
        val posts = ConcurrentHashMap.newKeySet<String>()
        var owner = "https://a|account-a"
        private val ids = AtomicInteger()
        val storage = EditorDraftStore(rows::get, { k, v -> rows[k] = v }, { rows.remove(it) })
        fun id(): String = UUID(0, ids.incrementAndGet().toLong()).toString()
        fun session(store: EditorDraftStore = storage): JournalDraftSession {
            val accountOwner = owner
            return JournalDraftSession(store, accountOwner, { owner }, ::id, { "$accountOwner:$it" in posts })
        }
        fun commit(id: String) { posts.add("$owner:$id") }
        fun saved(): JournalDraft = JournalDraft.decode(rows.values.first(), ::id)
    }
    private fun full(id: String) = JournalDraft(id, revision = 8, body = "**our words** 😀\nsecond line", selectionStart = 3, selectionEnd = 11,
        title = "A title", kind = "entry", authors = listOf("b", "a"), authorHint = "b", audience = "buckets", buckets = listOf("close", "family"),
        cw = "warning", tags = "#day, notes", mood = "bright", reply = "parent", quote = "quote", poll = true,
        options = listOf("one", "two", ""), multiple = true, files = listOf(JournalDraftFile("content://photos/1", "photo", "image/png", "a garden", true)),
        editing = true, advanced = true)

    @Test fun wholeComposerRoundTripsIncludingNullVersusEmptyAuthorsAndQueuedLaunches() {
        val f = Fixture()
        for (authors in listOf(null, emptyList(), listOf("b", "a"))) {
            val draft = full(f.id()).copy(authors = authors, sendingPost = "post", receipts = listOf("received"), launches = listOf(JournalLaunch("share", "share", "incoming", full(f.id()).files)))
            assertEquals(draft, JournalDraft.decode(draft.encode(), f::id))
        }
        val large = full(f.id()).copy(body = "words😀".repeat(100_000))
        assertEquals(large, JournalDraft.decode(large.encode(), f::id))
    }

    @Test fun draftsGoOnTheShelfAndComeBackWithoutLosingTheComposer() = runBlocking {
        val f = Fixture()
        val session = f.session(); session.restore()
        assertFalse("an empty composer has nothing to keep", session.shelve())
        session.edit { copy(body = "first", title = "One") }
        assertTrue(session.shelve())
        assertEquals("", session.state.value.draft.body)
        assertEquals(listOf("first"), session.shelf.value.map { it.body })
        session.edit { copy(body = "second") }
        val first = session.shelf.value.single().id
        session.unshelve(first)
        assertEquals("first", session.state.value.draft.body)
        assertEquals("One", session.state.value.draft.title)
        assertEquals("what the composer held is kept", listOf("second"), session.shelf.value.map { it.body })
        session.checkpoint(); session.detach()
        val again = f.session(); again.restore()
        assertEquals("first", again.state.value.draft.body)
        assertEquals(listOf("second"), again.shelf.value.map { it.body })
        again.discardShelved(again.shelf.value.single().id)
        assertTrue(again.shelf.value.isEmpty())
        assertTrue(JournalDraftSession.decodeShelf("not json").single().recovery == "not json")
    }

    @Test fun malformedMetadataSalvagesContentAndKeepsTheExactOriginalForReview() = runBlocking {
        val f = Fixture(); val original = full(f.id())
        val raw = JSONObject(original.encode()).put("authors", "broken").put("files", 7).toString()
        val recovered = JournalDraft.decode(raw, f::id)
        assertEquals(original.body, recovered.body); assertEquals(original.title, recovered.title)
        assertEquals(original.options, recovered.options); assertEquals(emptyList<String>(), recovered.authors)
        assertEquals(raw, recovered.recovery)
        assertEquals(raw, JournalDraft.decode(recovered.encode(), f::id).recovery)
        val badVersion = JSONObject(original.encode()).put("version", 9).put("kind", "future-kind").toString()
        val safe = JournalDraft.decode(badVersion, f::id)
        assertEquals(badVersion, JournalDraft.decode(safe.encode(), f::id).recovery)
        assertEquals("broken JSON", JournalDraft.decode("broken JSON", f::id).body)
        val session = f.session(); session.restore(); session.edit { recovered }
        var staged = false
        assertNull(session.send(validate = {}, stage = { staged = true; emptyList() }, create = { _, _, _ -> fail() }))
        assertFalse(staged)
        session.keepRecoveredCopy(); session.checkpoint()
        assertNull(session.state.value.draft.recovery)
        assertTrue(f.rows.values.contains(raw))
    }

    @Test fun loadFinishesBeforeAnyAutosaveOrUIEditAndDetachRejectsStaleLoads() = runBlocking {
        val f = Fixture(); val original = full(f.id())
        val readStarted = CountDownLatch(1); val release = CountDownLatch(1); val writes = AtomicInteger()
        val store = EditorDraftStore({ readStarted.countDown(); check(release.await(5, TimeUnit.SECONDS)); original.encode() },
            { _, _ -> writes.incrementAndGet() }, {})
        val session = f.session(store)
        val load = async(Dispatchers.Default) { session.restore() }
        assertTrue(readStarted.await(5, TimeUnit.SECONDS))
        try {
            session.edit { copy(body = "empty default must not overwrite saved text") }
            session.enqueue(JournalLaunch("early", "entry")); session.checkpoint()
            assertEquals(0, writes.get()); assertFalse(session.state.value.loaded)
            release.countDown(); load.await()
            assertEquals(original, session.state.value.draft)
        } finally { release.countDown() }
        session.detach()
        val blocked = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val stale = JournalDraftSession(f.storage, f.owner, { f.owner }, f::id, { blocked.complete(Unit); finish.await(); true })
        // A restored in-flight send exercises a suspended load/reconciliation result.
        val key = f.storage.key(f.owner, "journal-composer", UUID.nameUUIDFromBytes("journal-composer".toByteArray()).toString())
        f.storage.checkpoint(key) { original.copy(sendingPost = "committed").encode() }
        val restoring = async { stale.restore() }; blocked.await(); stale.detach(); finish.complete(Unit); restoring.await()
        assertFalse(stale.state.value.loaded)
        assertEquals(original.body, f.saved().body)
    }

    @Test fun accountTransitionCannotApplyOrWriteAnOldLoadIntoANewAccount() = runBlocking {
        val f = Fixture(); val a = f.session(); a.restore(); a.edit { full(id) }; a.checkpoint()
        val original = a.state.value.draft
        f.owner = "https://b|account-b"
        a.edit { copy(body = "wrong account") }
        val b = f.session(); b.restore(); assertFalse(b.state.value.draft.occupied)
        b.edit { copy(body = "B text") }; b.checkpoint()
        a.detach(); b.detach(); f.owner = "https://a|account-a"
        val again = f.session(); again.restore(); assertEquals(original, again.state.value.draft)
        assertTrue(f.rows.values.any { JournalDraft.decode(it, f::id).body == "B text" })
    }

    @Test fun delayedOldAccountLoadIsIgnoredWhenANewAccountOpens() = runBlocking {
        val f = Fixture(); val original = full(f.id())
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val reference = UUID.nameUUIDFromBytes("journal-composer".toByteArray()).toString()
        val key = f.storage.key(f.owner, "journal-composer", reference)
        f.rows[key] = original.encode()
        val store = EditorDraftStore({ k ->
            if (k == key) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            f.rows[k]
        }, { k, v -> f.rows[k] = v }, { f.rows.remove(it) })
        val old = f.session(store); val oldLoad = async(Dispatchers.Default) { old.restore() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            f.owner = "https://b|account-b"
            val current = f.session(store); val currentLoad = async { current.restore() }
            release.countDown(); oldLoad.await(); currentLoad.await()
            assertFalse(old.state.value.loaded); assertFalse(current.state.value.draft.occupied)
            current.edit { copy(body = "new account") }; current.checkpoint(); old.detach()
            assertEquals(original, JournalDraft.decode(f.rows.getValue(key), f::id))
        } finally { release.countDown() }
    }

    @Test fun delayedOlderAutosaveCannotOverwriteAUIEditOrReopenCheckpoint() = runBlocking {
        val f = Fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val store = EditorDraftStore(f.rows::get, { k, v ->
            if (JournalDraft.decode(v, f::id).body == "old") { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            f.rows[k] = v
        }, { f.rows.remove(it) })
        val session = f.session(store); session.restore(); session.edit { copy(body = "old") }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            session.edit { full(id).copy(body = "newest", editing = false) }
            val closed = async(Dispatchers.Default) { session.detach() }
            release.countDown(); closed.await()
            val reopened = f.session(store); reopened.restore()
            assertEquals("newest", reopened.state.value.draft.body)
            assertEquals(listOf("b", "a"), reopened.state.value.draft.authors)
            reopened.edit { copy(editing = true) }; reopened.checkpoint()
            assertEquals("newest", f.saved().body)
        } finally { release.countDown() }
    }

    @Test fun queuedLaunchesSurviveSuccessfulSendAndNeverEnterItsSnapshot() = runBlocking {
        val f = Fixture(); val session = f.session(); session.restore(); session.edit { full(id) }
        val original = session.state.value.draft
        val staged = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val sending = async { session.send(validate = { assertEquals(original, it) }, stage = {
            staged.complete(Unit); release.await(); listOf("attachment")
        }, create = { draft, post, files -> assertEquals(original, draft); assertEquals(listOf("attachment"), files); f.commit(post) }) }
        staged.await()
        val requests = listOf(JournalLaunch("share", "share", "incoming"), JournalLaunch("entry", "entry"), JournalLaunch("reply", "reply", post = "parent"))
        requests.forEach(session::enqueue)
        session.edit { copy(body = "must be disabled while sending") }; session.resolveLaunch(true)
        assertEquals(original.body, session.state.value.draft.body)
        release.complete(Unit); assertNotNull(sending.await())
        assertFalse(session.state.value.draft.occupied); assertEquals(requests, session.state.value.draft.launches)
        session.resolveLaunch(true); assertEquals("incoming", session.state.value.draft.body)
        session.checkpoint(); val reopen = f.session(); reopen.restore()
        assertEquals(requests.drop(1), reopen.state.value.draft.launches)
    }

    @Test fun delayedSendMarkerCannotOverwriteALaunchAcceptedWhileItsWriteIsPending() = runBlocking {
        val f = Fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val store = EditorDraftStore(f.rows::get, { k, v ->
            if (JournalDraft.decode(v, f::id).sendingPost != null) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            f.rows[k] = v
        }, { f.rows.remove(it) })
        val session = f.session(store); session.restore(); session.edit { full(id) }
        val sending = async { session.send(validate = {}, stage = { emptyList() }, create = { _, post, _ -> f.commit(post) }) }
        // Let send reach its first suspension, the disk checkpoint.
        yield(); assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            session.enqueue(JournalLaunch("share", "share", "new text"))
            release.countDown(); assertNotNull(sending.await())
            assertEquals("new text", f.saved().launches.single().text)
        } finally { release.countDown() }
    }

    @Test fun failureRetainsAllFieldsAndQueuedReplyRequiresExplicitResolution() = runBlocking {
        val f = Fixture(); val session = f.session(); session.restore(); session.edit { full(id) }
        val original = session.state.value.draft
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val sending = async { session.send(validate = {}, stage = { entered.complete(Unit); finish.await(); error("file copy failed") }, create = { _, _, _ -> fail() }) }
        entered.await(); session.enqueue(JournalLaunch("reply", "reply", post = "new-parent", author = "new-author", audience = "server"))
        finish.complete(Unit); assertNull(sending.await())
        assertEquals(original, session.state.value.draft.copy(launches = emptyList()))
        session.resolveLaunch(true)
        assertEquals(original.authors, session.state.value.draft.authors)
        assertEquals(original.body, session.state.value.draft.body)
        assertEquals("new-parent", session.state.value.draft.reply)
        assertEquals("server", session.state.value.draft.audience)
        assertNull(session.state.value.draft.quote)
        session.checkpoint(); assertEquals(session.state.value.draft, f.saved())
    }

    @Test fun cancellationBeforeCommitRetainsDraftAndRethrowsCancellation() = runBlocking {
        val f = Fixture(); val session = f.session(); session.restore(); session.edit { full(id) }
        val original = session.state.value.draft; val entered = CompletableDeferred<Unit>()
        val sending = launch { session.send(validate = {}, stage = { entered.complete(Unit); awaitCancellation() }, create = { _, _, _ -> fail() }) }
        entered.await(); sending.cancelAndJoin()
        assertTrue(sending.isCancelled); assertFalse(session.state.value.busy)
        assertEquals(original, session.state.value.draft); assertEquals(original, f.saved())
    }

    @Test fun cancellationAfterCommitConsumesOnlyTheCommittedDraftAndRethrows() = runBlocking {
        val f = Fixture(); val session = f.session(); session.restore(); session.edit { full(id) }
        val committed = CompletableDeferred<Unit>()
        val sending = launch { session.send(validate = {}, stage = { emptyList() }, create = { _, id, _ ->
            f.commit(id); committed.complete(Unit); awaitCancellation()
        }) }
        committed.await(); session.enqueue(JournalLaunch("queued", "share", "next draft")); sending.cancelAndJoin()
        assertTrue(sending.isCancelled); assertFalse(session.state.value.draft.occupied)
        session.resolveLaunch(true); assertEquals("next draft", session.state.value.draft.body)
    }

    @Test fun crashWindowRestoresCommittedPostAsConsumedButPreservesUncommittedDraft() = runBlocking {
        for (commit in listOf(false, true)) {
            val f = Fixture(); val session = f.session(); session.restore(); session.edit { full(id) }
            val original = session.state.value.draft
            try {
                session.send(validate = {}, stage = { emptyList() }, create = { _, post, _ ->
                    assertEquals(post, f.saved().sendingPost) // chosen identity is durable before create
                    if (commit) f.commit(post)
                    session.detach() // models process/editor death before completion cleanup
                    throw CancellationException("process died after create")
                })
                fail("must rethrow cancellation")
            } catch (_: CancellationException) { /* next process reconciles the persisted marker */ }
            val restarted = f.session(); restarted.restore()
            if (commit) assertFalse(restarted.state.value.draft.occupied)
            else assertEquals(original, restarted.state.value.draft)
            assertNull(restarted.state.value.draft.sendingPost)
        }
    }

    @Test fun detachedOldCompletionCannotWipeANewerDraftOfTheSameAccount() = runBlocking {
        val f = Fixture(); val old = f.session(); old.restore(); old.edit { full(id) }
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val send = async { old.send(validate = {}, stage = { emptyList() }, create = { _, post, _ ->
            f.commit(post); old.detach(); entered.complete(Unit); finish.await()
        }) }
        entered.await()
        val next = f.session(); next.restore(); next.edit { copy(body = "new draft generation") }; next.checkpoint()
        finish.complete(Unit); send.await()
        val reopened = f.session(); reopened.restore(); assertEquals("new draft generation", reopened.state.value.draft.body)
    }

    @Test fun replacementEditorDrainsAcceptedEditsAndRejectsLaterOldAutosavesAndCheckpoints() = runBlocking {
        val f = Fixture(); val old = f.session(); old.restore(); old.edit { copy(body = "latest accepted keystrokes") }
        val replacement = f.session(); replacement.restore()
        assertEquals("latest accepted keystrokes", replacement.state.value.draft.body)
        replacement.edit { copy(body = "new activity") }; replacement.checkpoint()
        old.edit { copy(body = "late old activity") }; old.checkpoint(); old.detach()
        val reopened = f.session(); reopened.restore(); assertEquals("new activity", reopened.state.value.draft.body)
    }

    @Test fun failedLoadCannotAutosaveEmptyAndCanRetryWithoutLosingSavedContent() = runBlocking {
        val f = Fixture(); var fail = true; val original = full(f.id()); val writes = AtomicInteger()
        val store = EditorDraftStore({ if (fail) error("read failed") else original.encode() }, { _, _ -> writes.incrementAndGet() }, {})
        val session = f.session(store); session.restore(); session.edit { copy(body = "empty") }; session.checkpoint()
        assertFalse(session.state.value.loaded); assertEquals(0, writes.get())
        assertTrue(session.state.value.error.orEmpty().contains("Retry"))
        fail = false; session.restore(); assertEquals(original, session.state.value.draft)
    }

    @Test fun markerWriteFailurePreventsCreateAndKeepsTheLastDurableContent() = runBlocking {
        val f = Fixture(); var fail = false
        val store = EditorDraftStore(f.rows::get, { k, v -> if (fail) error("disk full") else f.rows[k] = v }, { f.rows.remove(it) })
        val session = f.session(store); session.restore(); session.edit { full(id) }; session.checkpoint()
        val original = session.state.value.draft; fail = true; var created = false
        assertNull(session.send(validate = {}, stage = { emptyList() }, create = { _, _, _ -> created = true }))
        assertFalse(created); assertEquals(original, session.state.value.draft); assertEquals(original, f.saved())
        fail = false; session.checkpoint(); assertEquals(original, f.saved())
    }

    @Test fun uncertainCommitBlocksEditsUntilRetryReconcilesWithoutASecondCreate() = runBlocking {
        val f = Fixture(); var queryFails = false
        val session = JournalDraftSession(f.storage, f.owner, { f.owner }, f::id, { post ->
            if (queryFails) error("cannot read ops") else "${f.owner}:$post" in f.posts
        })
        session.restore(); session.edit { full(id) }; var creates = 0
        session.send(validate = {}, stage = { emptyList() }, create = { _, post, _ ->
            creates++; f.commit(post); queryFails = true; error("caller lost commit result")
        })
        assertNotNull(session.state.value.draft.sendingPost)
        val kept = session.state.value.draft
        session.edit { copy(body = "must not edit uncertain send") }; assertEquals(kept, session.state.value.draft)
        queryFails = false
        assertNotNull(session.send(validate = { fail() }, stage = { fail(); emptyList() }, create = { _, _, _ -> creates++ }))
        assertEquals(1, creates); assertFalse(session.state.value.draft.occupied)
    }

    @Test fun externalReceiptIsDurableBeforeAcknowledgementAndFailedRetryCannotAppendShareTwice() = runBlocking {
        val f = Fixture(); var fail = false
        val store = EditorDraftStore(f.rows::get, { k, v -> if (fail) error("disk full") else f.rows[k] = v }, { f.rows.remove(it) })
        val session = f.session(store); session.restore(); fail = true
        val share = JournalLaunch("unique-share-id", "share", "shared text")
        assertFalse(session.receive(share))
        session.resolveLaunch(true) // UI may have opened the draft while receipt IO failed
        fail = false; assertTrue(session.receive(share))
        assertEquals("shared text", f.saved().body); assertTrue(f.saved().launches.isEmpty())
        val replacement = f.session(store); replacement.restore()
        assertTrue(replacement.receive(share)); assertTrue(replacement.state.value.draft.launches.isEmpty())
        assertEquals("shared text", replacement.state.value.draft.body)
    }

    @Test fun oldAccountSendCompletionCannotClearAnotherAccountsNewDraft() = runBlocking {
        val f = Fixture(); val old = f.session(); old.restore(); old.edit { full(id) }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var created = false
        val sending = async { old.send(validate = {}, stage = { entered.complete(Unit); release.await(); emptyList() }, create = { _, _, _ -> created = true }) }
        entered.await(); old.detach(); f.owner = "https://b|account-b"
        val current = f.session(); current.restore(); current.edit { copy(body = "new account text") }
        release.complete(Unit); assertNull(sending.await()); assertFalse(created)
        assertEquals("new account text", current.state.value.draft.body)
        current.checkpoint(); current.detach(); f.owner = "https://a|account-a"
        val reopened = f.session(); reopened.restore(); assertEquals("**our words** 😀\nsecond line", reopened.state.value.draft.body)
    }
}
