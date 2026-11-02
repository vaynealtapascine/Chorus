package garden.vayne.chorus.data

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class SearchVisibilityTest {
    @Test fun malformedHistoricalMessageAudienceDoesNotAbortModelRebuild() {
        for (newerCount in listOf(0, 150)) {
            val p=JSONObject(java.io.File(System.getProperty("chorus.fixtures"),"ui_search.json").readText()).getJSONObject("projection")
            val messages=p.getJSONObject("rows").getJSONObject("message")
            val old=messages.getJSONObject("m-match")
            old.getJSONObject("fields").put("visibility",JSONObject().put("mode","members").put("member_ids",JSONArray().put("rin").put(42)))
            for (i in 1..newerCount) {
                val row=JSONObject(old.toString())
                row.getJSONObject("fields").put("occurred_at",10000+i).put("visibility",JSONObject().put("mode","all"))
                messages.put("new-$i",row)
            }
            p.put("fronts",JSONObject())
            val model=Model.parse(p,"account")
            val hit=model.searchMessages.first {it.id=="m-match"}
            assertEquals(setOf("rin"),hit.visibleMemberIds)
            assertFalse(SearchVisibility(model,"account").document(hit))
            if (newerCount == 0) {
                assertEquals(setOf("rin"),model.chatMessages.getValue("c").first {it.id=="m-match"}.visibleMemberIds)
            } else {
                assertFalse(model.chatMessages.getValue("c").any {it.id=="m-match"})
            }
        }
    }
    @Test fun subsystemSearchMatchesSharedEligibilityAndContextFixtures() {
        val fixture=JSONObject(java.io.File(System.getProperty("chorus.fixtures"),"ui_search_subsystems.json").readText())
        val cases=fixture.getJSONArray("cases")
        for(i in 0 until cases.length()) {
            val c=cases.getJSONObject(i)
            val p=JSONObject(fixture.getJSONObject("projection").toString())
            val changes=c.getJSONObject("rows")
            val rows=p.getJSONObject("rows")
            for(table in changes.keys()) {
                val target=rows.optJSONObject(table) ?: JSONObject().also { rows.put(table,it) }
                val updates=changes.getJSONObject(table)
                for(id in updates.keys()) {
                    val row=target.optJSONObject(id) ?: JSONObject().put("exists",true).put("fields",JSONObject()).also { target.put(id,it) }
                    val fields=row.getJSONObject("fields")
                    val values=updates.getJSONObject(id)
                    for(key in values.keys()) fields.put(key,values.get(key))
                }
            }
            val membership=p.getJSONObject("sets").getJSONObject("group_membership")
            val sets=c.getJSONObject("sets")
            for(key in sets.keys()) membership.put(key,sets.get(key))
            p.getJSONObject("fronts").getJSONObject("account").put("current",JSONArray().apply {
                for(id in SearchVisibility.strings(c.getJSONArray("front"))) put(JSONObject().put("subject_type","member").put("subject_id",id).put("level",c.getString("level")))
            })
            val model=Model.parse(p,"account")
            val view=SearchVisibility(model,"account")
            assertEquals(c.getString("name"),SearchVisibility.strings(c.getJSONArray("visible")),model.searchMessages.filter(view::document).map {it.id}.sorted())
            val hit=Model.parse(fixture.getJSONObject("projection"),"account").searchMessages.first {it.id=="hit"}
            assertEquals(c.getString("name"),SearchVisibility.strings(c.getJSONArray("context")),view.context(hit))
        }
    }
    @Test fun malformedChannelMembershipNeverBecomesAnUnrestrictedLocalChat() {
        for(raw in listOf("[\"rin\",42]","[42]","{}","\"rin\"")) {
            val p=JSONObject(java.io.File(System.getProperty("chorus.fixtures"),"ui_search.json").readText()).getJSONObject("projection")
            p.getJSONObject("rows").getJSONObject("channel").getJSONObject("c").getJSONObject("fields").put("member_ids",org.json.JSONTokener(raw).nextValue())
            p.put("fronts",JSONObject().put("account",JSONObject().put("current",JSONArray().put(JSONObject().put("subject_type","member").put("subject_id","rin").put("level","front")))))
            p.getJSONObject("rows").getJSONObject("channel").getJSONObject("thread").getJSONObject("fields").put("parent_message_id","m-match")
            val model=Model.parse(p,"account")
            val view=SearchVisibility(model,"account")
            val hit=model.searchMessages.first {it.id=="m-match"}
            assertFalse(raw,view.document(hit))
            assertFalse(view.document(model.searchMessages.first {it.id=="thread-message"}))
            assertTrue(view.context(hit).isEmpty())
            assertFalse(ChatOrganize.shown(model)("c"))
        }
    }
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
    @Test fun guardedMissingParentAndLocalRestrictions() {
        val f=JSONObject(java.io.File(System.getProperty("chorus.fixtures"),"ui_search.json").readText())
        val p=f.getJSONObject("projection")
        val rows=p.getJSONObject("rows")
        rows.getJSONObject("channel").getJSONObject("thread").getJSONObject("fields").put("parent_message_id","m-match")
        val original=Model.parse(p,"account")
        val parent=original.searchMessages.first {it.id=="m-match"}.let { ChatMessage(it.id,it.channelId!!,it.authors,it.text,it.occurredAt,it.cw,"all",emptySet(),"account",null,emptyList()) }
        rows.getJSONObject("message").remove(parent.id)
        val model=Model.parse(p,"account")
        val hit=model.searchMessages.first {it.channelId=="thread"}
        assertFalse(SearchVisibility(model,"account").document(hit))
        assertTrue(SearchVisibility(model,"account",ancestors=mapOf(parent.id to parent)).document(hit))
        rows.getJSONObject("message").put(parent.id,JSONObject().put("exists",true).put("fields",JSONObject().put("channel_id","c").put("occurred_at",20).put("text","local").put("account_id","account").put("visibility",JSONObject().put("mode","members").put("member_ids",JSONArray().put("missing")))))
        assertFalse(SearchVisibility(Model.parse(p,"account"),"account",ancestors=mapOf(parent.id to parent)).document(hit))
        rows.getJSONObject("message").getJSONObject(parent.id).getJSONObject("fields").put("deleted_at",30)
        assertFalse(SearchVisibility(Model.parse(p,"account"),"account",ancestors=mapOf(parent.id to parent)).document(hit))
        rows.getJSONObject("message").remove(parent.id)
        rows.getJSONObject("space").getJSONObject("s").getJSONObject("fields").put("created_by_account_id","other")
        rows.getJSONObject("channel_permission").put("c|account|account",JSONObject().put("exists",true).put("fields",JSONObject().put("deny",JSONArray().put("view"))))
        assertFalse(SearchVisibility(Model.parse(p,"account"),"account",ancestors=mapOf(parent.id to parent)).document(hit))
    }
    @Test fun missingAncestorsUseGuardedFetchButLocalTombstonesNeverDo() = runBlocking {
        val f=JSONObject(java.io.File(System.getProperty("chorus.fixtures"),"ui_search.json").readText())
        val p=f.getJSONObject("projection")
        val messages=p.getJSONObject("rows").getJSONObject("message")
        val parent=Model.messageCards(p,setOf("m-match")).getValue("m-match")
        p.getJSONObject("rows").getJSONObject("channel").getJSONObject("thread").getJSONObject("fields").put("parent_message_id",parent.id)
        val row=messages.getJSONObject(parent.id)
        messages.remove(parent.id)
        val model=Model.parse(p,"account")
        val requested=mutableListOf<String>()
        val dependencies=SearchAncestors.load(model) {id->requested.add(id);parent.takeIf {it.id==id}}
        assertTrue(parent.id in requested)
        val hit=model.searchMessages.first {it.channelId=="thread"}
        assertTrue(SearchVisibility(model,"account",ancestors=dependencies).document(hit))
        assertFalse(SearchVisibility(model,"account",ancestors=SearchAncestors.load(model) {null}).document(hit))
        row.getJSONObject("fields").put("deleted_at",99)
        messages.put(parent.id,row)
        requested.clear()
        SearchAncestors.load(Model.parse(p,"account")) {id->requested.add(id);parent}
        assertFalse(parent.id in requested)
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
