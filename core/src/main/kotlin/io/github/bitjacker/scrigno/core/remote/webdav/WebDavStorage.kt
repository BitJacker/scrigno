package io.github.bitjacker.scrigno.core.remote.webdav

import io.github.bitjacker.scrigno.core.backup.RemotePaths
import io.github.bitjacker.scrigno.core.io.Streams
import io.github.bitjacker.scrigno.core.remote.AuthenticationException
import io.github.bitjacker.scrigno.core.remote.BaseRemoteStorage
import io.github.bitjacker.scrigno.core.remote.PinningTrustManager
import io.github.bitjacker.scrigno.core.remote.Protocol
import io.github.bitjacker.scrigno.core.remote.RemoteException
import io.github.bitjacker.scrigno.core.remote.RemoteFile
import io.github.bitjacker.scrigno.core.remote.RemoteNotFoundException
import io.github.bitjacker.scrigno.core.remote.ServerConfig
import io.github.bitjacker.scrigno.core.remote.Timeouts
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.internal.tls.OkHostnameVerifier
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * WebDAV over HTTP or HTTPS (Nextcloud, ownCloud, Synology, Apache, nginx, rclone...), based on
 * OkHttp. Uses Basic authentication, sent only to the configured server.
 */
class WebDavStorage(
    override val config: ServerConfig,
    private val timeouts: Timeouts = Timeouts(),
) : BaseRemoteStorage() {

    private var client: OkHttpClient? = null
    private var trustManager: PinningTrustManager? = null
    private val https = config.protocol == Protocol.WEBDAVS

    private val root: HttpUrl by lazy {
        HttpUrl.Builder()
            .scheme(if (https) "https" else "http")
            .host(config.host.trim())
            .port(config.port)
            .build()
    }

    override val serverFingerprint: String?
        get() = trustManager?.observedFingerprint

    override val isConnected: Boolean
        get() = client != null

    override fun connect() {
        close()
        val builder = OkHttpClient.Builder()
            .connectTimeout(timeouts.connectMillis.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeouts.readMillis.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(timeouts.readMillis.toLong(), TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
        val authorization = if (config.username.isNotEmpty()) {
            Credentials.basic(config.username, config.password, Charsets.UTF_8)
        } else {
            null
        }
        builder.addInterceptor { chain ->
            val request = chain.request().newBuilder().header("User-Agent", "Scrigno")
            // Credentials only ever go to the configured host (not to a server we are redirected to).
            if (authorization != null && chain.request().url.host.equals(root.host, ignoreCase = true)) {
                request.header("Authorization", authorization)
            }
            chain.proceed(request.build())
        }
        if (https) {
            val tm = PinningTrustManager(config.trustedFingerprint, config.allowSelfSigned)
            trustManager = tm
            builder.sslSocketFactory(tm.sslContext().socketFactory, tm)
            builder.hostnameVerifier(tm.hostnameVerifier(OkHostnameVerifier))
        } else {
            trustManager = null
        }
        client = builder.build()
        try {
            // Checks that the server answers and accepts the credentials.
            propfind(resolve(""), depth = 0).use { response ->
                when (response.code) {
                    207, 200, 404 -> Unit // 404: the base folder will be created on the first backup
                    401, 403 -> throw AuthenticationException("Login refused by the WebDAV server (HTTP ${response.code})")
                    405 -> throw RemoteException("This address does not look like a WebDAV folder (HTTP 405)")
                    else -> throw httpError(response, "Connection")
                }
            }
        } catch (e: IOException) {
            close()
            if (e is SSLException) trustManager?.explain(e)?.let { throw it }
            throw e
        }
    }

    private fun http(): OkHttpClient = client ?: throw RemoteException("Not connected")

    private fun url(resolved: String, trailingSlash: Boolean = false): HttpUrl {
        val builder = root.newBuilder()
        for (segment in RemotePaths.segments(resolved)) builder.addPathSegment(segment)
        if (trailingSlash) builder.addPathSegment("")
        return builder.build()
    }

    private fun execute(request: Request): Response = http().newCall(request).execute()

    private fun propfind(resolved: String, depth: Int): Response {
        val body = PROPFIND_BODY.toRequestBody(XML)
        return execute(
            Request.Builder().url(url(resolved)).method("PROPFIND", body).header("Depth", depth.toString()).build(),
        )
    }

    private fun httpError(response: Response, operation: String): RemoteException = when (response.code) {
        401, 403 -> AuthenticationException("$operation refused by the server (HTTP ${response.code})")
        404 -> RemoteNotFoundException(response.request.url.encodedPath)
        else -> RemoteException("$operation failed: HTTP ${response.code} ${response.message}".trim())
    }

    override fun statResolved(resolved: String): Attrs? = propfind(resolved, 0).use { response ->
        when (response.code) {
            404 -> null
            207 -> {
                val entry = WebDavXml.parse(response.body!!.byteStream()).firstOrNull() ?: return@use null
                Attrs(entry.isCollection, entry.contentLength, entry.lastModifiedMillis)
            }
            else -> throw httpError(response, "PROPFIND")
        }
    }

    override fun mkdirResolved(resolved: String) {
        execute(Request.Builder().url(url(resolved, trailingSlash = true)).method("MKCOL", null).build()).use { response ->
            // 405: it already exists.
            if (!response.isSuccessful && response.code != 405) throw httpError(response, "Creating folder $resolved")
        }
    }

    override fun list(path: String): List<RemoteFile> {
        val full = resolve(path)
        val requested = url(full).pathSegments.filter { it.isNotEmpty() }
        return propfind(full, 1).use { response ->
            if (response.code != 207) throw httpError(response, "Listing")
            WebDavXml.parse(response.body!!.byteStream()).mapNotNull { entry ->
                val entryUrl = root.resolve(entry.href) ?: return@mapNotNull null
                val segments = entryUrl.pathSegments.filter { it.isNotEmpty() }
                if (segments == requested) return@mapNotNull null // the folder itself
                val name = segments.lastOrNull() ?: return@mapNotNull null
                RemoteFile(
                    name = name,
                    path = childOf(path, name),
                    isDirectory = entry.isCollection,
                    size = entry.contentLength,
                    modifiedMillis = entry.lastModifiedMillis,
                )
            }
        }
    }

    override fun upload(path: String, input: InputStream, length: Long, modifiedMillis: Long?, onProgress: (Long) -> Unit) {
        val body = object : RequestBody() {
            override fun contentType() = OCTET_STREAM
            override fun contentLength() = length
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                val buffer = ByteArray(Streams.BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    total += read
                    onProgress(total)
                }
            }
        }
        val request = Request.Builder().url(url(resolve(path))).put(body)
        // Nextcloud and ownCloud keep the original date of the photo with this header.
        if (modifiedMillis != null && modifiedMillis > 0) request.header("X-OC-MTime", (modifiedMillis / 1000L).toString())
        execute(request.build()).use { response ->
            if (!response.isSuccessful) throw httpError(response, "Upload")
        }
    }

    override fun download(path: String, output: OutputStream, onProgress: (Long) -> Unit) {
        execute(Request.Builder().url(url(resolve(path))).get().build()).use { response ->
            if (!response.isSuccessful) throw httpError(response, "Download")
            response.body!!.byteStream().use { Streams.copy(it, output, onProgress) }
        }
    }

    override fun rename(from: String, to: String) {
        val request = Request.Builder()
            .url(url(resolve(from)))
            .method("MOVE", null)
            .header("Destination", url(resolve(to)).toString())
            .header("Overwrite", "F")
            .build()
        execute(request).use { response ->
            if (!response.isSuccessful) throw httpError(response, "Rename")
        }
    }

    override fun delete(path: String) {
        execute(Request.Builder().url(url(resolve(path))).delete().build()).use { response ->
            if (!response.isSuccessful && response.code != 404) throw httpError(response, "Delete")
        }
    }

    override fun close() {
        val http = client ?: return
        client = null
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private companion object {
        val XML = "application/xml; charset=utf-8".toMediaType()
        val OCTET_STREAM = "application/octet-stream".toMediaType()
        const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
    }
}
