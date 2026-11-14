package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceFrontsTest {
    private val reply = JSONObject("""{"items":[
        {"account_id":"a1","entries":[
            {"t":"subject","subject_type":"member","subject_id":"m1","level":"cocon","is_primary":false,"name":"Juno","color":null,"glyph":null},
            {"t":"someone","level":"front","name":"Someone","color":null,"glyph":null},
            {"t":"subject","subject_type":"member","subject_id":"m2","level":"front","is_primary":true,"name":"Kairos","color":"#aa5500","glyph":"X"}]},
        {"account_id":"a2","entries":[]},
        {"entries":[{"level":"front","name":"x"}]}]}""")

    @Test fun parsesTheEndpointAndShowsThePrimaryFirstThenByLevelLeavingOutAccountsWithNobody() {
        val items = SpaceFronts.parse(reply)
        assertEquals(listOf("a1", "a2"), items.map { it.accountId })
        val shown = SpaceFronts.byAccount(items)
        assertEquals(setOf("a1"), shown.keys)
        assertEquals("Kairos & Someone & Juno", SpaceFronts.line(shown.getValue("a1")))
        assertEquals("X", SpaceFronts.glyph(shown.getValue("a1").first()))
        assertEquals("S", SpaceFronts.glyph(shown.getValue("a1")[1]))
        assertEquals("#aa5500", shown.getValue("a1").first().color)
    }

    @Test fun nothingComesBackNothingIsShown() {
        assertTrue(SpaceFronts.parse(JSONObject("{}")).isEmpty())
        assertTrue(SpaceFronts.byAccount(emptyList()).isEmpty())
    }

    @Test fun authorsWithoutACardFallBackToTheirAccountsNameAndPictureNeverOurOwnOrKnownOnes() {
        fun msg(id: String, account: String?, vararg authors: String) =
            ChatMessage(id, "c", authors.toList(), "hi", 1, null, "all", emptySet(), account, null, emptyList())
        val accounts = listOf(SpaceAccount("a1", "foxes", "The Foxes", "system", "blob1"), SpaceAccount("me", null, "Us"))
        val out = SpaceFronts.standIns(listOf(msg("1", "a1", "hidden", "known"), msg("2", "me", "ours"), msg("3", "stranger", "who")),
            { id, _ -> id == "known" }, accounts, "me")
        assertEquals(setOf("hidden"), out.keys)
        assertEquals("The Foxes", out.getValue("hidden").name)
        assertEquals("blob1", out.getValue("hidden").avatarBlob)
        assertEquals("a1", out.getValue("hidden").accountId)
    }
}
