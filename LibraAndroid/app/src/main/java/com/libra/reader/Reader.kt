package com.libra.reader

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.os.ParcelFileDescriptor
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.math.abs

// ══════════════════════════════════════════════════════════════════════════════
// PANTALLA PRINCIPAL DEL LECTOR
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun ReaderScreen(
    item: LibraryItem,
    onClose: () -> Unit,
    onSave: (Long, Int) -> Unit,
    highlightDao: HighlightDao,
    onUpdateItem: (LibraryItem) -> Unit
) {
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)

    var showHighlights by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val highlights by highlightDao.observeByBook(item.id).collectAsState(initial = emptyList())
    var editingHighlight by remember { mutableStateOf<Highlight?>(null) }

    Column(Modifier.fillMaxSize().background(Ink).systemBarsPadding()) {
        // Barra superior
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onClose) { Text("‹ Volver", color = Gold) }
            Text(item.title, color = Cream, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            TextButton(onClick = { showHighlights = true }) { Text("📝", fontSize = 18.sp) }
            TextButton(onClick = { showSettings = true }) { Text("⚙", color = Muted, fontSize = 18.sp) }
        }

        // Contenido
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (item.format.lowercase()) {
                "epub" -> EpubReader(item, onSave, highlightDao, onUpdateItem)
                "pdf"  -> PdfReader(item, onSave, item.readingMode, highlightDao, onUpdateItem)
                "txt"  -> TextFileReader(item, onSave, fb2 = false, highlightDao, onUpdateItem)
                "fb2"  -> TextFileReader(item, onSave, fb2 = true,  highlightDao, onUpdateItem)
                "cbz"  -> CbzReader(item, onSave, item.readingMode)
                else   -> Message("Los archivos CBR (RAR) no se pueden leer. Conviértelo a CBZ.")
            }
        }

        // Separador de página
        if (item.bookmarkStyle.isNotBlank()) BookmarkDivider(item)
    }

    if (showHighlights) {
        ModalBottomSheet(onDismissRequest = { showHighlights = false }, containerColor = Panel) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
                Text("NOTAS DE ESTE LIBRO", color = Gold, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(12.dp))
                BookHighlightsScreen(highlights = highlights,
                    onEdit = { editingHighlight = it },
                    onDelete = { h -> scope.launch { highlightDao.deleteById(h.id) } })
            }
        }
    }

    if (showSettings) {
        ReaderSettingsSheet(item = item, onDismiss = { showSettings = false },
            onUpdateItem = onUpdateItem)
    }

    editingHighlight?.let { h ->
        HighlightEditDialog(
            highlight = h,
            onSave = { updated -> scope.launch { highlightDao.update(updated) }; editingHighlight = null },
            onDismiss = { editingHighlight = null }
        )
    }
}

@Composable
fun ReaderSettingsSheet(item: LibraryItem, onDismiss: () -> Unit, onUpdateItem: (LibraryItem) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Panel) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("AJUSTES DEL LECTOR", color = Gold, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text("Modo de paso de página", color = Cream, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("VERTICAL" to "↕ Scroll", "HORIZONTAL" to "↔ Pasar hoja").forEach { (mode, label) ->
                    FilterChip(selected = item.readingMode == mode,
                        onClick = { onUpdateItem(item.copy(readingMode = mode)) },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Gold, selectedLabelColor = Ink, labelColor = Cream))
                }
            }
            HorizontalDivider(color = Ink)
            Text("Separador de página", color = Cream, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("Aparece al final del lector para recordar dónde lo dejaste.", color = Muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("" to "🚫 Ninguno", "COVER" to "📖 Portada", "RIBBON" to "🎀 Cinta")
                    .forEach { (style, label) ->
                        FilterChip(selected = item.bookmarkStyle == style,
                            onClick = { onUpdateItem(item.copy(bookmarkStyle = style)) },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Gold, selectedLabelColor = Ink, labelColor = Cream))
                    }
            }
        }
    }
}

@Composable
fun BookmarkDivider(item: LibraryItem) {
    when (item.bookmarkStyle) {
        "COVER" -> Row(
            Modifier.fillMaxWidth().height(48.dp).background(Panel).padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BookCover(item, Modifier.size(width = 25.dp, height = 36.dp))
            Spacer(Modifier.width(10.dp))
            Text("Continuarás aquí", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text("🔖", fontSize = 18.sp)
        }
        "RIBBON" -> Box(Modifier.fillMaxWidth().height(6.dp).background(Gold))
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Cream, fontSize = 15.sp, lineHeight = 22.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Modifier.pageSwipe(onNext: () -> Unit, onPrev: () -> Unit): Modifier {
    val next by rememberUpdatedState(onNext)
    val prev by rememberUpdatedState(onPrev)
    return pointerInput(Unit) {
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = { if (total < -80f) next() else if (total > 80f) prev() },
            onDragCancel = { total = 0f }
        ) { _, amount -> total += amount }
    }
}

@Composable
private fun <T> PagedContent(state: T, goingForward: Boolean, modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit) {
    AnimatedContent(targetState = state,
        transitionSpec = {
            if (goingForward)
                (slideInHorizontally { it } + fadeIn(tween(220))).togetherWith(slideOutHorizontally { -it } + fadeOut(tween(220)))
            else
                (slideInHorizontally { -it } + fadeIn(tween(220))).togetherWith(slideOutHorizontally { it } + fadeOut(tween(220)))
        },
        label = "page_anim", modifier = modifier) { s -> content(s) }
}

// ══════════════════════════════════════════════════════════════════════════════
// EPUB
// ══════════════════════════════════════════════════════════════════════════════

private const val EPUB_HOST = "libra.local"

private class EpubTheme { @Volatile var mode: Int = 0 }

private fun chapterUrl(path: String): String =
    Uri.Builder().scheme("https").authority(EPUB_HOST)
        .encodedPath("/" + Uri.encode(path, "/")).build().toString()

/** Convierte un colorArgb Long a cadena CSS hex (#rrggbb) */
private fun Long.toCssHex(): String {
    val r = ((this shr 16) and 0xFF).toInt()
    val g = ((this shr 8) and 0xFF).toInt()
    val b = (this and 0xFF).toInt()
    return "#%02x%02x%02x".format(r, g, b)
}

/**
 * CSS base del lector + reglas de subrayado para los highlights guardados.
 * Cada highlight se inyecta como un atributo data-libra-id en el span y se
 * colorea con una clase generada dinámicamente.
 */
private fun styleFor(mode: Int, highlights: List<Highlight>): String {
    val (bg, fg, link) = when (mode) {
        1 -> Triple("#F4ECD8", "#3B2F1E", "#8A5A1F")
        2 -> Triple("#FFFFFF", "#111111", "#1A5FB4")
        else -> Triple("#171716", "#F1E8D8", "#C5A46D")
    }
    // Genera una clase CSS por color de highlight
    val highlightCss = highlights
        .map { it.colorArgb }
        .distinct()
        .joinToString("\n") { argb ->
            val css = argb.toCssHex()
            ".libra-hl-${argb.toString(16)} { background-color: ${css}99 !important; border-radius: 3px; }"
        }
    return """
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
        html,body{background:$bg !important;color:$fg !important;}
        body{padding:14px 18px !important;line-height:1.6 !important;margin:0 !important;}
        a{color:$link !important;}img,svg{max-width:100% !important;height:auto !important;}
        p,div,span,li,h1,h2,h3,h4{color:$fg !important;}
        *{-webkit-user-select:text !important;user-select:text !important;}
        .libra-hl{border-radius:3px;}
        $highlightCss
        </style>
    """.trimIndent()
}

private val SCRIPT_REGEX = Regex("<script[\\s\\S]*?</script\\s*>", RegexOption.IGNORE_CASE)

private fun injectStyle(rawHtml: String, mode: Int, highlights: List<Highlight>): String {
    val html = SCRIPT_REGEX.replace(rawHtml, "")
    val style = styleFor(mode, highlights)
    val index = html.indexOf("</head>", ignoreCase = true)
    return if (index >= 0) html.substring(0, index) + style + html.substring(index) else style + html
}

/**
 * Pinta los subrayados guardados sobre el texto del capítulo buscando
 * las cadenas exactas e insertando spans coloreados.
 * Trabaja solo con el BODY para no romper la estructura del HTML.
 */
private fun paintHighlights(html: String, highlights: List<Highlight>): String {
    if (highlights.isEmpty()) return html
    var result = html
    highlights.forEach { h ->
        val escaped = h.selectedText
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val colorClass = "libra-hl-${h.colorArgb.toString(16)}"
        val span = """<span class="libra-hl $colorClass" data-libra-id="${h.id}">$escaped</span>"""
        result = result.replace(escaped, span, ignoreCase = false)
    }
    return result
}

private fun emptyResponse(status: Int, reason: String) =
    WebResourceResponse("text/plain", "utf-8", status, reason, emptyMap(),
        ByteArrayInputStream(ByteArray(0)))

private fun serveEpub(pkg: EpubPackage, theme: EpubTheme, uri: Uri,
    chapterHighlights: List<Highlight>): WebResourceResponse {
    if (uri.host != EPUB_HOST) return emptyResponse(403, "Blocked")
    return try {
        val path = uri.path?.removePrefix("/") ?: return emptyResponse(404, "Not found")
        val bytes = pkg.readBytes(path) ?: return emptyResponse(404, "Not found")
        val mime = pkg.mimeOf(path)
        if (mime.contains("html")) {
            var styled = injectStyle(String(bytes, Charsets.UTF_8), theme.mode, chapterHighlights)
            styled = paintHighlights(styled, chapterHighlights)
            WebResourceResponse("text/html", "utf-8",
                ByteArrayInputStream(styled.toByteArray(Charsets.UTF_8)))
        } else {
            WebResourceResponse(mime, null, ByteArrayInputStream(bytes))
        }
    } catch (_: Exception) { emptyResponse(404, "Not found") }
}

@Composable
private fun EpubReader(
    item: LibraryItem,
    onSave: (Long, Int) -> Unit,
    highlightDao: HighlightDao,
    onUpdateItem: (LibraryItem) -> Unit
) {
    val pkg = remember(item.id) { runCatching { EpubPackage(File(item.localPath)) }.getOrNull() }
    DisposableEffect(pkg) { onDispose { pkg?.close() } }
    if (pkg == null || pkg.spine.isEmpty()) {
        Message("No se pudo abrir este EPUB. Puede estar dañado o protegido con DRM.")
        return
    }

    val theme = remember { EpubTheme() }
    var themeMode by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableIntStateOf(100) }
    var index by remember(item.id) {
        mutableIntStateOf(item.lastPosition.toInt().coerceIn(0, pkg.spine.lastIndex))
    }
    var goingForward by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    var dictWord by remember { mutableStateOf<String?>(null) }
    var pendingHighlight by remember { mutableStateOf<String?>(null) }

    // Highlights del capítulo actual para pintarlos en el WebView
    val allHighlights by highlightDao.observeByBook(item.id).collectAsState(initial = emptyList())
    val chapterHighlights = remember(allHighlights, index) {
        allHighlights.filter { it.chapterIndex == index }
    }

    LaunchedEffect(index) { onSave(index.toLong(), (index + 1) * 100 / pkg.spine.size) }

    val isHorizontal = item.readingMode == "HORIZONTAL"

    Column(Modifier.fillMaxSize()) {
        if (isHorizontal) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                PagedContent(state = index to themeMode, goingForward = goingForward,
                    modifier = Modifier.fillMaxSize()) {
                    EpubWebView(pkg, theme, it.first, it.second, zoom, chapterHighlights,
                        onWordSelected = { w -> dictWord = w },
                        onTextSelected = { t -> pendingHighlight = t },
                        onChapterLink = { target -> goingForward = target > index; index = target },
                        onSwipe = { forward ->
                            if (forward && index < pkg.spine.lastIndex) { goingForward = true; index++ }
                            else if (!forward && index > 0) { goingForward = false; index-- }
                        })
                }
            }
        } else {
            EpubWebView(pkg, theme, index, themeMode, zoom, chapterHighlights,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                onWordSelected = { w -> dictWord = w },
                onTextSelected = { t -> pendingHighlight = t },
                onChapterLink = { target -> index = target },
                onSwipe = null)
        }

        // Barra inferior
        Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (index > 0) { goingForward = false; index-- } }) {
                Text("‹", color = Gold, fontSize = 24.sp) }
            Text("${index + 1} / ${pkg.spine.size}", color = Muted, fontSize = 12.sp)
            TextButton(onClick = { if (index < pkg.spine.lastIndex) { goingForward = true; index++ } }) {
                Text("›", color = Gold, fontSize = 24.sp) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { zoom = (zoom - 10).coerceAtLeast(70) }) { Text("A−", color = Gold) }
            TextButton(onClick = { zoom = (zoom + 10).coerceAtMost(220) }) { Text("A+", color = Gold) }
            TextButton(onClick = { themeMode = (themeMode + 1) % 3 }) { Text("◐", color = Gold, fontSize = 18.sp) }
        }
    }

    dictWord?.let { DictionaryDialog(word = it, onDismiss = { dictWord = null }) }

    pendingHighlight?.let { text ->
        HighlightColorPicker(
            selectedText = text,
            onConfirm = { colorArgb, note ->
                scope.launch {
                    highlightDao.insert(Highlight(
                        id = UUID.randomUUID().toString(),
                        bookId = item.id, bookTitle = item.title,
                        chapterIndex = index,
                        chapterTitle = "Capítulo ${index + 1}",
                        selectedText = text, note = note, colorArgb = colorArgb
                    ))
                }
                pendingHighlight = null
            },
            onDismiss = { pendingHighlight = null }
        )
    }
}

private class SelectionBridge(
    private val onWord: (String) -> Unit,
    private val onText: (String) -> Unit
) {
    @JavascriptInterface
    fun onSelected(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        Handler(Looper.getMainLooper()).post {
            if (clean.split(Regex("\\s+")).size == 1) onWord(clean) else onText(clean)
        }
    }
}

private const val SELECTION_JS = """
(function () {
  if (window.__libraSel) return;
  window.__libraSel = true;
  var timer = null;
  document.addEventListener('selectionchange', function () {
    clearTimeout(timer);
    timer = setTimeout(function () {
      var sel = window.getSelection();
      var t = sel ? sel.toString().trim() : '';
      if (t.length > 1) {
        LibraAndroid.onSelected(t);
        sel.removeAllRanges();
      }
    }, 900);
  });
})();
"""

@Composable
private fun EpubWebView(
    pkg: EpubPackage,
    theme: EpubTheme,
    index: Int,
    themeMode: Int,
    zoom: Int,
    chapterHighlights: List<Highlight>,
    modifier: Modifier = Modifier,
    onWordSelected: (String) -> Unit,
    onTextSelected: (String) -> Unit,
    onChapterLink: (Int) -> Unit,
    onSwipe: ((Boolean) -> Unit)?
) {
    val wordCb by rememberUpdatedState(onWordSelected)
    val textCb by rememberUpdatedState(onTextSelected)
    val linkCb by rememberUpdatedState(onChapterLink)
    val swipeCb by rememberUpdatedState(onSwipe)
    // Cuando cambian los highlights forzamos recarga
    val hlKey = remember(chapterHighlights) { chapterHighlights.joinToString { it.id } }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(0xFF171716.toInt())
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                addJavascriptInterface(
                    SelectionBridge({ w -> wordCb(w) }, { t -> textCb(t) }),
                    "LibraAndroid"
                )
                val detector = GestureDetector(ctx,
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onFling(e1: MotionEvent?, e2: MotionEvent,
                            velocityX: Float, velocityY: Float): Boolean {
                            val start = e1 ?: return false
                            val dx = e2.x - start.x; val dy = e2.y - start.y
                            if (abs(dx) > 140f && abs(dx) > abs(dy) * 1.5f && abs(velocityX) > 400f)
                                swipeCb?.invoke(dx < 0)
                            return false
                        }
                    })
                setOnTouchListener { _, event -> detector.onTouchEvent(event); false }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?,
                        request: WebResourceRequest?): WebResourceResponse? {
                        val uri = request?.url ?: return null
                        return serveEpub(pkg, theme, uri, chapterHighlights)
                    }
                    override fun shouldOverrideUrlLoading(view: WebView?,
                        request: WebResourceRequest?): Boolean {
                        val uri = request?.url ?: return true
                        if (uri.host != EPUB_HOST) return true
                        val path = uri.path?.removePrefix("/")
                        val target = if (path != null) pkg.spine.indexOf(path) else -1
                        if (target >= 0) {
                            view?.tag = chapterUrl(pkg.spine[target]) + "|" + theme.mode
                            linkCb(target)
                        }
                        return false
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript(SELECTION_JS, null)
                    }
                }
            }
        },
        update = { wv ->
            theme.mode = themeMode
            wv.settings.textZoom = zoom
            val url = chapterUrl(pkg.spine[index])
            // Incluimos hlKey en la clave para recargar cuando cambien highlights
            val key = "$url|$themeMode|$hlKey"
            if (wv.tag != key) {
                wv.tag = key
                wv.loadUrl(url)
            }
        }
    )
}

// ══════════════════════════════════════════════════════════════════════════════
// PDF
// ══════════════════════════════════════════════════════════════════════════════

private class PdfHolder(file: File) {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private val mutex = Mutex()
    val pageCount: Int = renderer.pageCount

    suspend fun render(index: Int, width: Int): Bitmap = mutex.withLock {
        withContext(Dispatchers.IO) {
            val page = renderer.openPage(index)
            try {
                val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            } finally { page.close() }
        }
    }

    fun close() { runCatching { renderer.close() }; runCatching { pfd.close() } }
}

@Composable
private fun PdfReader(
    item: LibraryItem, onSave: (Long, Int) -> Unit, readingMode: String,
    highlightDao: HighlightDao, onUpdateItem: (LibraryItem) -> Unit
) {
    val holder = remember(item.id) { runCatching { PdfHolder(File(item.localPath)) }.getOrNull() }
    DisposableEffect(holder) { onDispose { holder?.close() } }
    if (holder == null || holder.pageCount == 0) {
        Message("No se pudo abrir este PDF. Puede estar protegido con contraseña.")
        return
    }

    if (readingMode == "HORIZONTAL") {
        var index by remember(item.id) {
            mutableIntStateOf(item.lastPosition.toInt().coerceIn(0, holder.pageCount - 1))
        }
        var goingForward by remember { mutableStateOf(true) }
        LaunchedEffect(index) { onSave(index.toLong(), (index + 1) * 100 / holder.pageCount) }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val widthPx = constraints.maxWidth
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth().pageSwipe(
                    onNext = { if (index < holder.pageCount - 1) { goingForward = true; index++ } },
                    onPrev = { if (index > 0) { goingForward = false; index-- } }
                )) {
                    PagedContent(state = index, goingForward = goingForward,
                        modifier = Modifier.fillMaxSize()) { pg ->
                        val bitmap by produceState<Bitmap?>(null, pg, widthPx) {
                            value = try { holder.render(pg, widthPx) } catch (_: Exception) { null }
                        }
                        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth(), ContentScale.FillWidth) }
                            ?: Box(Modifier.fillMaxSize().background(Panel))
                    }
                }
                Row(Modifier.fillMaxWidth().background(Panel).padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { if (index > 0) { goingForward = false; index-- } }) {
                        Text("‹", color = Gold, fontSize = 24.sp) }
                    Text("${index + 1} / ${holder.pageCount}", color = Muted, fontSize = 12.sp)
                    TextButton(onClick = { if (index < holder.pageCount - 1) { goingForward = true; index++ } }) {
                        Text("›", color = Gold, fontSize = 24.sp) }
                }
            }
        }
    } else {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val widthPx = constraints.maxWidth
            val state = rememberLazyListState(
                initialFirstVisibleItemIndex = item.lastPosition.toInt().coerceIn(0, holder.pageCount - 1))
            LaunchedEffect(state) {
                snapshotFlow { state.firstVisibleItemIndex }.collect {
                    onSave(it.toLong(), (it + 1) * 100 / holder.pageCount)
                }
            }
            LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                items(holder.pageCount) { i ->
                    val bitmap by produceState<Bitmap?>(null, i, widthPx) {
                        value = try { holder.render(i, widthPx) } catch (_: Exception) { null }
                    }
                    Column(Modifier.fillMaxWidth()) {
                        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth(), ContentScale.FillWidth) }
                            ?: Box(Modifier.fillMaxWidth().aspectRatio(0.707f).background(Panel))
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// TXT / FB2
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun TextFileReader(
    item: LibraryItem, onSave: (Long, Int) -> Unit, fb2: Boolean,
    highlightDao: HighlightDao, onUpdateItem: (LibraryItem) -> Unit
) {
    val loaded by produceState<List<String>?>(null, item.id) {
        value = withContext(Dispatchers.IO) {
            try {
                val file = File(item.localPath)
                if (fb2) Fb2Parser.parse(file).paragraphs
                else file.readText().split(Regex("\\r?\\n"))
            } catch (_: Exception) { emptyList() }
        }
    }
    when {
        loaded == null -> Message("Abriendo…")
        loaded!!.isEmpty() -> Message("No se pudo leer el contenido de este archivo.")
        else -> ParagraphList(loaded!!, item, onSave, highlightDao)
    }
}

@Composable
private fun ParagraphList(
    paragraphs: List<String>, item: LibraryItem,
    onSave: (Long, Int) -> Unit, highlightDao: HighlightDao
) {
    var fontSize by remember { mutableIntStateOf(17) }
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = item.lastPosition.toInt().coerceIn(0, paragraphs.lastIndex))
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex }.collect {
            onSave(it.toLong(), (it + 1) * 100 / paragraphs.size)
        }
    }
    val scope = rememberCoroutineScope()
    var dictWord by remember { mutableStateOf<String?>(null) }
    var pendingText by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val allHighlights by highlightDao.observeByBook(item.id).collectAsState(initial = emptyList())

    Column(Modifier.fillMaxSize()) {
        LazyColumn(state = state, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp)) {
            items(paragraphs.size) { i ->
                val p = paragraphs[i]
                if (p.isBlank()) Spacer(Modifier.height(10.dp))
                else SelectableText(text = p, fontSize = fontSize,
                    highlight = allHighlights.firstOrNull { it.chapterIndex == i },
                    onWordLongPress = { word -> dictWord = word },
                    onTextSelected = { text -> pendingText = text to i })
            }
        }
        Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Párrafo ${state.firstVisibleItemIndex + 1} / ${paragraphs.size}",
                color = Muted, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { fontSize = (fontSize - 1).coerceAtLeast(12) }) { Text("A−", color = Gold) }
            TextButton(onClick = { fontSize = (fontSize + 1).coerceAtMost(34) }) { Text("A+", color = Gold) }
        }
    }

    dictWord?.let { DictionaryDialog(word = it, onDismiss = { dictWord = null }) }

    pendingText?.let { (text, parIdx) ->
        HighlightColorPicker(
            selectedText = text,
            onConfirm = { colorArgb, note ->
                scope.launch {
                    highlightDao.insert(Highlight(
                        id = UUID.randomUUID().toString(),
                        bookId = item.id, bookTitle = item.title,
                        chapterIndex = parIdx, chapterTitle = "Párrafo ${parIdx + 1}",
                        selectedText = text, note = note, colorArgb = colorArgb
                    ))
                }
                pendingText = null
            },
            onDismiss = { pendingText = null }
        )
    }
}

@Composable
private fun SelectableText(
    text: String, fontSize: Int,
    highlight: Highlight?,
    onWordLongPress: (String) -> Unit,
    onTextSelected: (String) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var askWord by remember { mutableStateOf(false) }
    var wordInput by remember { mutableStateOf("") }

    val bgColor = if (highlight != null)
        Color(highlight.colorArgb).copy(alpha = 0.35f)
    else Color.Transparent

    Box(
        Modifier.fillMaxWidth().padding(vertical = 5.dp)
            .background(bgColor, RoundedCornerShape(4.dp))
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { showMenu = true })
            }
    ) {
        Text(text, color = Cream, fontSize = fontSize.sp, lineHeight = (fontSize * 1.55f).sp)
    }

    if (showMenu) {
        AlertDialog(onDismissRequest = { showMenu = false }, containerColor = Panel,
            title = { Text("Opciones de texto", color = Cream) },
            text = {
                Column {
                    TextButton(onClick = { showMenu = false; wordInput = ""; askWord = true }) {
                        Text("🔍 Buscar una palabra", color = Gold) }
                    TextButton(onClick = { showMenu = false; onTextSelected(text) }) {
                        Text("🖍️ Subrayar párrafo", color = Gold) }
                }
            },
            confirmButton = { TextButton(onClick = { showMenu = false }) { Text("Cancelar", color = Muted) } }
        )
    }

    if (askWord) {
        AlertDialog(onDismissRequest = { askWord = false }, containerColor = Panel,
            title = { Text("¿Qué palabra buscas?", color = Cream) },
            text = {
                OutlinedTextField(value = wordInput, onValueChange = { wordInput = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Cream, unfocusedTextColor = Cream,
                        focusedBorderColor = Gold, unfocusedBorderColor = Muted))
            },
            confirmButton = {
                TextButton(onClick = { askWord = false; if (wordInput.isNotBlank()) onWordLongPress(wordInput.trim()) }) {
                    Text("Buscar", color = Gold) }
            },
            dismissButton = { TextButton(onClick = { askWord = false }) { Text("Cancelar", color = Muted) } }
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// CBZ
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun CbzReader(item: LibraryItem, onSave: (Long, Int) -> Unit, readingMode: String) {
    val zip = remember(item.id) { runCatching { ZipFile(File(item.localPath)) }.getOrNull() }
    DisposableEffect(zip) { onDispose { runCatching { zip?.close() } } }
    if (zip == null) { Message("No se pudo abrir este CBZ."); return }

    val pages = remember(zip) {
        zip.entries().toList()
            .filter { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS }
            .sortedBy { naturalKey(it.name) }.map { it.name }
    }
    if (pages.isEmpty()) { Message("Este CBZ no contiene imágenes."); return }

    if (readingMode == "HORIZONTAL") {
        var index by remember(item.id) {
            mutableIntStateOf(item.lastPosition.toInt().coerceIn(0, pages.lastIndex))
        }
        var goingForward by remember { mutableStateOf(true) }
        LaunchedEffect(index) { onSave(index.toLong(), (index + 1) * 100 / pages.size) }

        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().pageSwipe(
                onNext = { if (index < pages.lastIndex) { goingForward = true; index++ } },
                onPrev = { if (index > 0) { goingForward = false; index-- } }
            )) {
                PagedContent(state = index, goingForward = goingForward,
                    modifier = Modifier.fillMaxSize()) { pg ->
                    val bitmap by produceState<Bitmap?>(null, pages[pg]) {
                        value = withContext(Dispatchers.IO) {
                            try {
                                val entry = zip.getEntry(pages[pg])
                                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                                decodeSampled(bytes, 1080)
                            } catch (_: Exception) { null }
                        }
                    }
                    bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth(), ContentScale.FillWidth) }
                        ?: Box(Modifier.fillMaxSize().background(Panel))
                }
            }
            Row(Modifier.fillMaxWidth().background(Panel).padding(4.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { if (index > 0) { goingForward = false; index-- } }) {
                    Text("‹", color = Gold, fontSize = 24.sp) }
                Text("${index + 1} / ${pages.size}", color = Muted, fontSize = 12.sp)
                TextButton(onClick = { if (index < pages.lastIndex) { goingForward = true; index++ } }) {
                    Text("›", color = Gold, fontSize = 24.sp) }
            }
        }
    } else {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val widthPx = constraints.maxWidth
            val state = rememberLazyListState(
                initialFirstVisibleItemIndex = item.lastPosition.toInt().coerceIn(0, pages.lastIndex))
            LaunchedEffect(state) {
                snapshotFlow { state.firstVisibleItemIndex }.collect {
                    onSave(it.toLong(), (it + 1) * 100 / pages.size)
                }
            }
            LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                items(pages, key = { it }) { name ->
                    val bitmap by produceState<Bitmap?>(null, name, widthPx) {
                        value = withContext(Dispatchers.IO) {
                            try {
                                val entry = zip.getEntry(name)
                                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                                decodeSampled(bytes, widthPx)
                            } catch (_: Exception) { null }
                        }
                    }
                    bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth(), ContentScale.FillWidth) }
                        ?: Box(Modifier.fillMaxWidth().aspectRatio(0.7f).background(Panel))
                }
            }
        }
    }
}
