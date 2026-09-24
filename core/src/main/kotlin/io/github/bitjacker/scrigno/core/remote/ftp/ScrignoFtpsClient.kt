package io.github.bitjacker.scrigno.core.remote.ftp

import org.apache.commons.net.ftp.FTPSClient
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket

/**
 * FTPS (explicit TLS) client whose uploads end cleanly.
 *
 * With TLS 1.3 the server sends "session tickets" right after the handshake of every data
 * connection. An upload only writes, so they stay unread, and closing a socket with unread data
 * makes the TCP stack reset the connection instead of closing it: the server may then miss the
 * end of the file (vsftpd: "426 Failure reading network stream"). Hence:
 * - TLS 1.2, which sends nothing after the handshake, is preferred (see [FtpStorage.connect]);
 * - with TLS 1.3 the tickets are read before the end of the upload ([finishUpload]).
 */
internal class ScrignoFtpsClient : FTPSClient("TLS", false) {

    private var dataSocket: SSLSocket? = null
    private var dataOpenMillis = 0L

    /** TLS version of the last data connection. */
    val dataTlsVersion: String?
        get() = dataSocket?.session?.protocol

    override fun _openDataConnection_(command: String?, arg: String?): Socket? {
        val start = System.nanoTime()
        val socket = super._openDataConnection_(command, arg)
        dataOpenMillis = (System.nanoTime() - start) / 1_000_000
        dataSocket = socket as? SSLSocket
        return socket
    }

    /** Call once all the data of an upload is written, before closing its stream. */
    fun finishUpload() {
        val socket = dataSocket ?: return
        if (socket.session?.protocol != "TLSv1.3") return
        val timeout = socket.soTimeout
        try {
            // The tickets arrive one round trip after the handshake; opening the data connection
            // took a few round trips, so waiting that long is enough.
            socket.soTimeout = dataOpenMillis.coerceIn(20, 1_000).toInt()
            socket.inputStream.read()
        } catch (_: SocketTimeoutException) {
            // Expected: the server sends no data during an upload.
        } catch (_: IOException) {
            // Closing will report a real problem, if any.
        } finally {
            runCatching { socket.soTimeout = timeout }
        }
    }
}
