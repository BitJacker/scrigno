package io.github.bitjacker.scrigno.core.remote.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavXmlTest {

    @Test
    fun parsesNextcloudStyleMultistatus() {
        val xml = """
            <?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:oc="http://owncloud.org/ns">
              <d:response>
                <d:href>/remote.php/dav/files/me/Photos/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:resourcetype><d:collection/></d:resourcetype>
                    <d:getlastmodified>Tue, 14 May 2024 10:00:00 GMT</d:getlastmodified>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
                <d:propstat>
                  <d:prop><d:getcontentlength/></d:prop>
                  <d:status>HTTP/1.1 404 Not Found</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/remote.php/dav/files/me/Photos/IMG%201.jpg</d:href>
                <d:propstat>
                  <d:prop>
                    <d:resourcetype/>
                    <d:getcontentlength>12345</d:getcontentlength>
                    <d:getlastmodified>Tue, 14 May 2024 10:00:00 GMT</d:getlastmodified>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
        val entries = WebDavXml.parse(xml.byteInputStream())
        assertEquals(2, entries.size)
        assertTrue(entries[0].isCollection)
        assertEquals("/remote.php/dav/files/me/Photos/IMG%201.jpg", entries[1].href)
        assertFalse(entries[1].isCollection)
        assertEquals(12345L, entries[1].contentLength)
        assertEquals(1715680800000L, entries[1].lastModifiedMillis)
    }

    @Test
    fun parsesOtherPrefixes() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:"><D:response><D:href>http://host/dav/a.jpg</D:href>
            <D:propstat><D:prop><D:getcontentlength>7</D:getcontentlength><D:resourcetype/></D:prop>
            <D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>"""
        val entry = WebDavXml.parse(xml.byteInputStream()).single()
        assertEquals("http://host/dav/a.jpg", entry.href)
        assertEquals(7L, entry.contentLength)
    }
}
