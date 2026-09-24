package io.github.bitjacker.scrigno.core.remote

import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Trusts certificates signed by a known authority (as the system does) and, when the user allows it,
 * self-signed certificates on a "trust on first use" basis: the first certificate seen is pinned by
 * its fingerprint and any later change is refused.
 *
 * @param pinned fingerprint accepted on a previous connection, empty if none.
 */
class PinningTrustManager(
    private val pinned: String,
    private val allowSelfSigned: Boolean,
) : X509TrustManager {

    private val system: X509TrustManager = systemTrustManager()

    /** Fingerprint of the last certificate presented by the server. */
    @Volatile
    var observedFingerprint: String? = null
        private set

    /** Whether the last certificate was accepted because a known authority signed it. */
    @Volatile
    var trustedBySystem: Boolean = false
        private set

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        system.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("The server sent no certificate")
        val fingerprint = Fingerprints.certificate(chain[0])
        observedFingerprint = fingerprint
        try {
            system.checkServerTrusted(chain, authType)
            trustedBySystem = true
        } catch (e: CertificateException) {
            trustedBySystem = false
            if (!allowSelfSigned) throw e
            if (pinned.isNotEmpty() && pinned != fingerprint) {
                throw CertificateException("Certificate changed: expected $pinned, got $fingerprint", e)
            }
            // Trust on first use, or same certificate as last time.
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

    fun sslContext(): SSLContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(this@PinningTrustManager), null) }

    /**
     * Certificates signed by an authority must match the host name. A pinned self-signed
     * certificate is identified by its fingerprint instead (home servers are often reached by IP).
     */
    fun hostnameVerifier(default: HostnameVerifier): HostnameVerifier = HostnameVerifier { host, session ->
        if (trustedBySystem) default.verify(host, session) else allowSelfSigned && observedFingerprint != null
    }

    /** Converts a TLS failure into an error that tells the user what happened. */
    fun explain(error: Throwable): RemoteException? {
        val fingerprint = observedFingerprint ?: return null
        if (trustedBySystem) return null
        return if (allowSelfSigned && pinned.isNotEmpty() && pinned != fingerprint) {
            ServerIdentityChangedException(pinned, fingerprint, error)
        } else if (!allowSelfSigned) {
            UntrustedCertificateException(fingerprint, error)
        } else {
            null
        }
    }

    companion object {
        fun systemTrustManager(): X509TrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }
}
