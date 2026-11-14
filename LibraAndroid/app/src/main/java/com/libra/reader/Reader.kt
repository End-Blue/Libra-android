@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.libra.reader

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
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
import kotlin.math.absoluteValue

// ══════════════════════════════════════════════════════════════════════════════
// PANTALLA PRINCIPAL DEL LECTOR
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun ReaderScreen(
    item: LibraryItem,
    onClose: () -> Unit,
    onSave: (Long, Int) -> Unit,
    highlightDao: HighlightDao,
    onUpdateItem: (LibraryItem) -> Unit   // para guardar readingMode, bookmarkStyle
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    BackHandler(onBack = onClose)

    var showMenu by remember { mutableStateOf(false) }
    var showHighlights by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val highlights by highlightDao.observeByBook(item.id).collectAsState(initial = emptyList())
    var editingHighlight by remember { mutableStateOf<Highlight?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .systemBarsPadding()
    ) {
        // ── Barra superior ────────────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onClose) { Text("‹ Volver", color = Gold) }
            Text(
                item.title, color = Cream, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // Botón notas del libro
            TextButton(onClick = { showHighlights = true }) {
                Text("📝", fontSize = 18.sp)
            }
            // Botón ajustes del lector
            TextButton(onClick = { showSettings = true }) {
                Text("⚙", color = Muted, fontSize = 18.sp)
            }
        }

        // ── Contenido del lector ──────────────────────────────────────────────
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (item.format.lowercase()) {
                "epub" -> EpubReader(item, onSave, highlightDao, onUpdateItem)
                "pdf"  -> PdfReader(item, onSave, item.readingMode, highlightDao, onUpdateItem)
                "txt"  -> TextFileReader(item, onSave, fb2 = false, highlightDao, onUpdateItem)
                "fb2"  -> TextFileReader(item, onSave, fb2 = true,  highlightDao, onUpdateItem)
                "cbz"  -> CbzReader(item, onSave, item.readingMode)
                else   -> Message(
                    "Los archivos CBR (RAR) todavía no se pueden leer dentro de Libra. " +
                    "Conviértelo a CBZ y vuelve a importarlo."
                )
            }
        }

        // ── Separador de página (bookmark visual) ─────────────────────────────
        if (item.bookmarkStyle.isNotBlank()) {
            BookmarkDivider(item)
        }
    }

    // ── Hoja de notas del libro ───────────────────────────────────────────────
    if (showHighlights) {
        ModalBottomSheet(
            onDismissRequest = { showHighlights = false },
            containerColor = Panel
        ) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
                Text(
                    "NOTAS DE ESTE LIBRO",
                    color = Gold, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                )
                Spacer(Modifier.height(12.dp))
                BookHighlightsScreen(
                    highlights = highlights,
                    onEdit = { editingHighlight = it },
                    onDelete = { h -> scope.launch { highlightDao.deleteById(h.id) } }
                )
            }
        }
    }

    // ── Hoja de ajustes del lector ────────────────────────────────────────────
    if (showSettings) {
        ReaderSettingsSheet(
            item = item,
            onDismiss = { showSettings = false },
            onUpdateItem = onUpdateItem
        )
    }

    // ── Diálogo de edición de nota ────────────────────────────────────────────
    editingHighlight?.let { h ->
        HighlightEditDialog(
            highlight = h,
            onSave = { updated ->
                scope.launch { highlightDao.update(updated) }
                editingHighlight = null
            },
            onDismiss = { editingHighlight = null }
        )
    }
}

// ── Ajustes del lector ────────────────────────────────────────────────────────

@Composable
fun ReaderSettingsSheet(
    item: LibraryItem,
    onDismiss: () -> Unit,
    onUpdateItem: (LibraryItem) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Panel
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "AJUSTES DEL LECTOR",
                color = Gold, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp
            )

            // Modo de lectura
            Text("Modo de paso de página", color = Cream, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("VERTICAL" to "↕ Scroll vertical", "HORIZONTAL" to "↔ Pasar hoja").forEach { (mode, label) ->
                    FilterChip(
                        selected = item.readingMode == mode,
                        onClick = { onUpdateItem(item.copy(readingMode = mode)) },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Gold,
                            selectedLabelColor = Ink,
                            labelColor = Cream
                        )
                    )
                }
            }

            HorizontalDivider(color = Ink)

            // Separador de página
            Text("Separador de página", color = Cream, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Aparece al final del lector para recordar dónde lo dejaste.",
                color = Muted, fontSize = 12.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    "" to "🚫 Ninguno",
                    "COVER" to "📖 Portada",
                    "RIBBON" to "🎀 Cinta"
                ).forEach { (style, label) ->
                    FilterChip(
                        selected = item.bookmarkStyle == style,
                        onClick = { onUpdateItem(item.copy(bookmarkStyle = style)) },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Gold,
                            selectedLabelColor = Ink,
                            labelColor = Cream
                        )
                    )
                }
            }
        }
    }
}

// ── Separador visual de página ─────────────────────────────────────────────────

@Composable
fun BookmarkDivider(item: LibraryItem) {
    when (item.bookmarkStyle) {
        "COVER" -> {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Panel)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BookCover(item, Modifier.size(width = 25.dp, height = 36.dp))
                Spacer(Modifier.width(10.dp))
                Text("Continuarás aquí", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text("🔖", fontSize = 18.sp)
            }
        }
        "RIBBON" -> {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(Gold)
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// HELPERS COMUNES
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Cream, fontSize = 15.sp, lineHeight = 22.sp, textAlign = TextAlign.Center)
    }
}

/** Animación de paso de página horizontal (efecto slide + fade) */
@Composable
private fun <T> PagedContent(
    state: T,
    goingForward: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit
) {
    AnimatedContent(
        targetState = state,
        transitionSpec = {
            if (goingForward) {
                (slideInHorizontally { it } + fadeIn(tween(220))).togetherWith(
                    slideOutHorizontally { -it } + fadeOut(tween(220))
                )
            } else {
                (slideInHorizontally { -it } + fadeIn(tween(220))).togetherWith(
                    slideOutHorizontally { it } + fadeOut(tween(220))
                )
            }
        },
        label = "page_anim",
        modifier = modifier
    ) { s -> content(s) }
}

// ══════════════════════════════════════════════════════════════════════════════
// EPUB
// ══════════════════════════════════════════════════════════════════════════════

private const val EPUB_HOST = "libra.local"

private class EpubTheme { @Volatile var mode: Int = 0 }

private fun chapterUrl(path: String): String =
    Uri.Builder().scheme("https").authority(EPUB_HOST)
        .encodedPath("/" + Uri.encode(path, "/")).build().toString()

private fun styleFor(mode: Int): String {
    val (bg, fg, link) = when (mode) {
        1 -> Triple("#F4ECD8", "#3B2F1E", "#8A5A1F")
        2 -> Triple("#FFFFFF", "#111111", "#1A5FB4")
        else -> Triple("#171716", "#F1E8D8", "#C5A46D")
    }
    return "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
        "<style>html,body{background:$bg !important;color:$fg !important;}" +
        "body{padding:14px 18px !important;line-height:1.6 !important;margin:0 !important;}" +
        "a{color:$link !important;}img,svg{max-width:100% !important;height:auto !important;}" +
        "p,div,span,li,h1,h2,h3,h4{color:$fg !important;}" +
        // Permite selección de texto para el subrayado
        "* { -webkit-user-select: text !important; user-select: text !important; }" +
        "</style>"
}

private fun injectStyle(html: String, mode: Int): String {
    val style = styleFor(mode)
    val index = html.indexOf("</head>", ignoreCase = true)
    return if (index >= 0) html.substring(0, index) + style + html.substring(index) else style + html
}

private fun emptyResponse(status: Int, reason: String) =
    WebResourceResponse("text/plain", "utf-8", status, reason, emptyMap(),
        ByteArrayInputStream(ByteArray(0)))

private fun serveEpub(pkg: EpubPackage, theme: EpubTheme, uri: Uri): WebResourceResponse {
    if (uri.host != EPUB_HOST) return emptyResponse(403, "Blocked")
    return try {
        val path = uri.path?.removePrefix("/") ?: return emptyResponse(404, "Not found")
        val bytes = pkg.readBytes(path) ?: return emptyResponse(404, "Not found")
        val mime = pkg.mimeOf(path)
        if (mime.contains("html")) {
            val styled = injectStyle(String(bytes, Charsets.UTF_8), theme.mode)
            WebResourceResponse("text/html", "utf-8",
                ByteArrayInputStream(styled.toByteArray(Charsets.UTF_8)))
        } else {
            WebResourceResponse(mime, null, ByteArrayInputStream(bytes))
        }
    } catch (_: Exception) {
        emptyResponse(404, "Not found")
    }
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

    // Para diccionario
    var dictWord by remember { mutableStateOf<String?>(null) }
    // Para subrayado
    var pendingHighlight by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(index) { onSave(index.toLong(), (index + 1) * 100 / pkg.spine.size) }

    val isHorizontal = item.readingMode == "HORIZONTAL"

    Column(Modifier.fillMaxSize()) {
        if (isHorizontal) {
            // Modo horizontal con animación
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = {}
                        ) { _, dragAmount ->
                            if (dragAmount < -50 && index < pkg.spine.lastIndex) {
                                goingForward = true; index++
                            } else if (dragAmount > 50 && index > 0) {
                                goingForward = false; index--
                            }
                        }
                    }
            ) {
                PagedContent(state = index to themeMode, goingForward = goingForward,
                    modifier = Modifier.fillMaxSize()) {
                    EpubWebView(pkg, theme, it.first, it.second, zoom,
                        onWordSelected = { w -> dictWord = w },
                        onTextSelected = { t -> pendingHighlight = t }
                    )
                }
            }
        } else {
            // Modo vertical (scroll normal)
            EpubWebView(
                pkg, theme, index, themeMode, zoom,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                onWordSelected = { w -> dictWord = w },
                onTextSelected = { t -> pendingHighlight = t }
            )
        }

        // Barra inferior
        Row(
            Modifier.fillMaxWidth().background(Panel).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { if (index > 0) { goingForward = false; index-- } }) {
                Text("‹", color = Gold, fontSize = 24.sp)
            }
            Text("${index + 1} / ${pkg.spine.size}", color = Muted, fontSize = 12.sp)
            TextButton(onClick = { if (index < pkg.spine.lastIndex) { goingForward = true; index++ } }) {
                Text("›", color = Gold, fontSize = 24.sp)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { zoom = (zoom - 10).coerceAtLeast(70) }) { Text("A−", color = Gold) }
            TextButton(onClick = { zoom = (zoom + 10).coerceAtMost(220) }) { Text("A+", color = Gold) }
            TextButton(onClick = { themeMode = (themeMode + 1) % 3 }) {
                Text("◐", color = Gold, fontSize = 18.sp)
            }
        }
    }

    // Diccionario
    dictWord?.let { w ->
        DictionaryDialog(word = w, onDismiss = { dictWord = null })
    }

    // Subrayado
    pendingHighlight?.let { text ->
        HighlightColorPicker(
            selectedText = text,
            onConfirm = { colorArgb, note ->
                scope.launch {
                    highlightDao.insert(
                        Highlight(
                            id = UUID.randomUUID().toString(),
                            bookId = item.id,
                            bookTitle = item.title,
                            chapterIndex = index,
                            chapterTitle = "Capítulo ${index + 1}",
                            selectedText = text,
                            note = note,
                            colorArgb = colorArgb
                        )
                    )
                }
                pendingHighlight = null
            },
            onDismiss = { pendingHighlight = null }
        )
    }
}

@Composable
private fun EpubWebView(
    pkg: EpubPackage,
    theme: EpubTheme,
    index: Int,
    themeMode: Int,
    zoom: Int,
    modifier: Modifier = Modifier,
    onWordSelected: (String) -> Unit,
    onTextSelected: (String) -> Unit
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(0xFF171716.toInt())
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?, request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val uri = request?.url ?: return null
                        return serveEpub(pkg, theme, uri)
                    }
                    override fun shouldOverrideUrlLoading(
                        view: WebView?, request: WebResourceRequest?
                    ): Boolean {
                        val uri = request?.url ?: return true
                        if (uri.host != EPUB_HOST) return true
                        return false
                    }
                }
                // JS para capturar selección de texto y enviarla vía título de página
                addJavascriptInterface(object {
                    @android.webkit.JavascriptInterface
                    fun onTextSelected(text: String) {
                        if (text.split(" ").size == 1) onWordSelected(text)
                        else onTextSelected(text)
                    }
                }, "LibraAndroid")
            }
        },
        update = { wv ->
            theme.mode = themeMode
            wv.settings.textZoom = zoom
            val url = chapterUrl(pkg.spine[index])
            val key = "$url|$themeMode"
            if (wv.tag != key) {
                wv.tag = key
                wv.loadUrl(url)
                // Inyectar listener de selección tras cargar
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?, request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val uri = request?.url ?: return null
                        return serveEpub(pkg, theme, uri)
                    }
                    override fun onPageFinished(view: WebView?, url2: String?) {
                        wv.evaluateJavascript("""
                            document.addEventListener('selectionchange', function() {
                                var t = window.getSelection().toString().trim();
                                if (t.length > 1) LibraAndroid.onTextSelected(t);
                            });
                        """.trimIndent(), null)
                    }
                }
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

    fun close() {
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }
}

@Composable
private fun PdfReader(
    item: LibraryItem,
    onSave: (Long, Int) -> Unit,
    readingMode: String,
    highlightDao: HighlightDao,
    onUpdateItem: (LibraryItem) -> Unit
) {
    val holder = remember(item.id) { runCatching { PdfHolder(File(item.localPath)) }.getOrNull() }
    DisposableEffect(holder) { onDispose { holder?.close() } }
    if (holder == null || holder.pageCount == 0) {
        Message("No se pudo abrir este PDF. Puede estar protegido con contraseña.")
        return
    }

    val scope = rememberCoroutineScope()
    var dictWord by remember { mutableStateOf<String?>(null) }

    if (readingMode == "HORIZONTAL") {
        // Modo horizontal: una página a la vez con animación
        var index by remember(item.id) {
            mutableIntStateOf(item.lastPosition.toInt().coerceIn(0, holder.pageCount - 1))
        }
        var goingForward by remember { mutableStateOf(true) }
        LaunchedEffect(index) { onSave(index.toLong(), (index + 1) * 100 / holder.pageCount) }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val widthPx = constraints.maxWidth

            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures { _, dragAmount ->
                                if (dragAmount < -60 && index < holder.pageCount - 1) {
                                    goingForward = true; index++
                                } else if (dragAmount > 60 && index > 0) {
                                    goingForward = false; index--
                                }
                            }
                        }
                ) {
                    PagedContent(state = index, goingForward = goingForward,
                        modifier = Modifier.fillMaxSize()) { pg ->
                        val bitmap by produceState<Bitmap?>(null, pg, widthPx) {
                            value = try { holder.render(pg, widthPx) } catch (_: Exception) { null }
                        }
                        bitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.FillWidth
                            )
                        } ?: Box(Modifier.fillMaxSize().background(Panel))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().background(Panel).padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    TextButton(onClick = { if (index > 0) { goingForward = false; index-- } }) {
                        Text("‹", color = Gold, fontSize = 24.sp)
                    }
                    Text("${index + 1} / ${holder.pageCount}", color = Muted, fontSize = 12.sp)
                    TextButton(onClick = { if (index < holder.pageCount - 1) { goingForward = true; index++ } }) {
                        Text("›", color = Gold, fontSize = 24.sp)
                    }
                }
            }
        }
    } else {
        // Modo vertical: scroll
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val widthPx = constraints.maxWidth
            val state = rememberLazyListState(
                initialFirstVisibleItemIndex = item.lastPosition.toInt().coerceIn(0, holder.pageCount - 1)
            )
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
                    bitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.FillWidth
                        )
                    } ?: Box(Modifier.fillMaxWidth().aspectRatio(0.707f).background(Panel))
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }

    dictWord?.let { DictionaryDialog(word = it, onDismiss = { dictWord = null }) }
}

// ══════════════════════════════════════════════════════════════════════════════
// TXT / FB2
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun TextFileReader(
    item: LibraryItem,
    onSave: (Long, Int) -> Unit,
    fb2: Boolean,
    highlightDao: HighlightDao,
    onUpdateItem: (LibraryItem) -> Unit
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
    paragraphs: List<String>,
    item: LibraryItem,
    onSave: (Long, Int) -> Unit,
    highlightDao: HighlightDao
) {
    var fontSize by remember { mutableIntStateOf(17) }
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = item.lastPosition.toInt().coerceIn(0, paragraphs.lastIndex)
    )
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex }.collect {
            onSave(it.toLong(), (it + 1) * 100 / paragraphs.size)
        }
    }

    val scope = rememberCoroutineScope()
    var dictWord by remember { mutableStateOf<String?>(null) }
    var pendingText by remember { mutableStateOf<Pair<String, Int>?>(null) }  // text, parIdx

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = state,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp)
        ) {
            items(paragraphs.size) { i ->
                val p = paragraphs[i]
                if (p.isBlank()) {
                    Spacer(Modifier.height(10.dp))
                } else {
                    SelectableText(
                        text = p,
                        fontSize = fontSize,
                        onWordLongPress = { word -> dictWord = word },
                        onTextSelected = { text -> pendingText = text to i }
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Párrafo ${state.firstVisibleItemIndex + 1} / ${paragraphs.size}",
                color = Muted, fontSize = 12.sp
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { fontSize = (fontSize - 1).coerceAtLeast(12) }) {
                Text("A−", color = Gold)
            }
            TextButton(onClick = { fontSize = (fontSize + 1).coerceAtMost(34) }) {
                Text("A+", color = Gold)
            }
        }
    }

    dictWord?.let { DictionaryDialog(word = it, onDismiss = { dictWord = null }) }

    pendingText?.let { (text, parIdx) ->
        HighlightColorPicker(
            selectedText = text,
            onConfirm = { colorArgb, note ->
                scope.launch {
                    highlightDao.insert(
                        Highlight(
                            id = UUID.randomUUID().toString(),
                            bookId = item.id,
                            bookTitle = item.title,
                            chapterIndex = parIdx,
                            chapterTitle = "Párrafo ${parIdx + 1}",
                            selectedText = text,
                            note = note,
                            colorArgb = colorArgb
                        )
                    )
                }
                pendingText = null
            },
            onDismiss = { pendingText = null }
        )
    }
}

/** Texto seleccionable con callback al mantener pulsado */
@Composable
private fun SelectableText(
    text: String,
    fontSize: Int,
    onWordLongPress: (String) -> Unit,
    onTextSelected: (String) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var lastSelected by remember { mutableStateOf("") }

    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = {
                        // En texto plano, abrimos el picker con todo el párrafo
                        lastSelected = text
                        showMenu = true
                    }
                )
            }
    ) {
        Text(
            text, color = Cream,
            fontSize = fontSize.sp,
            lineHeight = (fontSize * 1.55f).sp
        )
    }

    if (showMenu) {
        AlertDialog(
            onDismissRequest = { showMenu = false },
            containerColor = Panel,
            title = { Text("Opciones de texto", color = Cream) },
            text = {
                Column {
                    TextButton(onClick = {
                        showMenu = false
                        onWordLongPress(text.split(" ").firstOrNull() ?: "")
                    }) { Text("🔍 Buscar en diccionario", color = Gold) }
                    TextButton(onClick = {
                        showMenu = false
                        onTextSelected(text)
                    }) { Text("🖍️ Subrayar párrafo", color = Gold) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMenu = false }) { Text("Cancelar", color = Muted) }
            }
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// CBZ
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun CbzReader(
    item: LibraryItem,
    onSave: (Long, Int) -> Unit,
    readingMode: String
) {
    val zip = remember(item.id) { runCatching { ZipFile(File(item.localPath)) }.getOrNull() }
    DisposableEffect(zip) { onDispose { runCatching { zip?.close() } } }
    if (zip == null) { Message("No se pudo abrir este CBZ."); return }

    val pages = remember(zip) {
        zip.entries().toList()
            .filter { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS }
            .sortedBy { naturalKey(it.name) }
            .map { it.name }
    }
    if (pages.isEmpty()) { Message("Este CBZ no contiene imágenes."); return }

    if (readingMode == "HORIZONTAL") {
        var index by remember(item.id) {
            mutableIntStateOf(item.lastPosition.toInt().coerceIn(0, pages.lastIndex))
        }
        var goingForward by remember { mutableStateOf(true) }
        LaunchedEffect(index) { onSave(index.toLong(), (index + 1) * 100 / pages.size) }

        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { _, dragAmount ->
                            if (dragAmount < -60 && index < pages.lastIndex) { goingForward = true; index++ }
                            else if (dragAmount > 60 && index > 0) { goingForward = false; index-- }
                        }
                    }
            ) {
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
                    bitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.FillWidth
                        )
                    } ?: Box(Modifier.fillMaxSize().background(Panel))
                }
            }
            Row(
                Modifier.fillMaxWidth().background(Panel).padding(4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { if (index > 0) { goingForward = false; index-- } }) {
                    Text("‹", color = Gold, fontSize = 24.sp)
                }
                Text("${index + 1} / ${pages.size}", color = Muted, fontSize = 12.sp)
                TextButton(onClick = { if (index < pages.lastIndex) { goingForward = true; index++ } }) {
                    Text("›", color = Gold, fontSize = 24.sp)
                }
            }
        }
    } else {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val widthPx = constraints.maxWidth
            val state = rememberLazyListState(
                initialFirstVisibleItemIndex = item.lastPosition.toInt().coerceIn(0, pages.lastIndex)
            )
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
                    bitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.FillWidth
                        )
                    } ?: Box(Modifier.fillMaxWidth().aspectRatio(0.7f).background(Panel))
                }
            }
        }
    }
}
