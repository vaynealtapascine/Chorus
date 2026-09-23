package garden.vayne.chorus.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.chorus_ffi.CoreException
import uniffi.chorus_ffi.CoreReplica
import uniffi.chorus_ffi.compose
import uniffi.chorus_ffi.coreVersion
import uniffi.chorus_ffi.feedParse
import uniffi.chorus_ffi.newId
import uniffi.chorus_ffi.parseMarkup

/** The generated UniFFI bindings call into the real Rust core (host build). */
class CoreBridgeTest {
    @Test
    fun versionAndMarkup() {
        assertEquals("0.1.0", coreVersion())
        val r = parseMarkup("hi **there**", "")
        assertTrue(r, r.contains("\"type\":\"bold\""))
    }

    @Test
    fun composeSegments() {
        val out = compose(
            "🌌 go\n🔖=> no",
            """[{"member_id":"sky","sigils":["🌌"]},{"member_id":"mark","sigils":["🔖"]}]""",
            "",
            """["def"]""",
            "",
        )
        assertTrue(out, out.contains(""""authors":["sky","mark"]"""))
    }

    @Test(expected = CoreException::class)
    fun errorsBecomeExceptions() {
        feedParse("kind:banana")
    }

    @Test
    fun pendingCountCrossesTheBridge() {
        val account = newId(1UL, ByteArray(10) { 1 })
        val member = newId(1UL, ByteArray(10) { 2 })
        CoreReplica("dev", 7U).use { replica ->
            assertEquals(0UL, replica.pendingCount())
            replica.create(
                """{"kind":"member.create","scope":"account:$account","entity_id":"$member","payload":{"name":"Kai"}}""",
                """{"now":1000,"tz_offset_min":0}""",
                ByteArray(10) { 4 },
            )
            assertEquals(1UL, replica.pendingCount())
        }
    }
}
