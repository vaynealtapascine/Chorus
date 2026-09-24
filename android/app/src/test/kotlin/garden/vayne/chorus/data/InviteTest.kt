package garden.vayne.chorus.data

import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InviteTest {
    @Test
    fun ordinaryInvitesKeepTheirServer() {
        assertEquals(Invite("https://chorus.vayne.garden", "7Q2M-KX4P", null), Api.parseInvite("https://chorus.vayne.garden/i/7Q2M-KX4P"))
        assertEquals(Invite("http://127.0.0.1:5251", "abc_d", null), Api.parseInvite("  http://127.0.0.1:5251/i/abc_d "))
    }

    @Test
    fun homeInvitesAreHttpsWithTheirPin() {
        val i = Api.parseInvite("chorus://192.168.1.20:5251/i/ABCD#pin=sha256/Xy-z_0")
        assertEquals(Invite("https://192.168.1.20:5251", "ABCD", "sha256/Xy-z_0"), i)
    }

    @Test
    fun otherLinksAreNotInvites() {
        assertNull(Api.parseInvite("https://example.com/about"))
        assertNull(Api.parseInvite("ftp://host/i/ABCD"))
    }

    /** The server computes the same pin for the same certificate (fixtures/home-pin.json, tls.rs). */
    @Test
    fun pinsMatchTheServer() {
        val f = JSONObject(File("../../fixtures/home-pin.json").readText())
        val der = java.util.Base64.getDecoder().decode(f.getString("cert_der_b64"))
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
        assertEquals(f.getString("pin"), Pins.pinOf(cert))
    }
}
