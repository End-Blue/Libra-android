package com.libra.reader

import android.util.Xml
import android.webkit.MimeTypeMap
import org.xmlpull.v1.XmlPullParser
import java.io.Closeable
import java.io.File
import java.io.StringReader
import java.net.URLDecoder
import java.util.zip.ZipFile

/** Lector mínimo de paquetes EPUB: metadatos, portada y orden de capítulos (spine). */
class EpubPackage(file: File) : Closeable {
    private class Item(val path: String, val mediaType: String, val properties: String)

    private val zip = ZipFile(file)
    private val mimeByPath = HashMap<String, String>()
    private var coverItem: Item? = null

    /** Rutas dentro del ZIP de los capítulos, en orden de lectura. */
    val spine = ArrayList<String>()
    var title: String? = null
        private set
    var author: String? = null
        private set

    init {
        try {
            parse()
        } catch (e: Exception) {
            runCatching { zip.close() }
            throw e
        }
    }

    private fun parse() {
        val container = readText("META-INF/container.xml")
            ?: throw IllegalStateException("EPUB sin container.xml")
        var opfPath: String? = null
        run {
            val p = newParser(container)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT && opfPath == null) {
                if (ev == XmlPullParser.START_TAG && p.name.substringAfterLast(':') == "rootfile") {
                    opfPath = p.getAttributeValue(null, "full-path")
                }
                ev = p.next()
            }
        }
        val opf = opfPath ?: throw IllegalStateException("EPUB sin archivo OPF")
        val opfText = readText(opf) ?: throw IllegalStateException("No se pudo leer el OPF")
        val base = opf.substringBeforeLast('/', "")

        val rawItems = LinkedHashMap<String, Item>()
        val spineIds = ArrayList<String>()
        var coverId: String? = null
        var inMetadata = false

        val p = newParser(opfText)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name.substringAfterLast(':')) {
                    "metadata" -> inMetadata = true
                    "title" -> if (inMetadata && title == null) {
                        title = p.nextText().trim().ifEmpty { null }
                    }
                    "creator" -> if (inMetadata && author == null) {
                        author = p.nextText().trim().ifEmpty { null }
                    }
                    "meta" -> if (p.getAttributeValue(null, "name") == "cover") {
                        coverId = p.getAttributeValue(null, "content")
                    }
                    "item" -> {
                        val id = p.getAttributeValue(null, "id")
                        val href = p.getAttributeValue(null, "href")
                        if (id != null && href != null) {
                            rawItems[id] = Item(
                                resolve(base, href),
                                p.getAttributeValue(null, "media-type") ?: "",
                                p.getAttributeValue(null, "properties") ?: ""
                            )
                        }
                    }
                    "itemref" -> p.getAttributeValue(null, "idref")?.let { spineIds.add(it) }
                }
            } else if (ev == XmlPullParser.END_TAG && p.name.substringAfterLast(':') == "metadata") {
                inMetadata = false
            }
            ev = p.next()
        }

        rawItems.values.forEach { if (it.mediaType.isNotBlank()) mimeByPath[it.path] = it.mediaType }
        spineIds.forEach { id -> rawItems[id]?.let { spine.add(it.path) } }
        coverItem = rawItems.values.firstOrNull { it.properties.split(' ').contains("cover-image") }
            ?: coverId?.let { rawItems[it] }
            ?: rawItems.entries.firstOrNull {
                it.key.contains("cover", ignoreCase = true) && it.value.mediaType.startsWith("image/")
            }?.value
    }

    fun readBytes(path: String): ByteArray? {
        val entry = zip.getEntry(path) ?: return null
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    fun readText(path: String): String? =
        readBytes(path)?.toString(Charsets.UTF_8)?.removePrefix("\uFEFF")

    fun coverBytes(): ByteArray? = coverItem?.let { readBytes(it.path) }

    fun mimeOf(path: String): String {
        mimeByPath[path]?.let { return it }
        val ext = path.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                "xhtml", "html", "htm" -> "text/html"
                "css" -> "text/css"
                else -> "application/octet-stream"
            }
    }

    override fun close() {
        runCatching { zip.close() }
    }

    private fun newParser(text: String): XmlPullParser =
        Xml.newPullParser().apply { setInput(StringReader(text)) }

    private fun resolve(base: String, href: String): String {
        val cleaned = href.substringBefore('#').substringBefore('?').replace("+", "%2B")
        val decoded = try {
            URLDecoder.decode(cleaned, "UTF-8")
        } catch (_: Exception) {
            cleaned
        }
        val parts = ArrayList<String>()
        val full = if (base.isEmpty()) decoded else "$base/$decoded"
        for (seg in full.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }
}
