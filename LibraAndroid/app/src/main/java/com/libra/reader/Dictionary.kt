package com.libra.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

// ── Modelos ───────────────────────────────────────────────────────────────────

data class DictMeaning(
    val partOfSpeech: String,
    val definitions: List<String>,
    val synonyms: List<String>
)

data class DictResult(
    val word: String,
    val phonetic: String,
    val meanings: List<DictMeaning>,
    val lang: String   // "es" o "en"
)

// ── Estrategia de búsqueda ────────────────────────────────────────────────────
// 1. Intenta español en dictionaryapi.dev (funciona para palabras comunes en ES)
// 2. Si falla, intenta inglés en dictionaryapi.dev
// 3. Si ambos fallan, consulta MyMemory como fallback de traducción

private fun openUrl(urlStr: String): String {
    val conn = URL(urlStr).openConnection()
    conn.connectTimeout = 7000
    conn.readTimeout = 7000
    conn.setRequestProperty("User-Agent", "LibraApp/1.0")
    return conn.getInputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
}

private fun parseDictionaryApi(json: String, lang: String): DictResult {
    val arr = JSONArray(json)
    val entry = arr.getJSONObject(0)
    val word = entry.optString("word", "")
    val phonetic = entry.optString("phonetic", "")
    val meaningsArr = entry.getJSONArray("meanings")
    val meanings = (0 until meaningsArr.length()).map { i ->
        val m = meaningsArr.getJSONObject(i)
        val pos = m.optString("partOfSpeech", "")
        val defs = m.getJSONArray("definitions")
        val defList = (0 until minOf(defs.length(), 3)).map { j ->
            defs.getJSONObject(j).optString("definition", "")
        }
        val syns = m.optJSONArray("synonyms")
        val synList = if (syns != null)
            (0 until minOf(syns.length(), 4)).map { j -> syns.getString(j) }
        else emptyList()
        DictMeaning(pos, defList, synList)
    }
    return DictResult(word, phonetic, meanings, lang)
}

// Fallback: si no hay definición usa MyMemory para traducir ES→EN y luego
// busca la definición en inglés de esa traducción.
private fun fetchTranslatedDefinition(word: String): DictResult? {
    return try {
        val enc = URLEncoder.encode(word, "UTF-8")
        val trJson = openUrl("https://api.mymemory.translated.net/get?q=$enc&langpair=es|en")
        val trObj = JSONObject(trJson)
        val translated = trObj.optJSONObject("responseData")?.optString("translatedText") ?: return null
        val enWord = translated.trim().lowercase().split(" ").firstOrNull() ?: return null
        val encEn = URLEncoder.encode(enWord, "UTF-8")
        val dictJson = openUrl("https://api.dictionaryapi.dev/api/v2/entries/en/$encEn")
        parseDictionaryApi(dictJson, "en").copy(word = "$word → $enWord")
    } catch (_: Exception) { null }
}

suspend fun fetchDefinition(word: String): Result<DictResult> = withContext(Dispatchers.IO) {
    val clean = word.trim().lowercase().trimEnd(',', '.', ';', ':', '!', '?', '"', '\'')
    if (clean.isEmpty()) return@withContext Result.failure(Exception("Palabra vacía"))

    // Intento 1: español
    val esResult = runCatching {
        val enc = URLEncoder.encode(clean, "UTF-8")
        parseDictionaryApi(openUrl("https://api.dictionaryapi.dev/api/v2/entries/es/$enc"), "es")
    }
    if (esResult.isSuccess) return@withContext esResult

    // Intento 2: inglés
    val enResult = runCatching {
        val enc = URLEncoder.encode(clean, "UTF-8")
        parseDictionaryApi(openUrl("https://api.dictionaryapi.dev/api/v2/entries/en/$enc"), "en")
    }
    if (enResult.isSuccess) return@withContext enResult

    // Intento 3: traducción + inglés
    val translated = runCatching {
        fetchTranslatedDefinition(clean) ?: throw Exception("No traducible")
    }
    translated
}

// ── UI ────────────────────────────────────────────────────────────────────────

@Composable
fun DictionaryDialog(word: String, onDismiss: () -> Unit) {
    var state by remember { mutableStateOf<Result<DictResult>?>(null) }

    LaunchedEffect(word) {
        state = null
        state = fetchDefinition(word)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Panel,
            modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📖 Diccionario", color = Gold, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("✕", color = Muted) }
                }
                Spacer(Modifier.height(4.dp))

                when {
                    state == null -> Box(
                        Modifier.fillMaxWidth().padding(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Gold, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.height(10.dp))
                            Text("Buscando «$word»…", color = Muted, fontSize = 12.sp)
                        }
                    }

                    state!!.isFailure -> Column {
                        Text("No se encontró «$word»", color = Cream, fontSize = 16.sp,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Comprueba la ortografía y que tengas conexión a internet.\n" +
                            "El diccionario funciona en español e inglés.",
                            color = Muted, fontSize = 14.sp, lineHeight = 20.sp
                        )
                    }

                    else -> {
                        val r = state!!.getOrNull()!!
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(r.word, color = Cream, fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Box(
                                    Modifier.background(
                                        if (r.lang == "es") Gold.copy(alpha = .2f) else Cream.copy(alpha = .1f),
                                        RoundedCornerShape(6.dp)
                                    ).padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(r.lang.uppercase(), color = Gold, fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold)
                                }
                            }
                            if (r.phonetic.isNotBlank())
                                Text(r.phonetic, color = Gold, fontSize = 14.sp,
                                    fontStyle = FontStyle.Italic)
                            Spacer(Modifier.height(12.dp))
                            r.meanings.forEach { m ->
                                Text(m.partOfSpeech, color = Gold, fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold)
                                m.definitions.forEachIndexed { i, def ->
                                    Text("${i + 1}. $def", color = Cream, fontSize = 14.sp,
                                        lineHeight = 20.sp,
                                        modifier = Modifier.padding(start = 8.dp, top = 4.dp))
                                }
                                if (m.synonyms.isNotEmpty())
                                    Text("Sinónimos: ${m.synonyms.joinToString(", ")}",
                                        color = Muted, fontSize = 12.sp,
                                        modifier = Modifier.padding(start = 8.dp, top = 4.dp))
                                Spacer(Modifier.height(10.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
