package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WelcomingTest {
    private val link = "https://chorus.example/i/abcDEF123"

    @Test
    fun handlesAreLowercaseWordsWithoutTheAt() {
        assertEquals("the_lanterns2", Welcoming.handle("@The Lanterns!2".replace(" ", "_")))
        assertEquals(32, Welcoming.handle("a".repeat(40)).length)
    }

    @Test
    fun aLinkShowsAsItsCode() {
        assertEquals("abcDEF123", Welcoming.code(link))
        assertNull(Welcoming.code("not a link"))
    }

    @Test
    fun readyOnceTheServerHasLookedAndTheFieldsAreFilled() {
        assertFalse(Welcoming.ready(link, null, "Lanterns", "", "Pixel"))
        assertTrue(Welcoming.ready(link, "system", "Lanterns", "", "Pixel"))
        assertFalse(Welcoming.ready(link, "system", "Lanterns", "l", "Pixel"))
        assertFalse(Welcoming.ready(link, "person", " ", "", "Pixel"))
        assertTrue(Welcoming.ready(link, "device", "", "", "Pixel"))
        assertFalse(Welcoming.ready(link, "device", "", "", ""))
        assertFalse(Welcoming.ready("nope", "device", "", "", "Pixel"))
    }

    @Test
    fun linkingAgainTakesADeviceLinkOfTheSameAccount() {
        assertNull(Welcoming.relinkRefusal("device"))
        assertNull(Welcoming.relinkRefusal("")) // an older server can't say; the account is checked after
        assertTrue(Welcoming.relinkRefusal("system")!!.contains("new account"))
        assertTrue(Welcoming.relinkRefusal("person") != null)
        assertNull(Welcoming.relinkAccountRefusal("acc", "acc"))
        assertTrue(Welcoming.relinkAccountRefusal("acc", "other")!!.contains("different account"))
    }
}
