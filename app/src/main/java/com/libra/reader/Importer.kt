package com.libra.reader

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

private val ALLOWED_BOOKS = setOf("epub", "pdf", "txt", "fb2", "cbz", "cbr")
private val ALLOWED_AUDIO = setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "wav", "flac")

/** Copia los archivos al almacenamiento privado y extrae título, autor y portada. */
internal suspend fun importUris(
    resolver: ContentResolver,
    filesDir: File,
    uris: List<Uri>,
    type: String,
    dao: LibraryDao
): String {
    val folder = File(filesDir, if (type == "AUDIO") "audiobooks" else "books").apply { mkdirs() }
    var imported = 0
    var rejected = 0
    for (uri in uris) {
        try {
            val displayName = resolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
            } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "archivo"
            val extension = displayName.substringAfterLast('.', "").lowercase()
            val allowed = if (type == "AUDIO") ALLOWED_AUDIO else ALLOWED_BOOKS
            if (extension !in allowed) {
                rejected++
                continue
            }
            val id = UUID.randomUUID().toString()
            val destination = File(folder, "$id.$extension")
            resolver.openInputStream(uri)?.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("No se pudo leer el archivo")

            val meta = MetadataExtractor.extract(destination, extension, type)
            val coverPath = meta.cover?.let { CoverStore.save(filesDir, id, it) }
            val fileTitle = displayName.substringBeforeLast('.', displayName).replace('_', ' ')
            val count = dao.count()
            dao.insert(
                LibraryItem(
                    id = id,
                    title = meta.title?.takeIf { it.isNotBlank() } ?: fileTitle,
                    author = meta.author?.takeIf { it.isNotBlank() } ?: "Autor no identificado",
                    format = extension.uppercase(),
                    localPath = destination.absolutePath,
                    mediaType = type,
                    sortOrder = count,
                    coverPath = coverPath,
                    durationMs = meta.durationMs
                )
            )
            imported++
        } catch (_: Exception) {
            rejected++
        }
    }
    return "Importados: $imported · No compatibles o con error: $rejected"
}

/** Quita el elemento de la biblioteca y borra su copia y su portada. */
internal suspend fun removeItem(dao: LibraryDao, item: LibraryItem) {
    dao.deleteById(item.id)
    runCatching { File(item.localPath).delete() }
    item.coverPath?.let { runCatching { File(it).delete() } }
}
