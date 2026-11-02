package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChorusLinksTest {
    private val home = "https://chorus.example.org"
    private val post = "01a19868-18cf-7213-980c-e6f963e216e6"
    private val msg = "01a19952-112e-7059-8b64-eaa54a247db5"
    private val channel = "01a195d4-06da-7601-9359-eb1d2c25c114"

    @Test fun sharesAShortLinkOnThisServer() {
        assertEquals("$home/post/$post", ChorusLinks.linkTo(home, "post", post))
        assertEquals("$home/message/$msg", ChorusLinks.linkTo("$home/", "message", msg))
    }

    @Test fun readsItsOwnLinksAndTheWebAppsAddressesOnThisServerOnly() {
        assertEquals(ChorusLinks.Target("post", post), ChorusLinks.parse("$home/post/$post", home))
        assertEquals(ChorusLinks.Target("message", msg), ChorusLinks.parse("$home/message/${msg.uppercase()}", home))
        assertEquals(ChorusLinks.Target("post", post), ChorusLinks.parse("$home/#/journal/post/$post", home))
        assertEquals(ChorusLinks.Target("message", msg), ChorusLinks.parse("$home/#/chat/$channel/$msg", home))
        // the default port is the same server; another host or port isn't
        assertEquals(ChorusLinks.Target("post", post), ChorusLinks.parse("https://chorus.example.org:443/post/$post", home))
        assertNull(ChorusLinks.parse("https://elsewhere.example/post/$post", home))
        assertNull(ChorusLinks.parse("http://chorus.example.org/post/$post", home))
        assertNull(ChorusLinks.parse("$home/post/not-an-id", home))
        assertNull(ChorusLinks.parse("$home/#/chat/$channel", home))
        assertNull(ChorusLinks.parse("not a url", home))
    }

    @Test fun findsEachLinkedThingOnceInOrder() {
        val text = "see $home/post/$post and this and $home/post/$post again https://x.org"
        val link = "$home/post/$post"
        val entities = JSONArray()
            .put(JSONObject().put("type", "url").put("offset", text.indexOf(home)).put("length", link.length))
            .put(JSONObject().put("type", "text_link").put("offset", text.indexOf("this")).put("length", 4).put("url", "$home/message/$msg"))
            .put(JSONObject().put("type", "url").put("offset", text.lastIndexOf(link)).put("length", link.length))
            .put(JSONObject().put("type", "url").put("offset", text.indexOf("https://x.org")).put("length", 13))
            .put(JSONObject().put("type", "url").put("offset", 500).put("length", 9))
        assertEquals(listOf(ChorusLinks.Target("post", post), ChorusLinks.Target("message", msg)),
            ChorusLinks.linksIn(text, entities.toString(), home))
        assertEquals(emptyList<ChorusLinks.Target>(), ChorusLinks.linksIn(text, "not json", home))
    }
}
