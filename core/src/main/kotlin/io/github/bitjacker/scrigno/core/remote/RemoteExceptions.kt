package io.github.bitjacker.scrigno.core.remote

import java.io.IOException

/** Base class for errors reported by the server or the connection to it. */
open class RemoteException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Wrong user name or password, or the user is not allowed to access the folder. */
class AuthenticationException(message: String, cause: Throwable? = null) : RemoteException(message, cause)

/** The requested file or folder does not exist on the server. */
class RemoteNotFoundException(val path: String, cause: Throwable? = null) :
    RemoteException("Not found on the server: $path", cause)

/**
 * The server presented a different SSH host key or TLS certificate than the one pinned on the first
 * connection. It could be a reinstalled server... or somebody in the middle.
 */
class ServerIdentityChangedException(val expected: String, val actual: String, cause: Throwable? = null) :
    RemoteException("The identity of the server changed. Expected $expected, got $actual", cause)

/** The TLS certificate is not trusted (for example self-signed) and self-signed certificates are not allowed. */
class UntrustedCertificateException(val fingerprint: String, cause: Throwable? = null) :
    RemoteException("The server certificate is not trusted ($fingerprint)", cause)

/** The local file (on the phone) could not be read. The server is fine. */
class LocalReadException(val fileName: String, cause: Throwable? = null) :
    IOException("Cannot read $fileName on this device", cause)
