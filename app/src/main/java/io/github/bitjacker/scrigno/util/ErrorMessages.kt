package io.github.bitjacker.scrigno.util

import android.content.Context
import io.github.bitjacker.scrigno.R
import io.github.bitjacker.scrigno.core.remote.AuthenticationException
import io.github.bitjacker.scrigno.core.remote.LocalReadException
import io.github.bitjacker.scrigno.core.remote.RemoteNotFoundException
import io.github.bitjacker.scrigno.core.remote.ServerIdentityChangedException
import io.github.bitjacker.scrigno.core.remote.UntrustedCertificateException
import io.github.bitjacker.scrigno.data.remote.OtherServerException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Turns technical errors into sentences a person can act on. */
object ErrorMessages {

    fun describe(context: Context, error: Throwable): String {
        for (cause in generateSequence(error) { it.cause }.take(8)) {
            known(context, cause)?.let { return it }
        }
        val message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        return context.getString(R.string.error_generic, message)
    }

    private fun known(context: Context, e: Throwable): String? = when (e) {
        is AuthenticationException -> context.getString(R.string.error_auth)
        is ServerIdentityChangedException -> context.getString(R.string.error_identity_changed, e.expected, e.actual)
        is UntrustedCertificateException -> context.getString(R.string.error_untrusted_certificate, e.fingerprint)
        is RemoteNotFoundException -> context.getString(R.string.error_not_found)
        is LocalReadException -> context.getString(R.string.error_local_read, e.fileName)
        is OtherServerException -> context.getString(R.string.error_other_server)
        is UnknownHostException -> context.getString(R.string.error_unknown_host)
        is ConnectException, is NoRouteToHostException -> context.getString(R.string.error_unreachable)
        is SocketTimeoutException -> context.getString(R.string.error_timeout)
        is SecurityException -> context.getString(R.string.error_no_permission)
        else -> null
    }
}
