package com.libra.reader

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class FoundBook(
    val uri: Uri,
    val name: String,
    val extension: String,
    val sizeKb: Long
)

private val SCAN_EXTENSIONS = setOf("epub", "pdf", "txt", "fb2", "cbz")

/**
 * Busca libros en el almacenamiento del dispositivo usando MediaStore.
 * Funciona en Android 10+ sin necesitar READ_EXTERNAL_STORAGE.
 */
suspend fun scanDeviceForBooks(context: Context): List<FoundBook> = withContext(Dispatchers.IO) {
    val results = mutableListOf<FoundBook>()
    val resolver: ContentResolver = context.contentResolver

    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Files.getContentUri("external")
    }

    val projection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.SIZE,
        MediaStore.Files.FileColumns.DATA
    )

    // Filtramos por extensión con LIKE
    val selectionParts = SCAN_EXTENSIONS.joinToString(" OR ") {
        "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
    }
    val selectionArgs = SCAN_EXTENSIONS.map { "%.${it}" }.toTypedArray()

    try {
        resolver.query(
            collection,
            projection,
            selectionParts,
            selectionArgs,
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in SCAN_EXTENSIONS) continue
                val sizeKb = cursor.getLong(sizeCol) / 1024
                val uri = android.content.ContentUris.withAppendedId(collection, id)
                results.add(FoundBook(uri, name, ext, sizeKb))
            }
        }
    } catch (_: Exception) {
        // MediaStore puede fallar en algunos dispositivos; retornamos lo que tengamos
    }

    results
}

// ── Diálogo del escáner ───────────────────────────────────────────────────────

@Composable
fun ScannerDialog(
    onImport: (List<Uri>) -> Unit,
    onDismiss: () -> Unit,
    context: Context
) {
    var scanning by remember { mutableStateOf(true) }
    var found by remember { mutableStateOf<List<FoundBook>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<Uri>>(emptySet()) }

    LaunchedEffect(Unit) {
        found = scanDeviceForBooks(context)
        scanning = false
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
                    "📱 Libros en tu dispositivo",
                    color = Cream,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Selecciona los que quieres importar a Libra",
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
                                Text("Escaneando…", color = Muted)
                            }
                        }
                    }
                    found.isEmpty() -> {
                        Box(
                            Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No se encontraron libros en el dispositivo.\n\nAsegúrate de que tienes permisos de almacenamiento.",
                                color = Muted,
                                fontSize = 14.sp
                            )
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

                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Muted)
                    ) { Text("Cancelar") }

                    Button(
                        onClick = {
                            if (selected.isNotEmpty()) {
                                onImport(selected.toList())
                            }
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
