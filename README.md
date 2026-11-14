# Libra — prototipo Android 0.3.0

Proyecto Android nativo con Kotlin, Jetpack Compose, Room y Media3.

## Funciones
- Biblioteca local (Room) con importación múltiple de EPUB, PDF, TXT, FB2, CBZ y CBR, y de audiolibros MP3, M4A, M4B, AAC, OGG, OPUS, WAV y FLAC. Cada archivo se copia al almacenamiento privado de la app.
- **Portadas y metadatos automáticos**: EPUB (portada, título, autor), PDF (primera página), CBZ (primera imagen), FB2 (portada, título, autor) y audio (carátula incrustada, título, artista, duración).
- **Lector**: EPUB (capítulos, tamaño de letra, tema oscuro/sepia/claro), PDF (desplazamiento continuo), TXT, FB2 y CBZ. Guarda por dónde ibas. CBR (RAR) se importa pero aún no se puede leer.
- **Reproductor de audiolibros** (Media3): reproducción en segundo plano con notificación, ±15/30 s, velocidad, barra de progreso y posición guardada.
- **Estantería con movimiento libre**: arrastra cada libro donde quieras; la posición se guarda. Tocar abre el libro; «Acostar/Levantar» cambia su orientación; «Ordenar» recoloca todo.
- **Copias de seguridad exportables**: Ajustes → exportar un .zip con libros, audiolibros, portadas, progreso y orden de la estantería; restaurar en otro teléfono.

## Limitaciones conocidas
- CBR no se lee (necesita una librería RAR).
- EPUB guarda el capítulo, no la posición exacta dentro del capítulo.
- EPUB con DRM no se abre.
- Aún pendiente: estantería 3D y selector de materiales.

## Compilar
- Android Studio Ladybug o posterior, JDK 17, Android SDK 35.
- Sin Android Studio: sube el proyecto a GitHub; `.github/workflows/build.yml` compila el APK en la pestaña Actions (artefacto `app-debug`).
