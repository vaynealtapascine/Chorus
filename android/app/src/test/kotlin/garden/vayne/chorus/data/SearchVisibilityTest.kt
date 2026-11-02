package garden.vayne.chorus.data

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class SearchVisibilityTest {
    @Test fun sharedCurrentFrontContextAndScopeFixtures() {
        val f=JSONObject(java.io.File(System.getProperty("chorus.fixtures"),"ui_search.json").readText())
        val cases=f.getJSONArray("cases")
        for(i in 0 until cases.length()) {
            val c=cases.getJSONObject(i)
            val p=JSONObject(f.getJSONObject("projection").toString())
            p.put("fronts",JSONObject().put("account",JSONObject().put("current",JSONArray().apply {
                for(id in SearchVisibility.strings(c.getJSONArray("front"))) put(JSONObject().put("subject_type","member").put("subject_id",id).put("level","front"))
            })))
            val model=Model.parse(p,"account")
            val view=SearchVisibility(model,"account")
            assertEquals(c.getString("name"),SearchVisibility.strings(c.getJSONArray("visible")).sorted(),model.searchMessages.filter(view::document).map {it.id}.sorted())
            val hit=model.searchMessages.first {it.id=="m-match"}
            assertEquals(SearchVisibility.strings(c.getJSONArray("context")),view.context(hit))
            val scoped=SearchVisibility(model,"account","c")
            assertFalse(model.searchMessages.filter(scoped::document).any {it.channelId!="c"})
            val posts=LocalSearch.fromModel(model).search("garden","Posts")
            assertEquals(listOf("entry","note","reply"),posts.map {it.id}.sorted())
            assertEquals(listOf("entry"),posts.filter {it.id in model.accountPrefs.bookmarks}.map {it.id})
            assertTrue(LocalSearch.fromModel(model).search("needle","Posts").isEmpty())
        }
    }
    @Test fun localTombstoneRejectsRetainedNetworkCard() {
        val f=JSONObject(java.io.File(System.getProperty("chorus.fixtures"),"ui_search.json").readText())
        val p=f.getJSONObject("projection")
        val old=Model.parse(p,"account").searchMessages.first {it.id=="m-match"}
        p.getJSONObject("rows").getJSONObject("message").getJSONObject("m-match").getJSONObject("fields").put("deleted_at",30)
        assertFalse(SearchVisibility(Model.parse(p,"account"),"account").document(old))
    }
    @Test fun remoteCardsPreserveFormattingAttachmentsAndIndependentContext() {
        val row=JSONObject("""{"items":[{"id":"m","channel_id":"c","text":"full","occurred_at":10,"entities":[{"type":"bold","offset":0,"length":4}],"visibility":{"mode":"members","member_ids":["kai"]},"attachments":[{"id":"a","blob_hash":"hash","mime":"image/png"}],"context_before":{"id":"b","channel_id":"c","text":"before","occurred_at":1}}],"next_cursor":null}""")
        val doc=MessageSearchApi.parse(row).items.single()
        assertTrue(doc.card!!.entities.contains("bold"))
        assertEquals("hash",doc.card.attachments.single().blobHash)
        assertEquals("b",doc.before!!.id)
        assertNull(doc.after)
        assertTrue(MessageSearchApi.pathRaw("in:elsewhere",tzOffsetMin=0,channelId="c").contains("channel_id=c"))
        assertTrue(MessageSearchApi.pathRaw("x",tzOffsetMin=0).contains("current=true"))
        assertTrue(PostSearchApi.path(LocalSearchQuery(listOf("x")),saved=true).contains("saved=true"))
    }
}
