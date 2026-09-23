package garden.vayne.chorus.data

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The receiving side of RFC 8291 Web Push encryption (`aes128gcm`), which the server uses for
 * every push (crates/chorus-server/src/push.rs). Plain JVM crypto, no Android APIs, so it is
 * unit-tested against the RFC's own example.
 */
object WebPushCrypto {
    /** This device's push keys: P-256 pair + 16-byte auth secret. */
    class Keys(val privatePkcs8: ByteArray, val publicRaw: ByteArray, val auth: ByteArray)

    private val params: ECParameterSpec by lazy {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"))
        (g.generateKeyPair().public as ECPublicKey).params
    }

    fun generate(random: java.security.SecureRandom = java.security.SecureRandom()): Keys {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"), random)
        val kp = g.generateKeyPair()
        val auth = ByteArray(16).also { random.nextBytes(it) }
        return Keys(kp.private.encoded, raw(kp.public as ECPublicKey), auth)
    }

    private fun fixed32(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    /** Uncompressed SEC1 point: 0x04 || X || Y. */
    fun raw(k: ECPublicKey): ByteArray = byteArrayOf(4) + fixed32(k.w.affineX) + fixed32(k.w.affineY)

    fun publicFromRaw(raw: ByteArray): ECPublicKey {
        require(raw.size == 65 && raw[0] == 4.toByte()) { "not an uncompressed P-256 point" }
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params)) as ECPublicKey
    }

    fun privateFromScalar(d: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, d), params))

    fun privateFromPkcs8(bytes: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    /** HKDF-SHA256 (extract + expand), for outputs up to 32 bytes. */
    private fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, len: Int): ByteArray {
        val prk = hmac(salt, ikm)
        return hmac(prk, info + byteArrayOf(1)).copyOf(len)
    }

    /** Decrypt one `aes128gcm` push body. Returns null if it isn't for us or was tampered with. */
    fun decrypt(private: PrivateKey, publicRaw: ByteArray, auth: ByteArray, msg: ByteArray): ByteArray? = runCatching {
        val salt = msg.copyOfRange(0, 16)
        val idLen = msg[20].toInt() and 0xff
        val asPublic = msg.copyOfRange(21, 21 + idLen)
        val body = msg.copyOfRange(21 + idLen, msg.size)
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(private)
            doPhase(publicFromRaw(asPublic), true)
            generateSecret()
        }
        val keyInfo = "WebPush: info".toByteArray() + byteArrayOf(0) + publicRaw + asPublic
        val ikm = hkdf(auth, shared, keyInfo, 32)
        val cek = hkdf(salt, ikm, "Content-Encoding: aes128gcm".toByteArray() + byteArrayOf(0), 16)
        val nonce = hkdf(salt, ikm, "Content-Encoding: nonce".toByteArray() + byteArrayOf(0), 12)
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
            doFinal(body)
        }
        // strip padding: trailing zeros, then the 0x02 last-record delimiter
        var end = plain.size
        while (end > 0 && plain[end - 1] == 0.toByte()) end--
        check(end > 0 && plain[end - 1] == 2.toByte()) { "bad padding" }
        plain.copyOf(end - 1)
    }.getOrNull()
}
