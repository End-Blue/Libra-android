package com.libra.reader

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LibraApp() }
    }
}

@Composable
fun LibraApp() {
    val context = LocalContext.current
    val db = remember { LibraryDatabase.get(context) }
    val dao = remember { db.libraryDao() }
    val highlightDao = remember { db.highlightDao() }

    val allItems by dao.observeAll().collectAsState(initial = emptyList())
    val allHighlights by highlightDao.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val controller by rememberMediaController()
    PlaybackPersistence(controller, dao)

    val prefs = remember { context.getSharedPreferences("libra_prefs", 0) }
    var showTutorial by remember { mutableStateOf(!prefs.getBoolean("tutorial_done", false)) }
    var permissionsRequested by remember {
        mutableStateOf(prefs.getBoolean("perms_requested", false))
    }

    var selectedTab by remember { mutableStateOf("Inicio") }
    var viewMode by remember { mutableStateOf("LISTA") }
    var status by remember { mutableStateOf("Tu biblioteca está guardada en este dispositivo.") }
    var busy by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<LibraryItem?>(null) }
    var showScanner by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var editingHighlight by remember { mutableStateOf<Highlight?>(null) }

    // ── Permiso de almacenamiento (pedido al arrancar, una sola vez) ──────────
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* resultado ignorado; el escáner maneja el caso sin permiso */ }

    LaunchedEffect(permissionsRequested) {
        if (!permissionsRequested) {
            val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO
                )
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            permLauncher.launch(perms)
            prefs.edit().putBoolean("perms_requested", true).apply()
            permissionsRequested = true
        }
    }

    // ── Lanzadores ────────────────────────────────────────────────────────────
    val bookPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            busy = true; status = "Importando…"
            status = withContext(Dispatchers.IO) {
                importUris(context.contentResolver, context.filesDir, uris, "BOOK", dao)
            }
            busy = false
        }
    }
    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            busy = true; status = "Importando audio…"
            status = withContext(Dispatchers.IO) {
                importUris(context.contentResolver, context.filesDir, uris, "AUDIO", dao)
            }
            busy = false
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) scope.launch {
            busy = true; status = "Creando copia de seguridad…"
            status = Backup.export(context, dao, highlightDao, uri); busy = false
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            busy = true; status = "Restaurando copia de seguridad…"
            status = Backup.restore(context, dao, highlightDao, uri); busy = false
        }
    }

    val onDelete: (LibraryItem) -> Unit = { item ->
        scope.launch {
            removeItem(dao, item)
            highlightDao.deleteByBook(item.id)
        }
    }

    // ── Tutorial ──────────────────────────────────────────────────────────────
    if (showTutorial) {
        TutorialScreen(onFinish = {
            showTutorial = false
            prefs.edit().putBoolean("tutorial_done", true).apply()
        })
        return
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Ink, surface = Panel, primary = Gold,
            onBackground = Cream, onSurface = Cream, onPrimary = Ink
        )
    ) {
        val current = opened
        if (current != null) {
            if (current.mediaType == "AUDIO") {
                AudioPlayerScreen(current, controller, onClose = { opened = null })
            } else {
                ReaderScreen(
                    item = current,
                    onClose = { opened = null },
                    onSave = { position, progress ->
                        scope.launch { dao.updateProgress(current.id, position, progress) }
                    },
                    highlightDao = highlightDao,
                    onUpdateItem = { updated -> scope.launch { dao.update(updated) } }
                )
            }
        } else {
            Scaffold(
                containerColor = Ink,
                bottomBar = {
                    NavigationBar(containerColor = Panel, contentColor = Cream) {
                        listOf("Inicio", "Biblioteca", "Notas", "Audio", "Ajustes").forEach { tab ->
                            NavigationBarItem(
                                selected = selectedTab == tab,
                                onClick = { selectedTab = tab },
                                icon = {
                                    Text(
                                        when (tab) {
                                            "Inicio" -> "⌂"; "Biblioteca" -> "▤"
                                            "Notas" -> "📝"; "Audio" -> "♫"; else -> "⚙"
                                        }, fontSize = 20.sp
                                    )
                                },
                                label = { Text(tab, fontSize = 10.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Gold, selectedTextColor = Gold,
                                    indicatorColor = Ink, unselectedIconColor = Muted,
                                    unselectedTextColor = Muted
                                )
                            )
                        }
                    }
                }
            ) { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)
                ) {
                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("LIBRA", color = Gold, fontSize = 13.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                            Text(
                                when (selectedTab) {
                                    "Inicio" -> "Tu biblioteca, a tu manera."
                                    "Biblioteca" -> "Todos tus libros"
                                    "Notas" -> "Tus subrayados y notas"
                                    "Audio" -> "Escucha tus historias"
                                    else -> "Personaliza tu experiencia"
                                },
                                color = Cream, fontSize = 24.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                        TextButton(onClick = { showTutorial = true }) {
                            Text("?", color = Muted, fontSize = 20.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    if (selectedTab in listOf("Inicio", "Biblioteca")) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Buscar libro o autor…", color = Muted, fontSize = 14.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Cream, unfocusedTextColor = Cream,
                                focusedBorderColor = Gold, unfocusedBorderColor = Panel,
                                focusedContainerColor = Panel, unfocusedContainerColor = Panel
                            ),
                            leadingIcon = { Text("🔍", fontSize = 16.sp) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty())
                                    TextButton(onClick = { searchQuery = "" }) { Text("✕", color = Muted) }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {})
                        )
                        Spacer(Modifier.height(10.dp))
                    }

                    if (selectedTab == "Inicio") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { bookPicker.launch(arrayOf("*/*")) },
                                enabled = !busy,
                                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
                                modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)
                            ) { Text("＋ Importar", fontSize = 12.sp) }
                            OutlinedButton(
                                onClick = { showScanner = true },
                                enabled = !busy, modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Gold)
                            ) { Text("📱 Escanear", fontSize = 12.sp) }
                        }
                        Text(status, color = Muted, fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                    }

                    val books = allItems.filter { it.mediaType == "BOOK" }
                    val filteredBooks = if (searchQuery.isBlank()) books
                    else books.filter {
                        it.title.contains(searchQuery, ignoreCase = true) ||
                        it.author.contains(searchQuery, ignoreCase = true)
                    }

                    when (selectedTab) {
                        "Inicio" -> HomeScreen(filteredBooks, onOpen = { opened = it },
                            onDelete = onDelete, onToggleFavorite = { item ->
                                scope.launch { dao.setFavorite(item.id, if (item.isFavorite == 1) 0 else 1) }
                            })
                        "Biblioteca" -> LibraryScreen(
                            filteredBooks, viewMode, onViewModeChange = { viewMode = it },
                            onOpen = { opened = it }, onDelete = onDelete,
                            onToggleFavorite = { item ->
                                scope.launch { dao.setFavorite(item.id, if (item.isFavorite == 1) 0 else 1) }
                            },
                            onToggleOrientation = { item ->
                                scope.launch {
                                    dao.update(item.copy(
                                        shelfOrientation =
                                            if (item.shelfOrientation == "VERTICAL") "HORIZONTAL" else "VERTICAL"
                                    ))
                                }
                            },
                            onMove = { id, x, y -> scope.launch { dao.updateShelfPosition(id, x, y) } },
                            onResetShelf = { scope.launch { dao.resetShelfPositions() } }
                        )
                        "Notas" -> AllHighlightsScreen(
                            highlights = allHighlights,
                            onEdit = { editingHighlight = it },
                            onDelete = { h -> scope.launch { highlightDao.deleteById(h.id) } }
                        )
                        "Audio" -> AudioScreen(
                            allItems.filter { it.mediaType == "AUDIO" },
                            onOpen = { opened = it }, onDelete = onDelete
                        )
                        else -> SettingsScreen(
                            allItems.size, busy,
                            onExport = {
                                val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                                exportLauncher.launch("libra-copia-$stamp.zip")
                            },
                            onImport = {
                                restoreLauncher.launch(arrayOf("application/zip",
                                    "application/octet-stream", "*/*"))
                            },
                            onShowTutorial = { showTutorial = true }
                        )
                    }
                }
            }
        }
    }

    if (showScanner) {
        ScannerDialog(
            context = context,
            onImport = { uris ->
                scope.launch {
                    busy = true; status = "Importando desde escáner…"
                    status = withContext(Dispatchers.IO) {
                        importUris(context.contentResolver, context.filesDir, uris, "BOOK", dao)
                    }
                    busy = false
                }
            },
            onDismiss = { showScanner = false }
        )
    }

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

// ══════════════════════════════════════════════════════════════════════════════
// PANTALLAS
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun HomeScreen(
    books: List<LibraryItem>,
    onOpen: (LibraryItem) -> Unit,
    onDelete: (LibraryItem) -> Unit,
    onToggleFavorite: (LibraryItem) -> Unit
) {
    if (books.isEmpty()) {
        EmptyFeature("TU BIBLIOTECA COMIENZA AQUÍ",
            "Importa tus archivos EPUB, PDF, TXT, FB2, CBZ o usa el escáner automático. " +
            "Libra guardará una copia privada en tu dispositivo.")
    } else {
        val favorites = books.filter { it.isFavorite == 1 }
        val rest = books.filter { it.isFavorite == 0 }
        LazyColumn {
            if (favorites.isNotEmpty()) {
                item {
                    Text("⭐ FAVORITOS · ${favorites.size}", color = Gold, fontSize = 11.sp,
                        letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 4.dp))
                }
                items(favorites, key = { it.id }) { BookRow(it, onOpen, onDelete, onToggleFavorite) }
                item { Spacer(Modifier.height(12.dp)) }
            }
            item {
                Text("LIBROS · ${rest.size}", color = Gold, fontSize = 11.sp,
                    letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
            items(rest, key = { it.id }) { BookRow(it, onOpen, onDelete, onToggleFavorite) }
        }
    }
}

@Composable
fun LibraryScreen(
    books: List<LibraryItem>,
    viewMode: String,
    onViewModeChange: (String) -> Unit,
    onOpen: (LibraryItem) -> Unit,
    onDelete: (LibraryItem) -> Unit,
    onToggleFavorite: (LibraryItem) -> Unit,
    onToggleOrientation: (LibraryItem) -> Unit,
    onMove: (String, Float, Float) -> Unit,
    onResetShelf: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${books.size} libros", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        listOf("LISTA" to "Lista", "CUADRICULA" to "Cuadrícula", "ESTANTERIA" to "Estantería")
            .forEach { (mode, label) ->
                TextButton(onClick = { onViewModeChange(mode) }) {
                    Text(label,
                        color = if (viewMode == mode) Gold else Muted,
                        fontWeight = if (viewMode == mode) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 12.sp)
                }
            }
    }
    if (books.isEmpty()) {
        EmptyFeature("AÚN NO HAY LIBROS", "Pulsa «Importar» o «Escanear» para añadir tu colección.")
    } else when (viewMode) {
        "ESTANTERIA" -> ShelfScreen(books, onOpen, onToggleOrientation, onMove, onResetShelf)
        "CUADRICULA" -> LazyColumn {
            items(books.chunked(2)) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEach { book ->
                        Column(
                            Modifier.weight(1f).padding(vertical = 8.dp).clickable { onOpen(book) }
                        ) {
                            BookCover(book, Modifier.fillMaxWidth().height(190.dp))
                            Spacer(Modifier.height(6.dp))
                            Text(book.title, color = Cream, fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${book.author} · ${book.format}", color = Muted, fontSize = 10.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        else -> LazyColumn {
            items(books, key = { it.id }) { BookRow(it, onOpen, onDelete, onToggleFavorite) }
        }
    }
}

@Composable
fun AudioScreen(audio: List<LibraryItem>, onOpen: (LibraryItem) -> Unit, onDelete: (LibraryItem) -> Unit) {
    if (audio.isEmpty()) {
        EmptyFeature("TUS AUDIOLIBROS",
            "Importa MP3, M4A, M4B, AAC, OGG, OPUS, WAV o FLAC. " +
            "Libra recuerda por dónde ibas y sigue sonando con la pantalla apagada.")
    } else {
        Text("${audio.size} archivos de audio importados", color = Gold, fontSize = 11.sp)
        LazyColumn {
            items(audio, key = { it.id }) { BookRow(it, onOpen, onDelete, onToggleFavorite = {}) }
        }
    }
}

@Composable
fun SettingsScreen(
    itemCount: Int, busy: Boolean,
    onExport: () -> Unit, onImport: () -> Unit, onShowTutorial: () -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            EmptyFeature("ALMACENAMIENTO LOCAL",
                "Libra mantiene $itemCount elementos en el almacenamiento privado de la aplicación.")
        }
        item {
            Button(onClick = onExport, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)
            ) { Text("Exportar copia de seguridad") }
        }
        item {
            OutlinedButton(onClick = onImport, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Gold)
            ) { Text("Restaurar desde una copia") }
        }
        item {
            OutlinedButton(onClick = onShowTutorial, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Gold)
            ) { Text("🎓 Ver tutorial de la app") }
        }
        item {
            Text("La copia es un archivo .zip con tus libros, audiolibros, portadas, progreso y " +
                "orden de la estantería. Guárdalo en Drive o en la tarjeta y restáuralo en otro teléfono.",
                color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// COMPONENTES REUTILIZABLES
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun EmptyFeature(title: String, description: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(18.dp)) {
            Text(title, color = Gold, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Text(description, color = Cream, fontSize = 14.sp, lineHeight = 21.sp)
        }
    }
}

@Composable
fun BookRow(
    item: LibraryItem,
    onOpen: (LibraryItem) -> Unit,
    onDelete: (LibraryItem) -> Unit,
    onToggleFavorite: (LibraryItem) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(item) }.padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BookCover(item, Modifier.size(width = 48.dp, height = 66.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, color = Cream, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${item.author} · ${item.format}", color = Muted, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.progress > 0) {
                Spacer(Modifier.height(5.dp))
                LinearProgressIndicator(
                    progress = { item.progress / 100f },
                    color = Gold, trackColor = Panel,
                    modifier = Modifier.fillMaxWidth().height(3.dp)
                )
            }
        }
        TextButton(onClick = { onToggleFavorite(item) }) {
            Text(if (item.isFavorite == 1) "⭐" else "☆", fontSize = 16.sp)
        }
        TextButton(onClick = { onDelete(item) }) { Text("Quitar", color = Muted, fontSize = 11.sp) }
    }
}

private fun coverColor(title: String): Color {
    val palette = listOf(
        Color(0xFF6B3540), Color(0xFF35564D),
        Color(0xFF43506D), Color(0xFF7A5C3A), Color(0xFF3B3430)
    )
    return palette[abs(title.hashCode()) % palette.size]
}

@Composable
fun BookCover(item: LibraryItem, modifier: Modifier = Modifier, textSize: TextUnit = 10.sp) {
    val cover = remember(item.coverPath) {
        item.coverPath?.let { File(it) }?.takeIf { it.exists() }
    }
    Box(modifier.clip(RoundedCornerShape(7.dp)).background(coverColor(item.title)),
        contentAlignment = Alignment.Center) {
        if (cover != null) {
            AsyncImage(model = cover, contentDescription = item.title,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(item.title, color = Cream, fontSize = textSize, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, maxLines = 6, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(5.dp))
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// ESTANTERÍA — libro "acostado" muestra solo el lomo (canto)
// ══════════════════════════════════════════════════════════════════════════════

private fun shelfSize(horizontal: Boolean): Pair<Dp, Dp> =
    if (horizontal) 110.dp to 28.dp   // lomo: ancho del libro, altura = grosor del lomo
    else 52.dp to 124.dp              // libro de pie

private val PLANK_LEVELS = listOf(0.30f, 0.62f, 0.94f)

private fun autoSlots(books: List<LibraryItem>, areaW: Int, areaH: Int, density: Density): Map<String, IntOffset> {
    val gap = with(density) { 4.dp.roundToPx() }
    val result = HashMap<String, IntOffset>(books.size)
    var row = 0; var cursor = 0
    books.forEach { b ->
        val (w, h) = shelfSize(b.shelfOrientation == "HORIZONTAL")
        val bw = with(density) { w.roundToPx() }
        val bh = with(density) { h.roundToPx() }
        if (cursor + bw > areaW && cursor > 0) { row++; cursor = 0 }
        val plankY = (areaH * PLANK_LEVELS[row % PLANK_LEVELS.size]).toInt()
        result[b.id] = IntOffset(cursor, (plankY - bh).coerceAtLeast(0))
        cursor += bw + gap
    }
    return result
}

@Composable
fun ShelfScreen(
    books: List<LibraryItem>,
    onOpen: (LibraryItem) -> Unit,
    onToggle: (LibraryItem) -> Unit,
    onMove: (String, Float, Float) -> Unit,
    onReset: () -> Unit
) {
    LazyColumn {
        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ARRASTRA · TOCA PARA ABRIR", color = Muted, fontSize = 10.sp,
                        letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = onReset) { Text("Ordenar", color = Gold, fontSize = 11.sp) }
                }
                Spacer(Modifier.height(8.dp))
                BoxWithConstraints(
                    Modifier.fillMaxWidth().height(400.dp)
                        .clip(RoundedCornerShape(14.dp)).background(Color(0xFF4A3022))
                ) {
                    val areaW = constraints.maxWidth
                    val areaH = constraints.maxHeight
                    val density = LocalDensity.current
                    val slots = remember(books.map { it.id to it.shelfOrientation }, areaW, areaH) {
                        autoSlots(books, areaW, areaH, density)
                    }
                    // Tablones
                    PLANK_LEVELS.forEach { level ->
                        Box(
                            Modifier.offset(y = maxHeight * level).fillMaxWidth().height(10.dp)
                                .background(Color(0xFFB18A5C))
                        )
                    }
                    // Libros
                    books.forEach { book ->
                        key(book.id) {
                            ShelfBook(book, slots[book.id] ?: IntOffset.Zero,
                                areaW, areaH, onOpen, onMove)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        // Lista de libros con toggle de orientación
        items(books, key = { it.id }) { book ->
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(book.title, color = Cream, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(book.format, color = Muted, fontSize = 10.sp)
                }
                TextButton(onClick = { onToggle(book) }) {
                    Text(if (book.shelfOrientation == "VERTICAL") "Acostar" else "Levantar", color = Gold)
                }
            }
        }
    }
}

@Composable
private fun ShelfBook(
    book: LibraryItem,
    slot: IntOffset,
    areaW: Int,
    areaH: Int,
    onOpen: (LibraryItem) -> Unit,
    onMove: (String, Float, Float) -> Unit
) {
    val density = LocalDensity.current
    val horizontal = book.shelfOrientation == "HORIZONTAL"
    val dims = shelfSize(horizontal)
    val bw = with(density) { dims.first.roundToPx() }
    val bh = with(density) { dims.second.roundToPx() }
    val maxX = (areaW - bw).coerceAtLeast(1)
    val maxY = (areaH - bh).coerceAtLeast(1)
    var x by remember(book.id, book.shelfX, areaW, bw, slot.x) {
        mutableFloatStateOf(
            if (book.shelfX >= 0f) book.shelfX * maxX
            else slot.x.toFloat().coerceIn(0f, maxX.toFloat())
        )
    }
    var y by remember(book.id, book.shelfY, areaH, bh, slot.y) {
        mutableFloatStateOf(
            if (book.shelfY >= 0f) book.shelfY * maxY
            else slot.y.toFloat().coerceIn(0f, maxY.toFloat())
        )
    }
    var dragging by remember { mutableStateOf(false) }

    Box(
        Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .zIndex(if (dragging) 10f else 0f)
            .size(dims.first, dims.second)
            .pointerInput(book.id, maxX, maxY) {
                detectDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = {
                        dragging = false
                        onMove(book.id, (x / maxX).coerceIn(0f, 1f), (y / maxY).coerceIn(0f, 1f))
                    },
                    onDragCancel = { dragging = false }
                ) { change, drag ->
                    change.consume()
                    x = (x + drag.x).coerceIn(0f, maxX.toFloat())
                    y = (y + drag.y).coerceIn(0f, maxY.toFloat())
                }
            }
            .clickable { onOpen(book) }
    ) {
        if (horizontal) {
            // ── Libro ACOSTADO: se ve solo el lomo (canto) ─────────────────
            // El lomo es una banda del color de la portada con el título rotado
            val spineColor = coverColor(book.title)
            val cover = remember(book.coverPath) {
                book.coverPath?.let { File(it) }?.takeIf { it.exists() }
            }
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(3.dp))
                    .background(spineColor),
                contentAlignment = Alignment.Center
            ) {
                if (cover != null) {
                    // Recorte del borde izquierdo de la portada como lomo aproximado
                    AsyncImage(
                        model = cover, contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    // Overlay oscuro para legibilidad del título
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
                }
                // Título rotado vertical (como en un lomo real)
                Text(
                    book.title,
                    color = Cream,
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .rotate(-90f)
                        .padding(horizontal = 2.dp)
                )
            }
        } else {
            // ── Libro DE PIE: portada completa ──────────────────────────────
            BookCover(book, Modifier.fillMaxSize(), textSize = 8.sp)
        }
    }
}
