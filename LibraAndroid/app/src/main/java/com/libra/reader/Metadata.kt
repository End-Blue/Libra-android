package com.libra.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.zip.ZipFile

class Meta(
    val title: String? = null,
    val author: String? = null,
    val cover: Bitmap? = null,
    val durationMs: Long = 0
)

internal val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

/** Decodifica una imagen reduciéndola para no gastar memoria de más. */
internal fun decodeSampled(bytes: ByteArray, reqWidth: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= reqWidth) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

internal fun naturalKey(name: String): String =
    name.lowercase().replace(Regex("\\d+")) { it.value.padStart(12, '0') }

object MetadataExtractor {
    fun extract(file: File, extension: String, mediaType: String): Meta = try {
        if (mediaType == "AUDIO") {
            audio(file)
        } else {
            when (extension) {
                "epub" -> epub(file)
                "pdf" -> pdf(file)
                "cbz" -> cbz(file)
                "fb2" -> fb2(file)
                else -> Meta()
            }
        }
    } catch (_: Throwable) {
        Meta()
    }

    private fun audio(file: File): Meta {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val author = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val cover = r.embeddedPicture?.let { decodeSampled(it, 480) }
            return Meta(title, author, cover, duration)
        } finally {
            r.release()
        }
    }

    private fun epub(file: File): Meta =
        EpubPackage(file).use { pkg ->
            Meta(pkg.title, pkg.author, pkg.coverBytes()?.let { decodeSampled(it, 480) })
        }

    private fun pdf(file: File): Meta {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val renderer = PdfRenderer(pfd)
            try {
                if (renderer.pageCount == 0) return Meta()
                val page = renderer.openPage(0)
                try {
                    val width = 480
                    val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return Meta(cover = bmp)
                } finally {
                    page.close()
                }
            } finally {
                renderer.close()
            }
        } finally {
            pfd.close()
        }
    }

    private fun cbz(file: File): Meta = ZipFile(file).use { zip ->
        val first = zip.entries().toList()
            .filter { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS }
            .minByOrNull { naturalKey(it.name) } ?: return@use Meta()
        val bytes = zip.getInputStream(first).use { it.readBytes() }
        Meta(cover = decodeSampled(bytes, 480))
    }

    private fun fb2(file: File): Meta {
        val doc = Fb2Parser.parse(file)
        return Meta(doc.title, doc.author, doc.cover?.let { decodeSampled(it, 480) })
    }
}

object CoverStore {
    fun save(filesDir: File, id: String, bitmap: Bitmap): String? = try {
        val dir = File(filesDir, "covers").apply { mkdirs() }
        val width = if (bitmap.width > 480) 480 else bitmap.width
        val height = (bitmap.height.toFloat() * width / bitmap.width).toInt().coerceAtLeast(1)
        val scaled = if (width != bitmap.width) Bitmap.createScaledBitmap(bitmap, width, height, true) else bitmap
        // Se aplana sobre fondo blanco: JPEG no admite transparencia.
        val flat = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(scaled, 0f, 0f, null)
        }
        val out = File(dir, "$id.jpg")
        out.outputStream().use { flat.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        out.absolutePath
    } catch (_: Throwable) {
        null
    }
}
