package com.libra.reader

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Copia de seguridad = un .zip con manifest.json (metadatos, progreso, posición en la estantería)
 * más los archivos en files/ y las portadas en covers/.
 */
object Backup {
    suspend fun export(context: Context, dao: LibraryDao, highlightDao: HighlightDao, uri: Uri): String = withContext(Dispatchers.IO) {
        try {
            val usable = dao.getAll().filter { File(it.localPath).exists() }
            val array = JSONArray()
            usable.forEach { item ->
                val cover = item.coverPath?.let { File(it) }?.takeIf { it.exists() }
                array.put(JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("author", item.author)
                    put("format", item.format)
                    put("mediaType", item.mediaType)
                    put("progress", item.progress)
                    put("lastPosition", item.lastPosition)
                    put("durationMs", item.durationMs)
                    put("shelfOrientation", item.shelfOrientation)
                    put("shelfX", item.shelfX.toDouble())
                    put("shelfY", item.shelfY.toDouble())
                    put("sortOrder", item.sortOrder)
                    put("readingMode", item.readingMode)
                    put("bookmarkStyle", item.bookmarkStyle)
                    put("isFavorite", item.isFavorite)
                    put("addedAt", item.addedAt)
                    put("file", File(item.localPath).name)
                    if (cover != null) put("cover", cover.name)
                })
            }
            val usableIds = usable.map { it.id }.toSet()
            val notes = JSONArray()
            highlightDao.getAll().filter { it.bookId in usableIds }.forEach { h ->
                notes.put(JSONObject().apply {
                    put("id", h.id)
                    put("bookId", h.bookId)
                    put("bookTitle", h.bookTitle)
                    put("chapterIndex", h.chapterIndex)
                    put("chapterTitle", h.chapterTitle)
                    put("selectedText", h.selectedText)
                    put("note", h.note)
                    put("colorArgb", h.colorArgb)
                    put("createdAt", h.createdAt)
                })
            }
            val manifest = JSONObject().apply {
                put("app", "Libra")
                put("version", 2)
                put("items", array)
                put("highlights", notes)
            }
            val out = context.contentResolver.openOutputStream(uri, "w")
                ?: return@withContext "No se pudo crear el archivo de copia."
            ZipOutputStream(BufferedOutputStream(out)).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                usable.forEach { item ->
                    val file = File(item.localPath)
                    zip.putNextEntry(ZipEntry("files/${file.name}"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    val cover = item.coverPath?.let { File(it) }?.takeIf { it.exists() }
                    if (cover != null) {
                        zip.putNextEntry(ZipEntry("covers/${cover.name}"))
                        cover.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            "Copia creada con ${usable.size} elementos y ${notes.length()} notas."
        } catch (e: Exception) {
            "No se pudo crear la copia: ${e.message ?: "error desconocido"}"
        }
    }

    suspend fun restore(context: Context, dao: LibraryDao, highlightDao: HighlightDao, uri: Uri): String = withContext(Dispatchers.IO) {
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext "No se pudo abrir la copia."
            val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
            val byFile = HashMap<String, JSONObject>()
            val byCover = HashMap<String, JSONObject>()
            val restored = HashSet<String>()
            var hasManifest = false
            var total = 0
            var notesJson: JSONArray? = null

            ZipInputStream(BufferedInputStream(input)).use { zin ->
                var entry: ZipEntry? = zin.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name == "manifest.json") {
                        val json = JSONObject(zin.readBytes().toString(Charsets.UTF_8))
                        val array = json.getJSONArray("items")
                        total = array.length()
                        notesJson = json.optJSONArray("highlights")
                        for (i in 0 until array.length()) {
                            val o = array.getJSONObject(i)
                            byFile[File(o.getString("file")).name] = o
                            val cover = o.optString("cover")
                            if (cover.isNotEmpty()) byCover[File(cover).name] = o
                        }
                        hasManifest = true
                    } else if (hasManifest && !entry.isDirectory) {
                        // Solo se usa el nombre final: nunca la ruta del zip (evita rutas maliciosas).
                        val safeName = File(name).name
                        if (name.startsWith("files/")) {
                            val o = byFile[safeName]
                            if (o != null && dao.getById(o.getString("id")) == null) {
                                val dir = destinationFolder(context, o.getString("mediaType"))
                                File(dir, safeName).outputStream().use { zin.copyTo(it) }
                                restored.add(o.getString("id"))
                            }
                        } else if (name.startsWith("covers/")) {
                            val o = byCover[safeName]
                            if (o != null && restored.contains(o.getString("id"))) {
                                File(coversDir, safeName).outputStream().use { zin.copyTo(it) }
                            }
                        }
                    }
                    entry = zin.nextEntry
                }
            }
            if (!hasManifest) return@withContext "Ese archivo no es una copia de seguridad de Libra."

            var order = dao.count()
            var added = 0
            for (o in byFile.values) {
                val id = o.getString("id")
                if (id !in restored) continue
                val dir = destinationFolder(context, o.getString("mediaType"))
                val coverName = o.optString("cover")
                val cover = if (coverName.isNotEmpty()) File(coversDir, File(coverName).name) else null
                dao.insert(
                    LibraryItem(
                        id = id,
                        title = o.optString("title", "Sin título"),
                        author = o.optString("author", "Autor no identificado"),
                        format = o.optString("format"),
                        localPath = File(dir, File(o.getString("file")).name).absolutePath,
                        mediaType = o.getString("mediaType"),
                        progress = o.optInt("progress"),
                        shelfOrientation = o.optString("shelfOrientation", "VERTICAL"),
                        sortOrder = order++,
                        addedAt = o.optLong("addedAt", System.currentTimeMillis()),
                        coverPath = cover?.takeIf { it.exists() }?.absolutePath,
                        lastPosition = o.optLong("lastPosition"),
                        durationMs = o.optLong("durationMs"),
                        shelfX = o.optDouble("shelfX", -1.0).toFloat(),
                        shelfY = o.optDouble("shelfY", -1.0).toFloat(),
                        readingMode = o.optString("readingMode", "VERTICAL").ifEmpty { "VERTICAL" },
                        bookmarkStyle = o.optString("bookmarkStyle", ""),
                        isFavorite = o.optInt("isFavorite", 0)
                    )
                )
                added++
            }
            // Notas y subrayados de los libros restaurados
            var notesAdded = 0
            val notes = notesJson
            if (notes != null) {
                for (i in 0 until notes.length()) {
                    val n = notes.getJSONObject(i)
                    if (n.getString("bookId") !in restored) continue
                    highlightDao.insert(
                        Highlight(
                            id = n.getString("id"),
                            bookId = n.getString("bookId"),
                            bookTitle = n.optString("bookTitle"),
                            chapterIndex = n.optInt("chapterIndex"),
                            chapterTitle = n.optString("chapterTitle"),
                            selectedText = n.optString("selectedText"),
                            note = n.optString("note"),
                            colorArgb = n.optLong("colorArgb", 0xFFFFF176L),
                            createdAt = n.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                    notesAdded++
                }
            }
            "Restaurados: $added libros y $notesAdded notas · Ya existían u omitidos: ${total - added}"
        } catch (e: Exception) {
            "La copia no es válida o está dañada: ${e.message ?: "error desconocido"}"
        }
    }

    private fun destinationFolder(context: Context, mediaType: String): File =
        File(context.filesDir, if (mediaType == "AUDIO") "audiobooks" else "books").apply { mkdirs() }
}
