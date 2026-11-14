package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Ops for tests of Handover and Notes (D-132, D-162), projected by the real core (never hand-written
 * rows: NOTES, Fixtures); the Kotlin twin of web's `testing/d132-ops.ts`. [build] numbers the ops, gives
 * each a clock and returns the projection; [model] parses it as [ME].
 */
object D132Ops {
    const val T0 = 1_800_000_000_000L

    /** A valid id (the core checks them): `u(5)` is always the same one. */
    fun u(n: Int) = "0192f8c2-0000-7000-8000-${n.toString(16).padStart(12, '0')}"

    val ME = u(0x001)
    val OTHER = u(0x002)
    val ME_MEMBER = u(0x101); val FOX = u(0x102); val AMY = u(0x103); val NAI = u(0x104)
    val HOME = u(0x201); val LANTERNS = u(0x202); val THEIRS = u(0x203)
    val GENERAL = u(0x301); val HANDOVER = u(0x302); val NOTES = u(0x303); val MINE = u(0x304); val THEIR_NOTES = u(0x305)

    class Spec(val kind: String, val scope: String, val entity: String? = null, val payload: JSONObject = JSONObject(),
        val at: Long? = null, val account: String = ME, val member: String? = null)

    private val home = "space:$HOME"

    private fun obj(vararg p: Pair<String, Any?>) = JSONObject().also { o -> p.forEach { o.put(it.first, it.second ?: JSONObject.NULL) } }

    fun ops(specs: List<Spec>): JSONArray = JSONArray().also { out ->
        specs.forEachIndexed { i, s ->
            val at = s.at ?: (T0 + i * 1000L)
            out.put(JSONObject().put("id", u(0x1000 + i)).put("scope", s.scope).put("kind", s.kind)
                .put("entity_id", s.entity ?: JSONObject.NULL).put("payload", s.payload).put("v", 1)
                .put("device_at", at).put("occurred_at", at).put("tz_offset_min", 0).put("account_id", s.account).put("device_id", "dev")
                .put("member_id", s.member ?: JSONObject.NULL).put("seq", i + 1)
                .put("hlc", "${at.toString(16).padStart(12, '0')}-${i.toString(16).padStart(4, '0')}-00000001"))
        }
    }

    fun build(specs: List<Spec>): JSONObject = JSONObject(uniffi.chorus_ffi.project(ops(specs).toString()))

    fun model(specs: List<Spec>, account: String = ME): Model = Model.parse(build(specs), account)

    /** A person's account: its one member (D-003), its internal space "Notes" holding only the Notes channel the
     * server makes (D-162), and a shared space with one chat. */
    fun person(): List<Spec> = listOf(
        Spec("member.create", "account:$ME", ME_MEMBER, obj("name" to "Violet", "is_self" to true, "color" to "#B99CE8")),
        Spec("space.create", home, HOME, obj("kind" to "internal", "name" to "Notes")),
        Spec("channel.create", home, NOTES, obj("space_id" to HOME, "kind" to "text", "name" to "Notes to self", "special" to "notes")),
        Spec("space.create", "space:$LANTERNS", LANTERNS, obj("kind" to "shared", "name" to "The Lanterns")),
        Spec("channel.create", "space:$LANTERNS", GENERAL, obj("space_id" to LANTERNS, "kind" to "text", "name" to "general")),
    )

    /** A system's account: members Foxglove and Amarynthe, its internal space with General, Handover and Notes to ourselves. */
    fun system(): List<Spec> = listOf(
        Spec("member.create", "account:$ME", FOX, obj("name" to "Foxglove", "color" to "#B99CE8")),
        Spec("member.create", "account:$ME", AMY, obj("name" to "Amarynthe", "color" to "#F08C7F")),
        Spec("space.create", home, HOME, obj("kind" to "internal", "name" to "The Lanterns")),
        Spec("channel.create", home, GENERAL, obj("space_id" to HOME, "kind" to "text", "name" to "general")),
        Spec("channel.create", home, HANDOVER, obj("space_id" to HOME, "kind" to "text", "name" to "Handover", "special" to "handover")),
        Spec("channel.create", home, NOTES, obj("space_id" to HOME, "kind" to "text", "name" to "Notes to ourselves", "special" to "notes")),
    )

    fun send(channel: String, id: String, text: String, authors: List<String>, extra: JSONObject = JSONObject(), at: Long? = null) =
        Spec("message.send", home, id, JSONObject().put("channel_id", channel).put("authors", JSONArray(authors)).put("text", text)
            .put("entities", JSONArray()).also { p -> extra.keys().forEach { k -> p.put(k, extra.get(k)) } }, at)
}
