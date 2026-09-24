package io.github.bitjacker.scrigno.core.remote.webdav

import org.w3c.dom.Element
import java.io.InputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

/** Minimal parser for WebDAV `multistatus` answers to PROPFIND. */
internal object WebDavXml {
    private const val DAV = "DAV:"

    data class Entry(
        val href: String,
        val isCollection: Boolean,
        val contentLength: Long,
        val lastModifiedMillis: Long?,
    )

    fun parse(input: InputStream): List<Entry> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        // No DTDs and no external entities: the answer comes from the network.
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { factory.isExpandEntityReferences = false }
        val document = input.use { factory.newDocumentBuilder().parse(it) }

        val responses = document.getElementsByTagNameNS(DAV, "response")
        val entries = ArrayList<Entry>(responses.length)
        for (i in 0 until responses.length) {
            val response = responses.item(i) as? Element ?: continue
            val href = response.firstText("href")?.trim() ?: continue
            var isCollection = false
            var length = 0L
            var modified: Long? = null
            var found = false
            val propstats = response.getElementsByTagNameNS(DAV, "propstat")
            for (j in 0 until propstats.length) {
                val propstat = propstats.item(j) as? Element ?: continue
                val status = propstat.firstText("status").orEmpty()
                if (status.isNotEmpty() && !status.contains(" 200")) continue
                val prop = propstat.getElementsByTagNameNS(DAV, "prop").item(0) as? Element ?: continue
                found = true
                val resourceType = prop.getElementsByTagNameNS(DAV, "resourcetype").item(0) as? Element
                if (resourceType != null && resourceType.getElementsByTagNameNS(DAV, "collection").length > 0) {
                    isCollection = true
                }
                prop.firstText("getcontentlength")?.trim()?.toLongOrNull()?.let { length = it }
                prop.firstText("getlastmodified")?.let { modified = parseHttpDate(it) }
            }
            // A response without a successful propstat (e.g. a 404 inside the multistatus) is skipped.
            if (found || propstats.length == 0) entries += Entry(href, isCollection, length, modified)
        }
        return entries
    }

    private fun Element.firstText(localName: String): String? {
        val nodes = getElementsByTagNameNS(DAV, localName)
        return if (nodes.length > 0) nodes.item(0).textContent else null
    }

    fun parseHttpDate(value: String): Long? = runCatching {
        ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrNull()
}
