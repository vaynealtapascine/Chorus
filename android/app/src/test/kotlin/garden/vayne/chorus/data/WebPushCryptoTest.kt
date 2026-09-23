package garden.vayne.chorus.data

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** RFC 8291 §5: the receiver decrypts the RFC's published message with the RFC's keys. */
class WebPushCryptoTest {
    private fun d(s: String): ByteArray = Base64.getUrlDecoder().decode(s.replace(" ", ""))

    private val uaPublic = d("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcx aOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4")
    private val uaPrivate = WebPushCrypto.privateFromScalar(d("q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94"))
    private val auth = d("BTBZMqHH6r4Tts7J_aSIgg")
    private val message = d(
        "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml" +
            "mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT" +
            "pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN",
    )

    @Test
    fun decryptsTheRfcExample() {
        val plain = WebPushCrypto.decrypt(uaPrivate, uaPublic, auth, message)
        assertArrayEquals("When I grow up, I want to be a watermelon".toByteArray(), plain)
    }

    @Test
    fun tamperedOrForeignMessagesAreRejected() {
        val bad = message.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(WebPushCrypto.decrypt(uaPrivate, uaPublic, auth, bad))
        val other = WebPushCrypto.generate()
        assertNull(WebPushCrypto.decrypt(WebPushCrypto.privateFromPkcs8(other.privatePkcs8), other.publicRaw, other.auth, message))
    }

    @Test
    fun generatedKeysRoundTripTheirPublicPoint() {
        val k = WebPushCrypto.generate()
        assertArrayEquals(k.publicRaw, WebPushCrypto.raw(WebPushCrypto.publicFromRaw(k.publicRaw)))
    }
}
