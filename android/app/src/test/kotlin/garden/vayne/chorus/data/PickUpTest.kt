package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.chorus_ffi.CoreReplica

/**
 * Linking a signed-out phone again to the account it kept (D-153): the same store opens under a
 * new device id and HLC node. What was written before the sign-out and never sent goes out then,
 * and nothing in the replica is tied to the old device (ops are stamped by the server on ack).
 */
class PickUpTest {
    private val account = uniffi.chorus_ffi.newId(1_000UL, ByteArray(10) { 7 })
    private val member = uniffi.chorus_ffi.newId(1_000UL, ByteArray(10) { 8 })
    private val welcome = JSONObject().put("t", "welcome").put("server_time", 5_000).put("epoch", "e1")
        .put("account_id", account).put("offset_ms", 0).put("scopes", JSONArray(listOf("account:$account")))
        .put("max_seq", JSONObject()).put("reconcile", false).put("core_min", "0.0.0").toString()

    private fun pushed(frames: JSONArray): List<JSONObject> = (0 until frames.length()).map { frames.getJSONObject(it) }
        .filter { it.optString("t") == "push" }.flatMap { f -> f.getJSONArray("ops").let { a -> (0 until a.length()).map(a::getJSONObject) } }

    /** Write one op on the old device and persist it as Store.save does, never connecting. */
    private fun keptCopy(): Triple<String, String, Pair<String, String>> =
        CoreReplica.restore("dev-old", 1U, "", "", "").use { old ->
            val op = JSONObject(old.create("""{"kind":"member.create","scope":"account:$account","entity_id":"$member","payload":{"name":"Kai"}}""",
                """{"now":1000}""", ByteArray(10) { 1 })).getJSONObject("op")
            val changes = JSONObject(old.takeChanges())
            val meta = if (changes.has("meta") && !changes.isNull("meta")) changes.get("meta").toString() else ""
            Triple(op.getString("id"), changes.getJSONArray("ops").toString(), meta to changes.optString("hlc_last"))
        }

    @Test fun anOpWrittenBeforeTheSignOutAndNeverSentGoesOutUnderTheNewDevice() {
        val (opId, ops, kept) = keptCopy()
        val (meta, hlc) = kept
        CoreReplica.restore("dev-new", 2U, meta, ops, hlc).use { fresh ->
            val hello = JSONObject(fresh.connect("""{"wall":5000}""", "new-session"))
            assertEquals("dev-new", hello.getString("device_id"))
            assertEquals(1, hello.getInt("outbox"))
            val out = pushed(JSONArray(fresh.onFrame(welcome, 5_000)))
            assertEquals(listOf(opId), out.map { it.getString("id") })
            // unsent: no device stamped on it, so the server stamps the new one
            assertTrue(out.single().isNull("device_id") || !out.single().has("device_id"))
            // and what is written now comes after it on the new node's clock
            val next = JSONObject(fresh.create("""{"kind":"member.set","scope":"account:$account","entity_id":"$member","payload":{"name":"Kai R"}}""",
                """{"now":900}""", ByteArray(10) { 2 })).getJSONObject("op")
            assertTrue(next.getString("hlc") > out.single().getString("hlc"))
        }
    }

    @Test fun metadataThatLagsBehindTheOpsStillSendsThem() {
        // the metadata saved before the op (or lost): relist_unsent puts it back in the outbox
        val (opId, ops, kept) = keptCopy()
        CoreReplica.restore("dev-new", 2U, "", ops, kept.second).use { fresh ->
            fresh.connect("""{"wall":5000}""", "new-session")
            assertEquals(listOf(opId), pushed(JSONArray(fresh.onFrame(welcome, 5_000))).map { it.getString("id") })
        }
    }
}
