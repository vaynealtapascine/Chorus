package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class SearchJumpTest {
    private fun projection(): JSONObject {
        val p=JSONObject(java.io.File(System.getProperty("chorus.fixtures"),"ui_search.json").readText()).getJSONObject("projection")
        val messages=JSONObject()
        for(i in 0 until 250) messages.put("old-$i",JSONObject().put("exists",true).put("fields",JSONObject().put("channel_id","c").put("account_id","account").put("occurred_at",i).put("text","needle $i")))
        p.getJSONObject("rows").put("message",messages)
        return p
    }
    @Test fun searchJumpLoadsAnOlderTargetWithoutEnlargingTheOrdinaryWindow() {
        val p=projection()
        val model=Model.parse(p,"account")
        assertEquals(100,model.chatMessages["c"]!!.size)
        assertFalse(model.chatMessages["c"]!!.any {it.id=="old-10"})
        val view=SearchVisibility(model,"account")
        val window=Model.channelWindow(p,"c",100,"old-10",view::message)
        assertTrue(window.messages.any {it.id=="old-10"})
        assertTrue(window.messages.size<=100)
        p.getJSONObject("rows").getJSONObject("space").getJSONObject("s").getJSONObject("fields").put("created_by_account_id","other")
        p.getJSONObject("rows").getJSONObject("channel_permission").put("c|account|account",JSONObject().put("exists",true).put("fields",JSONObject().put("deny",org.json.JSONArray().put("view"))))
        val revoked=SearchVisibility(Model.parse(p,"account"),"account")
        assertTrue(Model.channelWindow(p,"c",100,"old-10",revoked::message).messages.isEmpty())
    }
    @Test fun serverFallbackCannotResurrectARevokedOrDeletedTarget() = runBlocking {
        val p=projection()
        val model=Model.parse(p,"account")
        val target=Model.channelWindow(p,"c",100,"old-10").messages.first {it.id=="old-10"}
        val loaded=SearchJump.load(model,"account","c",target.id,emptyMap(),local={emptyList()},remote={listOf(target,target)})
        assertEquals(listOf(target.id),loaded.messages.map {it.id})
        p.getJSONObject("rows").getJSONObject("message").getJSONObject(target.id).getJSONObject("fields").put("deleted_at",999)
        val deleted=Model.parse(p,"account")
        assertTrue(SearchJump.load(deleted,"account","c",target.id,emptyMap(),local={emptyList()},remote={listOf(target)}).messages.isEmpty())
        p.getJSONObject("rows").getJSONObject("message").getJSONObject(target.id).getJSONObject("fields").remove("deleted_at")
        p.getJSONObject("rows").getJSONObject("space").getJSONObject("s").getJSONObject("fields").put("created_by_account_id","other")
        p.getJSONObject("rows").getJSONObject("channel_permission").put("c|account|account",JSONObject().put("exists",true).put("fields",JSONObject().put("deny",org.json.JSONArray().put("view"))))
        val revoked=Model.parse(p,"account")
        assertTrue(SearchJump.load(revoked,"account","c",target.id,emptyMap(),local={listOf(target)},remote={error("Local restrictions must win")}).messages.isEmpty())
    }

}
