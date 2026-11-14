package com.libra.reader

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class TutorialPage(
    val emoji: String,
    val title: String,
    val description: String
)

private val PAGES = listOf(
    TutorialPage(
        "📚",
        "Bienvenido a Libra",
        "Tu biblioteca personal, privada y sin conexión. Todos tus libros guardados en tu dispositivo."
    ),
    TutorialPage(
        "➕",
        "Importa tus libros",
        "Pulsa «Importar» para añadir EPUB, PDF, TXT, FB2 o CBZ. Con «Escanear» eliges una carpeta y Libra encuentra los libros que haya dentro."
    ),
    TutorialPage(
        "📖",
        "Lector con animación",
        "Abre cualquier libro y desliza para pasar páginas. Elige entre modo vertical (scroll) u horizontal (swipe con animación de hoja)."
    ),
    TutorialPage(
        "🖍️",
        "Subrayado y notas",
        "Mantén pulsado un texto para seleccionarlo, elige un color pastel y añade una nota. Tus subrayados se guardan con el libro, capítulo y página."
    ),
    TutorialPage(
        "📝",
        "Gestión de notas",
        "Desde el menú del lector accede a todas tus notas del libro. En la pestaña «Notas» de la app verás los subrayados de TODOS tus libros."
    ),
    TutorialPage(
        "🔍",
        "Diccionario integrado",
        "En un EPUB, selecciona una palabra y se abre su definición (necesita internet). Si seleccionas varias palabras, podrás subrayarlas y añadir una nota."
    ),
    TutorialPage(
        "🔖",
        "Separador de páginas",
        "Personaliza el separador de tu libro: sin separador, con la portada del libro o con un diseño decorativo. ¡Recuerda dónde lo dejaste con estilo!"
    ),
    TutorialPage(
        "📚",
        "Estantería virtual",
        "En Biblioteca → Estantería arrastra tus libros donde quieras, ponlos en pie o acostados. ¡Organiza tu biblioteca como quieras!"
    )
)

@Composable
fun TutorialScreen(onFinish: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    val current = PAGES[page]

    Box(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .systemBarsPadding()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Indicadores de progreso
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 40.dp)
            ) {
                PAGES.indices.forEach { i ->
                    Box(
                        Modifier
                            .size(if (i == page) 24.dp else 8.dp, 8.dp)
                            .clip(CircleShape)
                            .background(if (i == page) Gold else Muted)
                    )
                }
            }

            // Contenido animado
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    if (targetState > initialState) {
                        slideInHorizontally { it } + fadeIn() togetherWith
                                slideOutHorizontally { -it } + fadeOut()
                    } else {
                        slideInHorizontally { -it } + fadeIn() togetherWith
                                slideOutHorizontally { it } + fadeOut()
                    }
                },
                label = "tutorial_page"
            ) { p ->
                val pg = PAGES[p]
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(pg.emoji, fontSize = 72.sp)
                    Spacer(Modifier.height(24.dp))
                    Text(
                        pg.title,
                        color = Cream,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        pg.description,
                        color = Muted,
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(Modifier.height(60.dp))

            // Botones
            if (page < PAGES.lastIndex) {
                Button(
                    onClick = { page++ },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Siguiente", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onFinish) {
                    Text("Saltar tutorial", color = Muted)
                }
            } else {
                Button(
                    onClick = onFinish,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("¡Empezar a leer!", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // Navegación por puntos (atrás)
            if (page > 0) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { page-- }) {
                    Text("← Anterior", color = Muted, fontSize = 13.sp)
                }
            }
        }
    }
}
