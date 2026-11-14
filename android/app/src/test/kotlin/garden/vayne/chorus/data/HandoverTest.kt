package garden.vayne.chorus.data

import garden.vayne.chorus.data.D132Ops.AMY
import garden.vayne.chorus.data.D132Ops.FOX
import garden.vayne.chorus.data.D132Ops.HANDOVER
import garden.vayne.chorus.data.D132Ops.HOME
import garden.vayne.chorus.data.D132Ops.ME
import garden.vayne.chorus.data.D132Ops.Spec
import garden.vayne.chorus.data.D132Ops.T0
import garden.vayne.chorus.data.D132Ops.u
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Handover as core's `handover_view` answers it over a model built from ops through the real projection (D-132). */
class HandoverTest {
    private val h1 = u(0x501); private val h2 = u(0x502); private val plain = u(0x503)
    private val home = "space:$HOME"
    private fun name(id: String) = mapOf(FOX to "Foxglove", AMY to "Amarynthe")[id].orEmpty()

    // Amarynthe fronts from the start; Foxglove takes over at T0 + 60 s
    private val before = listOf(FrontSlice(AMY, "front", T0 - 1000, null))
    private val after = listOf(FrontSlice(AMY, "front", T0 - 1000, T0 + 60_000), FrontSlice(FOX, "front", T0 + 60_000, null))

    private fun def(deliver: String, vararg items: Pair<String, String>) = JSONObject().put("handover",
        JSONObject().put("deliver", deliver).put("checklist", JSONArray(items.map { JSONObject().put("id", it.first).put("text", it.second) })))

    private fun base(): List<Spec> = D132Ops.system() + listOf(
        D132Ops.send(HANDOVER, plain, "Evening: dishes done", listOf(AMY), at = T0 - 5000),
        D132Ops.send(HANDOVER, h1, "Handover for whoever is next", listOf(AMY), def("next_switch", "a" to "Keys", "b" to "Lunch"), T0 + 1000),
        D132Ops.send(HANDOVER, h2, "", listOf(AMY), def("now", "a" to "Pills"), T0 + 2000),
    )

    private fun view(specs: List<Spec>, slices: List<FrontSlice>, member: String?) =
        Handover.view(D132Ops.model(specs), ME, HANDOVER, slices, member)!!

    @Test fun readsHandoversOffTheProjectedMessagesAndLeavesPlainOnesAlone() {
        val m = D132Ops.model(base())
        assertEquals(listOf(h1, h2), Handover.messages(m, HANDOVER).map { it.id })
        assertNull(m.chatMessages.getValue(HANDOVER).first { it.id == plain }.handover)
        assertEquals(HandoverDef("next_switch", listOf(ChecklistDef("a", "Keys"), ChecklistDef("b", "Lunch"))), Handover.messages(m, HANDOVER)[0].handover)
    }

    @Test fun waitsForTheNextSwitchThenDeliversItInOrder() {
        val wait = view(base(), before, FOX)
        assertTrue(wait.waiting(h1)); assertFalse(wait.waiting(h2))
        assertEquals(listOf(h2), wait.unread)
        val done = view(base(), after, FOX)
        assertFalse(done.waiting(h1))
        assertEquals(2, done.unread.size)
    }

    @Test fun oneAckByAnotherMemberClosesAHandoverAndSaysSoOnce() {
        val specs = base() + listOf(
            Spec("handover.ack", home, h1, Handover.ackPayload(h1, AMY), at = T0 + 90_000),
            Spec("handover.ack", home, h2, Handover.ackPayload(h2, FOX), at = T0 + 95_000))
        val m = D132Ops.model(specs)
        val v = Handover.view(m, ME, HANDOVER, after, FOX)!!
        // the author's own ack doesn't close h1; Foxglove's closes h2
        assertEquals(listOf(h1), v.unread)
        val lines = Handover.ackLines(v, m.handoverMessages, ::name)
        assertEquals(listOf(ChatPresentation.FrontEvent(T0 + 95_000, "Foxglove acknowledged the handover")), lines)
    }

    @Test fun ticksChecklistItemsAsTheSpeakerAndTheLastTickWins() {
        val specs = base() + listOf(
            Spec("checklist.set", home, h1, Handover.tickPayload(h1, "a", true, FOX), at = T0 + 70_000),
            Spec("checklist.set", home, h1, Handover.tickPayload(h1, "b", true, FOX), at = T0 + 80_000),
            Spec("checklist.set", home, h1, Handover.tickPayload(h1, "b", false, FOX), at = T0 + 90_000))
        val h = view(specs, after, FOX).of(h1)!!
        assertEquals(listOf("a" to true, "b" to false), h.checklist.map { it.id to it.done })
        assertEquals(FOX, h.checklist[0].by)
    }

    @Test fun knowsWhenAMemberLastStoppedFrontingForTheDivider() {
        assertEquals(T0 + 60_000, view(base(), after, AMY).lastFronted)
        assertNull(view(base(), after, FOX).lastFronted)
        val line = Handover.sinceLine(T0 + 60_000, "Amarynthe", T0 + 100_000)!!
        assertEquals(ChatPresentation.FrontEvent(T0 + 60_001, "Since Amarynthe last fronted", divider = true), line)
        // never at the end of the chat
        assertNull(Handover.sinceLine(T0 + 60_000, "Amarynthe", T0 + 30_000))
        assertNull(Handover.sinceLine(null, "Amarynthe", T0))
    }

    @Test fun makesTheHomeCardFromTheNewestUnreadAndCountsTheRest() {
        val m = D132Ops.model(base())
        val v = Handover.view(m, ME, HANDOVER, after, FOX)!!
        val card = Handover.card(v, m.handoverMessages, ::name)!!
        assertEquals(v.unread.last(), card.id)
        assertEquals("Handover from Amarynthe", card.title)
        assertEquals(1, card.more)
        assertNull(Handover.card(HandoverView(emptyList(), emptyList(), null), m.handoverMessages, ::name))
        assertEquals("Checklist · 1 item", Handover.firstLine(m.handoverMessages[1]))
        assertEquals("Handover for whoever is next", Handover.firstLine(m.handoverMessages[0]))
    }

    @Test fun writesWhatTheComposerSaysEverySendAHandoverItemsNumbered() {
        assertEquals("""{"deliver":"now"}""", Handover.sendDef(false, emptyList()).toString())
        val d = Handover.sendDef(true, listOf(" Keys ", "", "Lunch"))
        assertEquals("next_switch", d.getString("deliver"))
        assertEquals(listOf("c1" to "Keys", "c2" to "Lunch"), (0 until 2).map { d.getJSONArray("checklist").getJSONObject(it).let { o -> o.getString("id") to o.getString("text") } })
        assertEquals(30, Handover.sendDef(false, List(40) { "i$it" }).getJSONArray("checklist").length())
    }

    @Test fun aChecklistOnlySendIsAcceptedByTheCoreWithEmptyText() {
        val m = D132Ops.model(D132Ops.system())
        val payload = ChatCompose.payload(m, HANDOVER, FOX, "", "", "all", emptySet(), "internal", accountId = ME, allowEmpty = true)
        payload.put("handover", Handover.sendDef(false, listOf("Keys")))
        val specs = D132Ops.system() + Spec("message.send", home, u(0x504), payload)
        val sent = D132Ops.model(specs)
        assertEquals("", Handover.messages(sent, HANDOVER).single().text)
        assertNotNull(Handover.messages(sent, HANDOVER).single().handover)
    }
}
