package garden.vayne.chorus.ui

import garden.vayne.chorus.data.SearchDocument
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SearchRequestsTest {
    private fun page(id: String, cursor: String? = "C") = SearchRequests.Page(
        listOf(SearchDocument(id,"Posts",1,id)),cursor)

    @Test fun reconnectAndQueryABARejectOldSameCursorPageEvenWhenCancellationIsIgnored() = runBlocking {
        for (transition in listOf("reconnect", "query A-B-A", "projection")) {
            val old = SearchRequests()
            old.first(this) {page("authorized-old")}; yield()
            val late = CompletableDeferred<SearchRequests.Page>()
            var cancelled = false
            old.more(this) {
                try {late.await()} catch(e:CancellationException) {
                    cancelled = true
                    withContext(NonCancellable) {late.await()}
                }
            }; yield()
            old.close()
            val fresh = SearchRequests()
            fresh.first(this) {page("fresh-A")}; yield()
            assertEquals("C",fresh.cursor)
            val current = CompletableDeferred<SearchRequests.Page>()
            fresh.more(this) {current.await()}; yield()
            late.complete(page("revoked-foreign-post")); yield()
            assertTrue(transition,cancelled)
            assertEquals(listOf("authorized-old"),old.items.map {it.id})
            assertEquals("C",old.cursor)
            assertTrue(old.loading)
            assertNull(old.error)
            assertEquals(listOf("fresh-A"),fresh.items.map {it.id})
            assertTrue("old finally must not finish current loading",fresh.loading)
            assertNull(fresh.error)
            current.complete(page("current-C",null)); yield()
            assertEquals(listOf("fresh-A","current-C"),fresh.items.map {it.id})
            assertFalse(fresh.loading)
            fresh.close()
        }
    }
    @Test fun sameOwnerRestartAlsoRejectsOldPageAtTheSameCursor() = runBlocking {
        val requests=SearchRequests()
        requests.first(this) {page("old-A")}; yield()
        val late=CompletableDeferred<SearchRequests.Page>()
        requests.more(this) {withContext(NonCancellable) {late.await()}}; yield()
        requests.first(this) {page("fresh-A")}; yield()
        val current=CompletableDeferred<SearchRequests.Page>()
        requests.more(this) {current.await()}; yield()
        late.complete(page("revoked")); yield()
        assertEquals(listOf("fresh-A"),requests.items.map {it.id})
        assertEquals("C",requests.cursor)
        assertTrue(requests.loading)
        current.complete(page("current",null)); yield()
        assertEquals(listOf("fresh-A","current"),requests.items.map {it.id})
        requests.close()
    }
    @Test fun restartingFirstPageOwnsAllResultsErrorsAndLoadingWrites() = runBlocking {
        for (fails in listOf(false,true)) {
            val requests=SearchRequests()
            val late=CompletableDeferred<Unit>()
            requests.first(this) {
                withContext(NonCancellable) {late.await()}
                if(fails) error("obsolete failure") else page("obsolete first")
            }; yield()
            val current=CompletableDeferred<SearchRequests.Page>()
            requests.first(this) {current.await()}; yield()
            late.complete(Unit); yield()
            assertTrue(requests.items.isEmpty())
            assertNull(requests.error)
            assertTrue(requests.loading)
            current.complete(page("fresh",null)); yield()
            assertEquals(listOf("fresh"),requests.items.map {it.id})
            requests.close()
        }
    }
    @Test fun closedPageFailureDoesNotWriteAnErrorOrLoadingState() = runBlocking {
        val requests=SearchRequests()
        requests.first(this) {page("first")}; yield()
        val late=CompletableDeferred<Unit>()
        requests.more(this) {withContext(NonCancellable) {late.await()}; error("obsolete page error")}; yield()
        requests.close()
        late.complete(Unit); yield()
        assertNull(requests.error)
        assertEquals(listOf("first"),requests.items.map {it.id})
    }
}
