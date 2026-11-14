package com.libra.reader

import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream

class Fb2Doc(
    val title: String?,
    val author: String?,
    val paragraphs: List<String>,
    val cover: ByteArray?
)

object Fb2Parser {
    fun parse(file: File): Fb2Doc {
        val p = Xml.newPullParser()
        return FileInputStream(file).use { input ->
            p.setInput(input, null)
            var title: String? = null
            val authorParts = ArrayList<String>()
            var authorDone = false
            var coverRef: String? = null
            var cover: ByteArray? = null
            val paragraphs = ArrayList<String>()
            val buffer = StringBuilder()
            var inTitleInfo = false
            var titleInfoSeen = false
            var inAuthor = false
            var inBody = false

            fun flush() {
                val t = buffer.toString().replace(Regex("\\s+"), " ").trim()
                if (t.isNotEmpty()) paragraphs.add(t)
                buffer.setLength(0)
            }

            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name.substringAfterLast(':')) {
                        "title-info" -> if (!titleInfoSeen) {
                            inTitleInfo = true
                            titleInfoSeen = true
                        }
                        "author" -> if (inTitleInfo && !authorDone) inAuthor = true
                        "first-name", "middle-name", "last-name" -> if (inAuthor) {
                            val t = p.nextText().trim()
                            if (t.isNotEmpty()) authorParts.add(t)
                        }
                        "book-title" -> if (inTitleInfo && title == null) {
                            title = p.nextText().trim().ifEmpty { null }
                        }
                        "image" -> if (inTitleInfo && coverRef == null) {
                            for (i in 0 until p.attributeCount) {
                                if (p.getAttributeName(i).endsWith("href")) {
                                    coverRef = p.getAttributeValue(i).removePrefix("#")
                                }
                            }
                        }
                        "body" -> inBody = true
                        "binary" -> {
                            val id = p.getAttributeValue(null, "id")
                            val data = p.nextText()
                            if (id != null && id == coverRef && cover == null) {
                                cover = try {
                                    Base64.decode(data, Base64.DEFAULT)
                                } catch (_: Exception) {
                                    null
                                }
                            }
                        }
                    }
                } else if (ev == XmlPullParser.TEXT) {
                    if (inBody) buffer.append(p.text)
                } else if (ev == XmlPullParser.END_TAG) {
                    when (p.name.substringAfterLast(':')) {
                        "title-info" -> inTitleInfo = false
                        "author" -> if (inAuthor) {
                            inAuthor = false
                            authorDone = true
                        }
                        "body" -> {
                            flush()
                            inBody = false
                        }
                        "p", "v", "subtitle", "text-author", "title" -> if (inBody) flush()
                    }
                }
                ev = p.next()
            }
            Fb2Doc(title, authorParts.joinToString(" ").ifEmpty { null }, paragraphs, cover)
        }
    }
}
