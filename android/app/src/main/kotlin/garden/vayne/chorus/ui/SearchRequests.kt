package garden.vayne.chorus.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import garden.vayne.chorus.data.SearchDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A fresh owner per search/connection/projection incarnation, even when values return to A. */
internal class SearchRequests {
    data class Page(val items: List<SearchDocument>, val cursor: String?)
    var items by mutableStateOf<List<SearchDocument>>(emptyList()); private set
    var cursor by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var loading by mutableStateOf(false); private set
    private var alive = true
    private var request: Any? = null
    private var job: Job? = null

    fun close() {
        alive = false
        request = null
        job?.cancel()
    }
    fun first(scope: CoroutineScope, fetch: suspend () -> Page) {
        if (!alive) return
        job?.cancel()
        items = emptyList(); cursor = null; error = null
        launch(scope, false, fetch)
    }
    fun more(scope: CoroutineScope, fetch: suspend (String) -> Page) {
        val next = cursor ?: return
        if (!alive || loading) return
        launch(scope, true) { fetch(next) }
    }
    private fun launch(scope: CoroutineScope, append: Boolean, fetch: suspend () -> Page) {
        if (!alive) return
        val token = Any()
        request = token
        loading = true
        job = scope.launch {
            fun owns() = alive && request === token
            try {
                val page = fetch()
                if (owns()) {
                    items = (if (append) items else emptyList()) + page.items
                    cursor = page.cursor
                    error = null
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (owns()) error = e.message ?: "Server search is unavailable." }
            finally { if (owns()) loading = false }
        }
    }
}
