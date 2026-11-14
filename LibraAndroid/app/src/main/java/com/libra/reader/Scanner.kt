package com.libra.reader

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class FoundBook(
    val uri: Uri,
    val name: String,
    val extension: String,
    val sizeKb: Long
)

private val SCAN_EXTENSIONS = setOf("epub", "pdf", "txt", "fb2", "cbz")
private const val MAX_FILES = 3000
private const val MAX_DEPTH = 8

/**
 * Recorre una carpeta elegida por el usuario (Storage Access Framework) buscando libros.
 * No necesita permisos de almacenamiento: el propio selector de carpetas concede el acceso.
 */
suspend fun scanFolderForBooks(context: Context, tree: Uri): List<FoundBook> = withContext(Dispatchers.IO) {
    val root = DocumentFile.fromTreeUri(context, tree) ?: return@withContext emptyList()
    val results = mutableListOf<FoundBook>()

    fun walk(dir: DocumentFile, depth: Int) {
        if (depth > MAX_DEPTH || results.size >= MAX_FILES) return
        val children = try {
            dir.listFiles()
        } catch (_: Exception) {
            return
        }
        for (f in children) {
            if (results.size >= MAX_FILES) return
            if (f.isDirectory) {
                walk(f, depth + 1)
            } else {
                val name = f.name ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext in SCAN_EXTENSIONS) results.add(FoundBook(f.uri, name, ext, f.length() / 1024))
            }
        }
    }

    walk(root, 0)
    results.sortedBy { it.name.lowercase() }
}

// ── Diálogo del escáner ───────────────────────────────────────────────────────

@Composable
fun ScannerDialog(
    onImport: (List<Uri>) -> Unit,
    onDismiss: () -> Unit,
    context: Context
) {
    var treeUri by remember { mutableStateOf<Uri?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<FoundBook>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<Uri>>(emptySet()) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) treeUri = uri
    }

    // Al abrir el escáner se pide la carpeta directamente
    LaunchedEffect(Unit) { picker.launch(null) }

    LaunchedEffect(treeUri) {
        val tree = treeUri
        if (tree != null) {
            scanning = true
            found = scanFolderForBooks(context, tree)
            selected = emptySet()
            scanning = false
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Panel,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    "📱 Buscar libros en una carpeta",
                    color = Cream,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Elige una carpeta y Libra encontrará los libros que haya dentro, incluidas las subcarpetas.",
                    color = Muted,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(12.dp))

                when {
                    scanning -> {
                        Box(
                            Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = Gold)
                                Spacer(Modifier.height(12.dp))
                                Text("Buscando…", color = Muted)
                            }
                        }
                    }
                    treeUri == null || found.isEmpty() -> {
                        Box(
                            Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    if (treeUri == null) "Aún no has elegido ninguna carpeta."
                                    else "No se encontraron libros en esa carpeta.",
                                    color = Cream,
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Android no deja elegir «Descargas» completa: para los archivos que estén " +
                                        "ahí usa el botón «Importar».",
                                    color = Muted,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = { picker.launch(null) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)
                                ) { Text("Elegir carpeta") }
                            }
                        }
                    }
                    else -> {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${found.size} archivos encontrados", color = Muted, fontSize = 12.sp)
                            TextButton(onClick = {
                                selected = if (selected.size == found.size) emptySet()
                                else found.map { it.uri }.toSet()
                            }) {
                                Text(
                                    if (selected.size == found.size) "Deseleccionar todo" else "Seleccionar todo",
                                    color = Gold, fontSize = 12.sp
                                )
                            }
                        }

                        LazyColumn(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(found, key = { it.uri.toString() }) { book ->
                                val isSelected = book.uri in selected
                                // Un solo hijo por item: fila + separador dentro de una Column
                                Column {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selected = if (isSelected) selected - book.uri
                                                else selected + book.uri
                                            }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = {
                                                selected = if (it) selected + book.uri else selected - book.uri
                                            },
                                            colors = CheckboxDefaults.colors(checkedColor = Gold)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                book.name,
                                                color = Cream,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                "${book.extension.uppercase()} · ${book.sizeKb} KB",
                                                color = Muted,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                    HorizontalDivider(color = Ink, thickness = 0.5.dp)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Muted)
                    ) { Text("Cancelar") }

                    Button(
                        onClick = {
                            if (selected.isNotEmpty()) onImport(selected.toList())
                            onDismiss()
                        },
                        enabled = selected.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)
                    ) {
                        Text(
                            if (selected.isEmpty()) "Importar"
                            else "Importar (${selected.size})"
                        )
                    }
                }
            }
        }
    }
}
