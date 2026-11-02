package garden.vayne.chorus.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import garden.vayne.chorus.data.ChatMessage
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.SearchJump
import garden.vayne.chorus.data.SearchVisibility

/** History is an explicit temporary mode. Draft and list-position objects belong to the room. */
internal class ChatHistory {
    var target by mutableStateOf<String?>(null); private set
    private var owner: Model? = null
    private var connection: Any? = null
    private var request: Any? = null
    private var rows by mutableStateOf<SearchJump.Rows?>(null)
    fun bind(model: Model, status: Any, account: String) {
        if (owner !== model || connection != status) {
            // Keep already available local history stable during a refresh, but never reuse
            // remote ancestor grants. Today's model must authorize every retained row.
            val view = SearchVisibility(model,account)
            val retained = rows?.messages?.filter(view::message).orEmpty()
            rows = if (retained.any { it.id == target }) SearchJump.Rows(retained,emptyMap()) else null
            owner = model; connection = status; request = null
        }
    }
    fun begin(id: String): Any {
        if (target != id) rows = null
        target = id
        return Any().also { request = it }
    }
    fun owns(token: Any) = request === token
    fun loaded(token: Any, value: SearchJump.Rows) {
        if (owns(token)) rows = value
    }
    fun latest() { request = null; target = null; rows = null }
    fun sent() = latest()
    fun messages(model: Model, live: List<ChatMessage>): List<ChatMessage> = window(model)?.messages ?: live
    fun window(model: Model): SearchJump.Rows? = rows.takeIf { owner === model }
}
