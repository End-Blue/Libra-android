package com.libra.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.text.SimpleDateFormat
import java.util.*

// ── Pantalla global: todos los subrayados de todos los libros ─────────────────

@Composable
fun AllHighlightsScreen(
    highlights: List<Highlight>,
    onEdit: (Highlight) -> Unit,
    onDelete: (Highlight) -> Unit
) {
    if (highlights.isEmpty()) {
        EmptyFeature(
            "SIN NOTAS AÚN",
            "Cuando subrayes texto en cualquier libro, tus notas y subrayados aparecerán aquí con el nombre del libro, capítulo y página."
        )
        return
    }

    // Agrupar por libro
    val byBook = highlights.groupBy { it.bookTitle }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        byBook.forEach { (bookTitle, list) ->
            item {
                Text(
                    bookTitle.uppercase(),
                    color = Gold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )
            }
            items(list, key = { it.id }) { h ->
                HighlightCard(h, onEdit = { onEdit(h) }, onDelete = { onDelete(h) })
            }
        }
    }
}

// ── Pantalla por libro: subrayados de un libro concreto ───────────────────────

@Composable
fun BookHighlightsScreen(
    highlights: List<Highlight>,
    onEdit: (Highlight) -> Unit,
    onDelete: (Highlight) -> Unit
) {
    if (highlights.isEmpty()) {
        EmptyFeature(
            "SIN NOTAS EN ESTE LIBRO",
            "Mantén pulsado cualquier texto mientras lees para subrayarlo y añadir una nota."
        )
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(highlights, key = { it.id }) { h ->
            HighlightCard(h, showBook = false, onEdit = { onEdit(h) }, onDelete = { onDelete(h) })
        }
    }
}

// ── Tarjeta de un subrayado ───────────────────────────────────────────────────

@Composable
fun HighlightCard(
    h: Highlight,
    showBook: Boolean = false,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val color = Color(h.colorArgb)
    val fmt = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }

    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            // Cabecera: color + capítulo
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(1.dp, Muted, CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    h.chapterTitle,
                    color = Gold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(fmt.format(Date(h.createdAt)), color = Muted, fontSize = 10.sp)
            }

            if (showBook) {
                Text(
                    h.bookTitle,
                    color = Muted,
                    fontSize = 11.sp,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            // Texto subrayado con color de fondo pastel
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(color.copy(alpha = 0.25f))
                    .padding(10.dp)
            ) {
                Text(
                    "\"${h.selectedText}\"",
                    color = Cream,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontStyle = FontStyle.Italic
                )
            }

            // Nota del lector
            if (h.note.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "📝 ${h.note}",
                    color = Cream,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }

            // Acciones
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onEdit) { Text("Editar", color = Gold, fontSize = 12.sp) }
                TextButton(onClick = onDelete) { Text("Eliminar", color = Muted, fontSize = 12.sp) }
            }
        }
    }
}

// ── Diálogo para añadir/editar nota de un subrayado ──────────────────────────

@Composable
fun HighlightEditDialog(
    highlight: Highlight,
    onSave: (Highlight) -> Unit,
    onDismiss: () -> Unit
) {
    var note by remember { mutableStateOf(highlight.note) }
    var selectedColor by remember { mutableLongStateOf(highlight.colorArgb) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Panel,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    "Editar nota",
                    color = Cream,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(12.dp))

                // Selector de color
                Text("Color de subrayado", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HIGHLIGHT_COLORS.forEach { (argb, name) ->
                        val c = Color(argb)
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(
                                    if (selectedColor == argb) 3.dp else 1.dp,
                                    if (selectedColor == argb) Cream else Muted,
                                    CircleShape
                                )
                                .clickable { selectedColor = argb }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Texto subrayado (sólo lectura)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(selectedColor).copy(alpha = 0.25f))
                        .padding(10.dp)
                ) {
                    Text(
                        "\"${highlight.selectedText}\"",
                        color = Cream,
                        fontSize = 13.sp,
                        fontStyle = FontStyle.Italic,
                        lineHeight = 18.sp
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Campo de nota
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Nota (opcional)", color = Muted) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Cream,
                        unfocusedTextColor = Cream,
                        focusedBorderColor = Gold,
                        unfocusedBorderColor = Muted
                    )
                )

                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Muted)
                    ) { Text("Cancelar") }
                    Button(
                        onClick = {
                            onSave(highlight.copy(note = note, colorArgb = selectedColor))
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)
                    ) { Text("Guardar") }
                }
            }
        }
    }
}

// ── Selector de color para subrayar texto nuevo ───────────────────────────────

@Composable
fun HighlightColorPicker(
    selectedText: String,
    onConfirm: (colorArgb: Long, note: String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedColor by remember { mutableLongStateOf(HIGHLIGHT_COLORS.first().first) }
    var note by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Panel,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    "Subrayar texto",
                    color = Cream,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(12.dp))

                // Vista previa del texto seleccionado
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(selectedColor).copy(alpha = 0.3f))
                        .padding(10.dp)
                ) {
                    Text(
                        "\"${selectedText.take(120)}${if (selectedText.length > 120) "…" else ""}\"",
                        color = Cream,
                        fontSize = 13.sp,
                        fontStyle = FontStyle.Italic,
                        lineHeight = 18.sp
                    )
                }

                Spacer(Modifier.height(14.dp))

                // Selector de color
                Text("Elige un color", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HIGHLIGHT_COLORS.forEach { (argb, name) ->
                        val c = Color(argb)
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(
                                    if (selectedColor == argb) 3.dp else 1.dp,
                                    if (selectedColor == argb) Cream else Muted,
                                    CircleShape
                                )
                                .clickable { selectedColor = argb }
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Añadir nota (opcional)", color = Muted) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Cream,
                        unfocusedTextColor = Cream,
                        focusedBorderColor = Gold,
                        unfocusedBorderColor = Muted
                    )
                )

                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Muted)
                    ) { Text("Cancelar") }
                    Button(
                        onClick = { onConfirm(selectedColor, note) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)
                    ) { Text("Subrayar") }
                }
            }
        }
    }
}
