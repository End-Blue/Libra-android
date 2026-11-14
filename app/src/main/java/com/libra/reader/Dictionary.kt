package com.libra.reader

import androidx.compose.animation.*
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
import java.net.URL

// ── Modelo ────────────────────────────────────────────────────────────────────

data class DictMeaning(
    val partOfSpeech: String,
    val definitions: List<String>,
    val synonyms: List<String>
)

data class DictResult(
    val word: String,
    val phonetic: String,
    val meanings: List<DictMeaning>
)

// ── Consulta a la API gratuita dictionaryapi.dev ──────────────────────────────

suspend fun fetchDefinition(word: String): Result<DictResult> = withContext(Dispatchers.IO) {
    runCatching {
        val clean = word.trim().lowercase()
        val json = URL("https://api.dictionaryapi.dev/api/v2/entries/en/$clean")
            .readText(Charsets.UTF_8)
        val arr = JSONArray(json)
        val entry = arr.getJSONObject(0)
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
            val synList = if (syns != null) (0 until minOf(syns.length(), 4)).map { j ->
                syns.getString(j)
            } else emptyList()
            DictMeaning(pos, defList, synList)
        }
        DictResult(entry.optString("word", clean), phonetic, meanings)
    }
}

// ── UI: diálogo del diccionario ───────────────────────────────────────────────

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
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "📖 Diccionario",
                        color = Gold,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismiss) {
                        Text("✕", color = Muted)
                    }
                }
                Spacer(Modifier.height(4.dp))

                when {
                    state == null -> {
                        Box(
                            Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = Gold, modifier = Modifier.size(32.dp))
                        }
                    }
                    state!!.isFailure -> {
                        Text(
                            "No se encontró definición para «$word».\n\nPrueba con una palabra en inglés o revisa la ortografía.",
                            color = Cream,
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )
                    }
                    else -> {
                        val result = state!!.getOrNull()!!
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            Text(result.word, color = Cream, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            if (result.phonetic.isNotBlank()) {
                                Text(result.phonetic, color = Gold, fontSize = 14.sp, fontStyle = FontStyle.Italic)
                            }
                            Spacer(Modifier.height(12.dp))
                            result.meanings.forEach { meaning ->
                                Text(
                                    meaning.partOfSpeech,
                                    color = Gold,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                meaning.definitions.forEachIndexed { i, def ->
                                    Text(
                                        "${i + 1}. $def",
                                        color = Cream,
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp,
                                        modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                                    )
                                }
                                if (meaning.synonyms.isNotEmpty()) {
                                    Text(
                                        "Sinónimos: ${meaning.synonyms.joinToString(", ")}",
                                        color = Muted,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
