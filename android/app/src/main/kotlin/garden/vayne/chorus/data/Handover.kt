package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

/** One checklist line a handover was sent with (`handover.checklist`). */
data class ChecklistDef(val id: String, val text: String)

/** A handover's definition, fixed at sending (D-132): when it reaches the next front, and its checklist. */
data class HandoverDef(val deliver: String, val checklist: List<ChecklistDef> = emptyList()) {
    val nextSwitch: Boolean get() = deliver == "next_switch"

    companion object {
        fun parse(o: JSONObject?): HandoverDef? {
            o ?: return null
            val items = o.optJSONArray("checklist")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    a.optJSONObject(i)?.let { c -> ChecklistDef(c.optString("id"), c.optString("text")) }?.takeIf { it.id.isNotEmpty() }
                }
            }.orEmpty()
            return HandoverDef(o.optString("deliver", "now").ifEmpty { "now" }, items)
        }
    }
}

/** `rows.handover_ack["<message>|<member>"].fields`: a member acknowledged a handover (LWW by the same member). */
data class HandoverAck(val messageId: String, val memberId: String, val ackedAt: Long)

/** `rows.checklist_item_state["<message>|<item>"].fields`: the latest tick of one checklist line. */
data class ChecklistCheck(val messageId: String, val itemId: String, val done: Boolean, val memberId: String)

/** One interval of the Body front's folded history, as core's `handover_view` reads it (layer: `front`, `cocon`, ...). */
data class FrontSlice(val subjectId: String, val layer: String, val startAt: Long, val endAt: Long?)

data class HandoverItem(val id: String, val text: String, val done: Boolean, val by: String?)
data class HandoverOut(val id: String, val deliveredAt: Long?, val acks: List<HandoverAck>, val acked: Boolean, val checklist: List<HandoverItem>)

/** Core's answer for the Handover chat and Home (`handover_view`): delivery, who took it, ticks, what is unread. */
data class HandoverView(val handovers: List<HandoverOut>, val unread: List<String>, val lastFronted: Long?) {
    fun of(id: String): HandoverOut? = handovers.firstOrNull { it.id == id }
    /** `deliver: next_switch` and not yet delivered: "waiting for next switch". */
    fun waiting(id: String): Boolean = of(id)?.deliveredAt == null && of(id) != null
}

/** The Home card (F0.1): the newest unread handover and how many others wait. */
data class HandoverCard(val id: String, val channelId: String, val title: String, val line: String, val more: Int)

/**
 * Handover (D-132, D-162; docs/handoff/d132-clients.md, web's handover.ts): notes from one front to whoever fronts
 * next. Delivery, who took it and the checklist ticks are core's `handover_view`; this builds its input from the
 * model and turns its answer into what the chat and Home draw. The front's history comes in as [FrontSlice]s.
 */
object Handover {
    /** The Body front's whole history, as slices, from the legacy fold. */
    fun slices(model: Model): List<FrontSlice> = model.frontIntervals.map { FrontSlice(it.subjectId, it.level, it.startAt, it.endAt) }

    /** The same from a configured account's effective history (named fronts, D-080): `layer` = its layer id. */
    fun slices(history: NamedHistory): List<FrontSlice> =
        history.intervals.map { FrontSlice(it.presence.subjectId, it.presence.layerId, it.startAt, it.endAt) }

    /** The live handovers in [channelId], oldest first. */
    fun messages(model: Model, channelId: String): List<ChatMessage> = model.handoverMessages.filter { it.channelId == channelId }

    /** Core's view of the Handover channel for [member] (the speaker, for "since X last fronted"). */
    fun view(model: Model, accountId: String, channelId: String, slices: List<FrontSlice>, member: String?): HandoverView? {
        val list = messages(model, channelId)
        val ids = list.mapTo(HashSet()) { it.id }
        val input = JSONObject().put("body", accountId)
            .put("intervals", JSONArray(slices.map { JSONObject().put("subject_id", it.subjectId).put("layer", it.layer)
                .put("start_at", it.startAt).put("end_at", it.endAt ?: JSONObject.NULL) }))
            .put("handovers", JSONArray(list.map { m -> JSONObject().put("id", m.id).put("sent_at", m.occurredAt)
                .put("deliver", m.handover?.deliver ?: "now").put("authors", JSONArray(m.authors))
                .put("checklist", JSONArray(m.handover?.checklist.orEmpty().map { JSONObject().put("id", it.id).put("text", it.text) })) }))
            .put("acks", JSONArray(model.handoverAcks.filter { it.messageId in ids }.map {
                JSONObject().put("message_id", it.messageId).put("member_id", it.memberId).put("acked_at", it.ackedAt) }))
            .put("checks", JSONArray(model.checklistChecks.filter { it.messageId in ids }.map {
                JSONObject().put("message_id", it.messageId).put("item_id", it.itemId).put("done", it.done).put("member_id", it.memberId) }))
            .put("member", member ?: JSONObject.NULL)
        return runCatching { parseView(JSONObject(uniffi.chorus_ffi.handoverView(input.toString()))) }.getOrNull()
    }

    internal fun parseView(o: JSONObject): HandoverView {
        fun strings(a: JSONArray?) = if (a == null) emptyList() else List(a.length()) { a.getString(it) }
        val handovers = o.optJSONArray("handovers")?.let { a ->
            List(a.length()) { i ->
                val h = a.getJSONObject(i)
                val id = h.getString("id")
                HandoverOut(id, if (h.isNull("delivered_at")) null else h.getLong("delivered_at"),
                    h.optJSONArray("acks")?.let { acks -> List(acks.length()) { n -> acks.getJSONObject(n).let { HandoverAck(id, it.getString("member_id"), it.getLong("acked_at")) } } }.orEmpty(),
                    h.optBoolean("acked"),
                    h.optJSONArray("checklist")?.let { items -> List(items.length()) { n -> items.getJSONObject(n).let {
                        HandoverItem(it.getString("id"), it.optString("text"), it.optBoolean("done"), if (it.isNull("by")) null else it.optString("by")) } } }.orEmpty())
            }
        }.orEmpty()
        return HandoverView(handovers, strings(o.optJSONArray("unread")), if (o.isNull("last_fronted")) null else o.optLong("last_fronted"))
    }

    /** "Foxglove acknowledged the handover" lines, at the time of each ack by someone who didn't write it. */
    fun ackLines(view: HandoverView, list: List<ChatMessage>, nameOf: (String) -> String): List<ChatPresentation.FrontEvent> {
        val authors = list.associate { it.id to it.authors }
        return view.handovers.flatMap { h -> h.acks.filter { it.memberId !in authors[h.id].orEmpty() }
            .map { ChatPresentation.FrontEvent(it.ackedAt, "${nameOf(it.memberId)} acknowledged the handover") } }
    }

    /** The "Since Foxglove last fronted" line goes before the first message after [lastFronted]; never at the end. */
    fun sinceLine(lastFronted: Long?, name: String?, newestMessageAt: Long?): ChatPresentation.FrontEvent? {
        if (lastFronted == null || name == null || newestMessageAt == null || lastFronted + 1 > newestMessageAt) return null
        return ChatPresentation.FrontEvent(lastFronted + 1, "Since $name last fronted", divider = true)
    }

    /** The first line of a handover, or how long its checklist is. */
    fun firstLine(m: ChatMessage): String {
        val line = m.text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        val n = m.handover?.checklist?.size ?: 0
        return line ?: if (n > 0) "Checklist · $n ${if (n == 1) "item" else "items"}" else ""
    }

    /** The Home card (F0.1): the newest unread handover and how many others wait. */
    fun card(view: HandoverView?, list: List<ChatMessage>, nameOf: (String) -> String): HandoverCard? {
        val id = view?.unread?.lastOrNull() ?: return null
        val m = list.firstOrNull { it.id == id } ?: return null
        val who = m.authors.map(nameOf).filter { it.isNotEmpty() }.joinToString(" & ")
        return HandoverCard(m.id, m.channelId, if (who.isEmpty()) "Handover" else "Handover from $who", firstLine(m), view.unread.size - 1)
    }

    /** Who acknowledges or ticks: the primary fronter, else the first member of ours (the speaker rule). */
    fun reader(model: Model, accountId: String): Member? = Reply.speaker(model) ?: ChatCompose.members(model, accountId).firstOrNull()

    /** The ops an ack and a tick write. */
    fun ackPayload(id: String, member: String): JSONObject = JSONObject().put("message_id", id).put("member_id", member)
    fun tickPayload(id: String, item: String, done: Boolean, member: String): JSONObject =
        JSONObject().put("message_id", id).put("item_id", item).put("done", done).put("member_id", member)

    /** `handover` for a `message.send` from the Handover composer: every send is one, so Home lists it. Ids `c1`, `c2`... */
    fun sendDef(nextSwitch: Boolean, items: List<String>): JSONObject {
        val list = items.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_ITEMS)
        return JSONObject().put("deliver", if (nextSwitch) "next_switch" else "now").apply {
            if (list.isNotEmpty()) put("checklist", JSONArray(list.mapIndexed { i, t -> JSONObject().put("id", "c${i + 1}").put("text", t.take(MAX_TEXT)) }))
        }
    }

    const val MAX_ITEMS = 30
    const val MAX_TEXT = 200
}
