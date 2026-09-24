package io.github.bitjacker.scrigno.core.remote

import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Locale

/** Human comparable fingerprints, in the same format shown by `ssh-keygen -l` and browsers. */
object Fingerprints {

    /** SSH host key fingerprint, e.g. `SHA256:nThbg6kXUpJWGl7E1IGOCspRomTxdCARLviKw6E5SY8`. */
    fun ssh(keyBlob: ByteArray): String =
        "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(sha256(keyBlob))

    /** TLS certificate fingerprint, e.g. `SHA256:AB:CD:...`. */
    fun certificate(certificate: X509Certificate): String =
        "SHA256:" + sha256(certificate.encoded).joinToString(":") {
            String.format(Locale.ROOT, "%02X", it.toInt() and 0xFF)
        }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
