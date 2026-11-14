package com.libra.reader

import androidx.compose.ui.graphics.Color

// ── Colores base de la app ──────────────────────────────────────────────────
internal val Ink    = Color(0xFF171716)
internal val Panel  = Color(0xFF242320)
internal val Cream  = Color(0xFFF1E8D8)
internal val Gold   = Color(0xFFC5A46D)
internal val Muted  = Color(0xFFB9B0A2)

// ── Colores pastel para subrayado (texto oscuro legible sobre ellos) ────────
internal val HighlightYellow  = Color(0xFFFFF176)   // amarillo suave
internal val HighlightGreen   = Color(0xFFB9F6CA)   // verde menta
internal val HighlightBlue    = Color(0xFFB3E5FC)   // azul cielo
internal val HighlightPink    = Color(0xFFF8BBD0)   // rosa pálido
internal val HighlightLavender= Color(0xFFE1BEE7)   // lavanda
internal val HighlightPeach   = Color(0xFFFFCCBC)   // melocotón

/** Lista ordenada de colores de subrayado con su nombre para la UI */
val HIGHLIGHT_COLORS = listOf(
    0xFFFFF176L to "Amarillo",
    0xFFB9F6CAL to "Verde",
    0xFFB3E5FCL to "Azul",
    0xFFF8BBD0L to "Rosa",
    0xFFE1BEE7L to "Lavanda",
    0xFFFFCCBCL to "Melocotón"
)
