package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceLinksTest {
    @Test
    fun readsTheServersQrSvg() {
        // the shape qr.rs writes: a white square, then one path of horizontal runs
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 29 29" shape-rendering="crispEdges"><rect width="29" height="29" fill="#fff"/><path d="M4,4h7v1h-7zM13,4h1v1h-1zM4,5h1v1h-1z" fill="#000"/></svg>"""
        val qr = DeviceLinks.parseQr(svg)!!
        assertEquals(29, qr.size)
        assertEquals(listOf(Triple(4, 4, 7), Triple(13, 4, 1), Triple(4, 5, 1)), qr.runs)
        assertNull(DeviceLinks.parseQr(null))
        assertNull(DeviceLinks.parseQr("<svg/>"))
        val invite = DeviceLinks.invite(JSONObject().put("url", "https://h/i/abc").put("qr_svg", JSONObject.NULL))
        assertEquals("https://h/i/abc" to null, invite.url to invite.qr)
    }

    @Test
    fun listsThisDeviceFirst() {
        val me = JSONObject("""{"devices":[
            {"id":"a","name":"Laptop","platform":"web","created_at":1,"last_seen_at":500},
            {"id":"b","name":"Galaxy A56","platform":"android","created_at":2,"last_seen_at":null},
            {"id":"c","name":"","platform":"android","created_at":3,"last_seen_at":0}]}""")
        val rows = DeviceLinks.devices(me, "b")
        assertEquals(listOf("b", "a", "c"), rows.map { it.id })
        assertEquals(listOf(true, false, false), rows.map { it.current })
        assertEquals(listOf(null, 500L, null), rows.map { it.lastSeen })
        assertEquals("android", rows[2].name)
        assertEquals(emptyList<LinkedDevice>(), DeviceLinks.devices(JSONObject("""{"devices":null}"""), "b"))
    }

    @Test
    fun namesThisPhone() {
        assertEquals("Samsung SM-A566B", DeviceLinks.defaultName("samsung", "SM-A566B"))
        assertEquals("Google Pixel 8", DeviceLinks.defaultName("Google", "Pixel 8"))
        assertEquals("Google Pixel 8", DeviceLinks.defaultName("Google", "Google Pixel 8"))
        assertEquals("Android", DeviceLinks.defaultName(null, null))
    }
}
