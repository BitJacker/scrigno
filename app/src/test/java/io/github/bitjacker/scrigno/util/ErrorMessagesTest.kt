package io.github.bitjacker.scrigno.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.bitjacker.scrigno.R
import io.github.bitjacker.scrigno.core.remote.AuthenticationException
import io.github.bitjacker.scrigno.core.remote.RemoteException
import io.github.bitjacker.scrigno.core.remote.ServerIdentityChangedException
import io.github.bitjacker.scrigno.core.remote.UntrustedCertificateException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
class ErrorMessagesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun knownErrorsBecomeReadableSentences() {
        assertEquals(context.getString(R.string.error_auth), ErrorMessages.describe(context, AuthenticationException("530")))
        assertEquals(context.getString(R.string.error_unknown_host), ErrorMessages.describe(context, UnknownHostException("nas")))
        assertEquals(context.getString(R.string.error_unreachable), ErrorMessages.describe(context, ConnectException("refused")))
        assertEquals(context.getString(R.string.error_timeout), ErrorMessages.describe(context, SocketTimeoutException()))
        assertTrue(ErrorMessages.describe(context, UntrustedCertificateException("SHA256:AA")).contains("SHA256:AA"))
        val changed = ErrorMessages.describe(context, ServerIdentityChangedException("SHA256:old", "SHA256:new"))
        assertTrue(changed.contains("SHA256:old") && changed.contains("SHA256:new"))
    }

    @Test
    fun theCauseChainIsInspected() {
        // JSch and OkHttp wrap the real reason: it must still be recognised.
        val wrapped = RemoteException("SSH connection failed", RuntimeException(UnknownHostException("nas.local")))
        assertEquals(context.getString(R.string.error_unknown_host), ErrorMessages.describe(context, wrapped))
    }

    @Test
    fun unknownErrorsKeepTheirMessage() {
        val text = ErrorMessages.describe(context, IllegalStateException("disk full"))
        assertTrue(text.contains("disk full"))
    }

    @Test
    @Config(qualifiers = "it")
    fun italian() {
        assertEquals(
            "Nome utente o password errati, oppure permessi insufficienti sulla cartella.",
            ErrorMessages.describe(context, AuthenticationException("530")),
        )
    }
}
