package io.github.bitjacker.scrigno.core.remote.webdav

import io.github.bitjacker.scrigno.core.remote.Protocol
import io.github.bitjacker.scrigno.core.remote.RemoteStorage
import io.github.bitjacker.scrigno.core.remote.RemoteStorageContract
import io.github.bitjacker.scrigno.core.remote.ServerConfig
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

class WebDavStorageTest : RemoteStorageContract() {

    private lateinit var server: MockWebServer
    private lateinit var dav: FakeDav

    @Before
    fun start() {
        dav = FakeDav(USER, PASSWORD)
        server = MockWebServer().apply {
            dispatcher = dav
            start()
        }
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun config(password: String = PASSWORD) = ServerConfig(
        protocol = Protocol.WEBDAV,
        host = server.hostName,
        port = server.port,
        username = USER,
        password = password,
        basePath = "/remote.php/dav/files/$USER",
    )

    override fun storage(): RemoteStorage = WebDavStorage(config())

    override fun storageWithWrongPassword(): RemoteStorage = WebDavStorage(config(password = "wrong"))

    @Test
    fun sendsTheCaptureDateToNextcloud() {
        WebDavStorage(config()).use { storage ->
            storage.connect()
            storage.mkdirs("")
            storage.upload("a.jpg", ByteArrayInputStream(ByteArray(3)), 3, modifiedMillis = 1_700_000_000_000L)
        }
        assertEquals(1_700_000_000_000L, dav.mtimes["remote.php/dav/files/$USER/a.jpg"])
    }

    private companion object {
        const val USER = "carla"
        const val PASSWORD = "pässword"
    }
}

/** A tiny in-memory WebDAV server, strict like nginx (MKCOL needs the parent, MOVE does not overwrite). */
private class FakeDav(user: String, password: String) : Dispatcher() {
    private val authorization = Credentials.basic(user, password, Charsets.UTF_8)
    private val files: MutableMap<String, ByteArray> = ConcurrentHashMap()
    private val dirs: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply {
        add("")
        add("remote.php")
        add("remote.php/dav")
        add("remote.php/dav/files")
    }
    val mtimes = ConcurrentHashMap<String, Long>()

    private fun key(url: HttpUrl) = url.pathSegments.filter { it.isNotEmpty() }.joinToString("/")
    private fun parent(path: String) = path.substringBeforeLast('/', "")
    private fun status(code: Int) = MockResponse().setResponseCode(code)

    override fun dispatch(request: RecordedRequest): MockResponse {
        if (request.getHeader("Authorization") != authorization) return status(401)
        val path = key(request.requestUrl!!)
        return when (request.method) {
            "PROPFIND" -> propfind(path, request.getHeader("Depth") ?: "1")
            "MKCOL" -> when {
                path in dirs || path in files -> status(405)
                parent(path) !in dirs -> status(409)
                else -> status(201).also { dirs += path }
            }
            "PUT" -> if (parent(path) !in dirs) {
                status(409)
            } else {
                files[path] = request.body.readByteArray()
                request.getHeader("X-OC-MTime")?.toLongOrNull()?.let { mtimes[path] = it * 1000 }
                status(201)
            }
            "GET" -> files[path]?.let { MockResponse().setBody(Buffer().write(it)) } ?: status(404)
            "DELETE" -> if (files.remove(path) != null || dirs.remove(path)) status(204) else status(404)
            "MOVE" -> {
                val destination = key(request.getHeader("Destination")!!.toHttpUrl())
                when {
                    path !in files -> status(404)
                    destination in files && request.getHeader("Overwrite") == "F" -> status(412)
                    else -> status(201).also { files[destination] = files.remove(path)!! }
                }
            }
            else -> status(405)
        }
    }

    private fun propfind(path: String, depth: String): MockResponse {
        val body = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><d:multistatus xmlns:d=\"DAV:\">")
        when {
            path in dirs -> {
                body.append(entry(path, null))
                if (depth == "1") {
                    dirs.filter { it.isNotEmpty() && it != path && parent(it) == path }.forEach { body.append(entry(it, null)) }
                    files.filterKeys { parent(it) == path }.forEach { (name, bytes) -> body.append(entry(name, bytes.size)) }
                }
            }
            path in files -> body.append(entry(path, files.getValue(path).size))
            else -> return status(404)
        }
        body.append("</d:multistatus>")
        return MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml; charset=utf-8").setBody(body.toString())
    }

    private fun entry(path: String, size: Int?): String {
        val url = HttpUrl.Builder().scheme("http").host("localhost").apply {
            path.split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) }
            if (size == null && path.isNotEmpty()) addPathSegment("")
        }.build()
        val props = if (size == null) {
            "<d:resourcetype><d:collection/></d:resourcetype>"
        } else {
            "<d:resourcetype/><d:getcontentlength>$size</d:getcontentlength>"
        }
        return "<d:response><d:href>${url.encodedPath}</d:href><d:propstat><d:prop>$props" +
            "<d:getlastmodified>Tue, 14 May 2024 10:00:00 GMT</d:getlastmodified></d:prop>" +
            "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
    }
}
