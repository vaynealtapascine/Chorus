package garden.vayne.chorus.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.chorus_ffi.CoreException
import uniffi.chorus_ffi.compose
import uniffi.chorus_ffi.coreVersion
import uniffi.chorus_ffi.feedParse
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
}
