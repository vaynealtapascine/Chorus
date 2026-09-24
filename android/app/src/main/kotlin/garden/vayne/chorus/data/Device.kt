package garden.vayne.chorus.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.json.JSONObject

/** What enrolment gave this device (API.md §2.1), plus the server it belongs to. */
data class DeviceRecord(
    val base: String,
    val deviceId: String,
    val shortId: String,
    val accountId: String,
    val session: String,
    val expiresAt: Long,
    /** Chorus Home: the server certificate's pin from the invite (Pins.kt), else null. */
    val pin: String? = null,
) {
    fun toJson(): String = JSONObject()
        .put("base", base).put("device_id", deviceId).put("short_id", shortId)
        .put("account_id", accountId).put("session", session).put("expires_at", expiresAt)
        .apply { if (pin != null) put("pin", pin) }
        .toString()

    /** HLC node id: the device's short id read as hex (same as the web client). */
    val node: UInt get() = shortId.toLong(16).toUInt()

    companion object {
        fun fromJson(s: String): DeviceRecord = JSONObject(s).let {
            DeviceRecord(
                it.getString("base"), it.getString("device_id"), it.getString("short_id"),
                it.getString("account_id"), it.getString("session"), it.getLong("expires_at"),
                it.optString("pin").ifEmpty { null },
            )
        }
    }
}

/**
 * The device key: a non-exportable P-256 key in the Android Keystore. Its public half is sent at
 * enrolment; later sessions are renewed by signing a server nonce (API.md §2.2). The server
 * accepts DER signatures, which is what the Keystore produces.
 */
object DeviceKey {
    private const val ALIAS = "chorus-device"

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** A fresh key pair (replacing any old one); returns the public key as base64 SPKI. */
    fun create(): String {
        keyStore().deleteEntry(ALIAS)
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        gen.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        return Base64.encodeToString(gen.generateKeyPair().public.encoded, Base64.NO_WRAP)
    }

    fun sign(message: ByteArray): String {
        val key = keyStore().getKey(ALIAS, null) as PrivateKey
        val s = Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(message) }
        return Base64.encodeToString(s.sign(), Base64.NO_WRAP)
    }
}
