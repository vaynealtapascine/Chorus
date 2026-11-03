package garden.vayne.chorus.ui

import androidx.compose.foundation.lazy.LazyListState
import garden.vayne.chorus.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatHistoryTest {
    private fun projection(): JSONObject {
        val p=JSONObject(java.io.File(System.getProperty("chorus.fixtures"),"ui_search.json").readText()).getJSONObject("projection")
        val messages=JSONObject()
        for(i in 0 until 250) messages.put("old-$i",JSONObject().put("exists",true).put("fields",JSONObject().put("channel_id","c").put("account_id","account").put("occurred_at",i).put("text","needle $i")))
        p.getJSONObject("rows").put("message",messages)
        return p
    }
    @Test fun historicalJumpReturnsToLiveAfterLatestOrSendAndDoesNotOwnDraftOrScroll() {
        for(action in listOf("latest","send")) {
            val p=projection(); val model=Model.parse(p,"account")
            val history=ChatHistory(); history.bind(model,true,"account")
            val draft=ChatDraft().apply {text.value="unfinished draft"; replyTo.value="old-9"}
            val reading=ChatReadingPosition(LazyListState(23,17),"old-249")
            val token=history.begin("old-10")
            val rows=SearchJump.Rows(Model.channelWindow(p,"c",100,"old-10").messages,emptyMap())
            history.loaded(token,rows)
            assertTrue(history.window(model)!!.messages.any {it.id=="old-10"})
            assertEquals(23,reading.list.firstVisibleItemIndex)
            assertEquals(17,reading.list.firstVisibleItemScrollOffset)
            assertEquals("unfinished draft",draft.text.value)
            // A new live message must become available on leaving history, even at index 0.
            p.getJSONObject("rows").getJSONObject("message").put("new-250",JSONObject().put("exists",true).put("fields",JSONObject().put("channel_id","c").put("account_id","account").put("occurred_at",250).put("text","new live")))
            val updated=Model.parse(p,"account"); history.bind(updated,true,"account")
            assertTrue(history.messages(updated,updated.chatMessages["c"].orEmpty()).any {it.id=="old-10"})
            val refresh=history.begin("old-10")
            assertTrue(history.messages(updated,updated.chatMessages["c"].orEmpty()).any {it.id=="old-10"})
            history.loaded(refresh,rows)
            if(action=="send") history.sent() else history.latest()
            history.loaded(refresh,rows) // An ignored cancellation cannot re-enter history.
            assertNull(action,history.target)
            val displayed=history.messages(updated,updated.chatMessages["c"].orEmpty())
            assertEquals("new-250",displayed.last().id)
            assertEquals("unfinished draft",draft.text.value)
            assertEquals("old-9",draft.replyTo.value)
            assertEquals(23,reading.list.firstVisibleItemIndex)
        }
    }
    @Test fun projectionRevocationInvalidatesHistoryAndItsPendingResponse() {
        val p=projection(); val model=Model.parse(p,"account")
        val history=ChatHistory(); history.bind(model,true,"account")
        val token=history.begin("old-10")
        val rows=SearchJump.Rows(Model.channelWindow(p,"c",100,"old-10").messages,emptyMap())
        history.loaded(token,rows)
        p.getJSONObject("rows").getJSONObject("message").getJSONObject("old-10").getJSONObject("fields").put("deleted_at",999)
        val revoked=Model.parse(p,"account"); history.bind(revoked,true,"account")
        history.loaded(token,rows)
        assertFalse(history.owns(token))
        assertFalse(history.messages(revoked,revoked.chatMessages["c"].orEmpty()).any {it.id=="old-10"})
    }
    @Test fun unrelatedStalledAncestorCannotBlockLocalJumpAndTargetAncestorStillFailsClosed() = runBlocking {
        val p=projection()
        val model=Model.parse(p,"account")
        val requested=mutableListOf<String>()
        // Fixture has an unrelated missing-parent thread; fetching it would never finish.
        val deps=kotlinx.coroutines.withTimeout(1000) { SearchAncestors.load(model,listOf("c")) {id -> requested.add(id); kotlinx.coroutines.awaitCancellation()} }
        val jump=SearchJump.load(model,"account","c","old-10",deps,
            local={view -> Model.channelWindow(p,"c",100,"old-10",view::message).messages},remote={error("local jump must not need network")})
        assertTrue(requested.isEmpty())
        assertTrue(jump.messages.any {it.id=="old-10"})
        val deniedDeps=SearchAncestors.load(model,listOf("thread")) {id -> requested.add(id); null}
        assertTrue(requested.isNotEmpty())
        val target=ChatMessage("child","thread",emptyList(),"hidden",5,null,"all",emptySet(),"account",null,emptyList())
        assertFalse(SearchVisibility(model,"account",ancestors=deniedDeps).message(target))
    }
}
