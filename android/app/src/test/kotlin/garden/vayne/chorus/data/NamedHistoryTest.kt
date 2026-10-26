package garden.vayne.chorus.data

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.chorus_ffi.CoreReplica

class NamedHistoryTest {
    @Test fun actualReplicaHistoryKeepsIndependentLayersAndInvalidatesForPastEdits() {
        val root = File(requireNotNull(System.getProperty("chorus.fixtures")))
        val fixture = JSONObject(File(root,"front_runtime/channel_pools.json").readText())
        val ops = fixture.getJSONArray("args").getJSONArray(0)
        val account = ops.getJSONObject(0).getString("entity_id")
        val source = ops.getJSONObject(1).getString("entity_id")
        val target = ops.getJSONObject(1).getString("id")
        CoreReplica.restore("device",1U,"",ops.toString(),"").use { replica ->
            val cache = NamedHistoryCache()
            var ownReads = 0
            var effectiveReads = 0
            fun read(revision: Long, front: String = source): NamedHistory = cache.read(account,revision,front,
                { ownReads++; replica.frontSystemHistory(account) },
                { effectiveReads++; replica.effectiveFrontHistory(account,it) })
            val first = read(1)
            assertEquals("quiet",first.saved.single().resultingFront.single().level)
            assertEquals("quiet",first.intervals.single().presence.layerId)
            assertEquals(first.intervals.single().id,first.intervals.single().toJson().getString("id"))
            assertNull(first.saved.single().note)
            read(1);assertEquals(1,ownReads);assertEquals(1,effectiveReads)
            val body = read(1,account)
            assertEquals(emptyList<SwitchRow>(),body.saved)
            assertEquals("following",body.changes.last().state)
            assertEquals(1,ownReads);assertEquals(2,effectiveReads)
            val created = JSONObject(replica.create("""{"kind":"front.change","scope":"account:$account","entity_id":"$source","payload":{"action":"amend","target_op_id":"$target","note":"Past correction"}}""",
                """{"now":5000}""",ByteArray(10) { 44 })).getJSONObject("op")
            assertNull(read(1).saved.single().note)
            val changed = read(2)
            assertEquals("Past correction",changed.saved.single().note)
            assertEquals(first.intervals,changed.intervals)
            assertEquals(2,ownReads);assertEquals(3,effectiveReads)
            val remaining = org.json.JSONArray(ops.toString()).apply { remove(1); put(created) }
            CoreReplica.restore("device",1U,"",remaining.toString(),"").use { reopened ->
                val removed = cache.read(account,3,source,{reopened.frontSystemHistory(account)},
                    {reopened.effectiveFrontHistory(account,it)})
                assertEquals(emptyList<SwitchRow>(),removed.saved)
                assertEquals(emptyList<NamedFrontInterval>(),removed.intervals)
            }
        }
    }
}
