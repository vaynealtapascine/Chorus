package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LockFrontTest {
    private val names = listOf("fox" to "Foxglove", "ama" to "Amarynthe", "nai" to "Nairon", "vio" to "Violet")
    private val members = names.map { (id, name) -> Member(id, name, null, null, "#C0694E", emptyList(), null, false) }
    private fun layers(vararg entries: Entry) =
        LockFront.layers(Model(members, emptyList(), emptyMap(), entries.toList(), null, emptyList()), "acct")

    @Test fun oneLinePerLayerWithItsMembers() {
        val l = layers(Entry("member", "fox", "front", true), Entry("member", "ama", "front", false),
            Entry("member", "nai", "cocon", false), Entry("member", "vio", "present", false))
        assertEquals("FRONTING Foxglove, Amarynthe · CO-CONSCIOUS Nairon · PRESENT Violet", LockFront.line(l))
    }

    @Test fun oneLayerIsOneSegment() {
        assertEquals("FRONTING Nairon", LockFront.line(layers(Entry("member", "nai", "front", true))))
    }

    @Test fun emptyLayersAreLeftOut() {
        assertEquals("PRESENT Violet", LockFront.line(layers(Entry("member", "vio", "present", false))))
    }

    @Test fun nobodyFrontingIsTheQuietLine() {
        assertEquals("No one fronting", LockFront.line(layers()))
        assertEquals("No one fronting", LockFront.line(listOf(LockFront.Layer("Steering", emptyList()))))
    }

    @Test fun aLongListIsKeptWhole() {
        val many = (1..40).map { "Member $it" }
        val line = LockFront.line(listOf(LockFront.Layer("Steering", many), LockFront.Layer("Nearby", listOf("Violet"))))
        assertEquals("STEERING ${many.joinToString(", ")} · NEARBY Violet", line)
    }
}
