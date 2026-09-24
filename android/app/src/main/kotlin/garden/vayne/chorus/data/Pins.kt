package garden.vayne.chorus.data

import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import javax.net.ssl.HttpsURLConnection

/**
 * Chorus Home servers (D-071, docs/HOME.md): on the home wifi the server has a self-signed
 * certificate, and the invite carries its SHA-256 (`#pin=sha256/<b64url>`). A certificate whose
 * hash is pinned here is trusted for any host (only that server holds its private key); every
 * other certificate goes through the platform's normal CA and hostname checks, unchanged.
 */
object Pins {
    private val pins = ConcurrentHashMap.newKeySet<String>()

    fun trust(pin: String?) {
        if (pin != null && pin.startsWith("sha256/")) pins.add(pin)
    }

    fun pinOf(cert: X509Certificate): String =
        "sha256/" + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.encoded))

    private fun pinned(chain: Array<out X509Certificate>?): Boolean =
        !chain.isNullOrEmpty() && pinOf(chain[0]) in pins

    private val platform: X509TrustManager by lazy {
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        f.init(null as KeyStore?)
        f.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** Pinned leaf certificates pass; anything else is the platform's decision. */
    val trustManager: X509TrustManager = object : X509ExtendedTrustManager() {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (!pinned(chain)) platform.checkServerTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
            if (pinned(chain)) return
            val ext = platform as? X509ExtendedTrustManager
            if (ext != null) ext.checkServerTrusted(chain, authType, socket) else platform.checkServerTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
            if (pinned(chain)) return
            val ext = platform as? X509ExtendedTrustManager
            if (ext != null) ext.checkServerTrusted(chain, authType, engine) else platform.checkServerTrusted(chain, authType)
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("no client certificates")

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
            throw CertificateException("no client certificates")

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
            throw CertificateException("no client certificates")

        override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers
    }

    /** A pinned certificate names no LAN address, so its host isn't checked; others are, as usual. */
    val hostnameVerifier = HostnameVerifier { host: String, session: SSLSession ->
        val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
        if (leaf != null && pinOf(leaf) in pins) true else HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)
    }
}

/** A parsed invite: where the server is, the code, and (Chorus Home) the certificate to pin. */
data class Invite(val base: String, val code: String, val pin: String?)
