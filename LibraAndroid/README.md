# Libra — Android 0.4.0

Proyecto Android nativo con Kotlin, Jetpack Compose, Room y Media3.

## Funciones
- Biblioteca local (Room): importación múltiple de EPUB, PDF, TXT, FB2, CBZ y CBR, y de audiolibros MP3, M4A, M4B, AAC, OGG, OPUS, WAV y FLAC. Cada archivo se copia al almacenamiento privado de la app.
- Escáner de carpetas: elige una carpeta y Libra encuentra los libros que haya dentro.
- Portadas y metadatos automáticos (EPUB, PDF, CBZ, FB2 y audio).
- Lector: EPUB, PDF, TXT, FB2 y CBZ, con modo de scroll vertical o pasar hoja, tamaño de letra y temas. Guarda por dónde ibas. CBR se importa pero aún no se lee.
- Subrayados y notas por libro, con colores pastel y vista global en la pestaña «Notas».
- Diccionario en línea (español e inglés) al seleccionar una palabra en un EPUB.
- Audiolibros con reproducción en segundo plano, ±15/30 s, velocidad y posición guardada.
- Favoritos, búsqueda por título o autor y separador de página.
- Biblioteca en lista, cuadrícula o estantería con movimiento libre de los libros.
- Copias de seguridad exportables (.zip) con libros, audios, portadas, progreso, favoritos, orden de la estantería y notas.

## Limitaciones conocidas
- CBR (RAR) no se lee todavía.
- En EPUB se guarda el capítulo, no la posición exacta dentro del capítulo.
- Los EPUB con DRM no se abren.
- Android no permite elegir la carpeta «Descargas» completa en el escáner; para esos archivos usa «Importar».

## Compilar
- Android Studio Ladybug o posterior, JDK 17, Android SDK 35.
- Sin Android Studio: sube `LibraAndroid.zip` y el workflow `.github/workflows/build.yml` a GitHub; el APK sale en Actions → artefacto `app-debug`.
