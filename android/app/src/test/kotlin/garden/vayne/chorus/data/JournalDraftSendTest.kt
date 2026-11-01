package garden.vayne.chorus.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class JournalDraftSendTest {
    private fun model(author: String = "a", buckets: List<Pair<String, String>> = listOf("bucket" to "Close")) = Model(
        listOf(Member("a", "A", null, null, "#fff", emptyList(), null, false, createdByAccountId = "mine"),
            Member("b", "B", null, null, "#fff", emptyList(), null, false, createdByAccountId = "mine")),
        emptyList(), emptyMap(), listOf(Entry("member", author, "front", true)), null, emptyList(), sharingBuckets = buckets)
    private val file = JournalDraftFile("content://lost/grant", "Photo.png", "image/png", "my flowers", true)
    private fun draft() = JournalDraft(UUID.randomUUID().toString(), body = "**words**", authors = listOf("b", "a"), audience = "buckets",
        buckets = listOf("bucket"), poll = true, options = listOf("one", "two"), files = listOf(file))
    private fun session(): JournalDraftSession {
        val rows = ConcurrentHashMap<String, String>()
        return JournalDraftSession(EditorDraftStore(rows::get, { k, v -> rows[k] = v }, { rows.remove(it) }),
            "base|mine", { "base|mine" }, { UUID.randomUUID().toString() }, { false })
    }

    @Test fun missingAttachmentPreflightKeepsReferenceAltSpoilerAndTextWithoutStaging() = runBlocking {
        val session = session(); session.restore(); session.edit { draft() }
        val original = session.state.value.draft
        var staged = false
        assertNull(session.send(prepare = { JournalDraftSend.snapshot(model(), "mine", it) },
            validate = { JournalDraftSend.validate(model(), "mine", it) { false } },
            stage = { staged = true; emptyList() }, create = { _, _, _ -> fail() }))
        assertFalse(staged); assertEquals(original, session.state.value.draft)
        assertTrue(session.state.value.error.orEmpty().contains("Photo.png"))
        assertEquals(file, JournalDraft.decode(original.encode()) { UUID.randomUUID().toString() }.files.single())
    }

    @Test fun defaultAuthorsAreFrozenAtSendEvenIfTheFrontChangesWhileStaging() = runBlocking {
        val session = session(); session.restore(); session.edit { draft().copy(authors = null) }
        val entered = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        var live = model("a"); var createdAuthors: List<String>? = null
        val sending = async { session.send(prepare = { JournalDraftSend.snapshot(live, "mine", it) },
            validate = { JournalDraftSend.validate(live, "mine", it) { true } }, stage = {
                entered.complete(Unit); resume.await(); listOf("staged")
            }, create = { draft, _, _ -> createdAuthors = draft.authors; error("pretend disk failure") }) }
        entered.await(); live = model("b"); resume.complete(Unit); sending.await()
        assertEquals(listOf("a"), createdAuthors)
        assertNull(session.state.value.draft.authors) // failed send retains default INTENT
    }

    @Test fun unavailableBucketAfterStagingFailsTheSecondPreflightWithoutLosingFields() = runBlocking {
        val session = session(); session.restore(); session.edit { draft() }
        val original = session.state.value.draft
        var live = model(); var checks = 0; var created = false
        session.send(prepare = { JournalDraftSend.snapshot(live, "mine", it) }, validate = {
            checks++; JournalDraftSend.validate(live, "mine", it) { true }
        }, stage = { live = model(buckets = emptyList()); listOf("staged") }, create = { _, _, _ -> created = true })
        assertEquals(2, checks); assertFalse(created); assertEquals(original, session.state.value.draft)
        assertTrue(session.state.value.error.orEmpty().contains("buckets"))
    }

    @Test fun unavailableAuthorAfterStagingFailsBeforeCreateAndKeepsExplicitOrder() = runBlocking {
        val session = session(); session.restore(); session.edit { draft() }
        var live = model(); var created = false
        session.send(prepare = { JournalDraftSend.snapshot(live, "mine", it) }, validate = {
            JournalDraftSend.validate(live, "mine", it) { true }
        }, stage = { live = Model.Empty; emptyList() }, create = { _, _, _ -> created = true })
        assertFalse(created); assertEquals(listOf("b", "a"), session.state.value.draft.authors)
    }
}
