package com.libra.reader

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/** Conecta con PlaybackService y entrega el MediaController cuando está listo. */
@Composable
fun rememberMediaController(): State<MediaController?> {
    val context = LocalContext.current
    val state = remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                if (!future.isCancelled) {
                    state.value = runCatching { future.get() }.getOrNull()
                }
            },
            ContextCompat.getMainExecutor(context)
        )
        onDispose {
            MediaController.releaseFuture(future)
            state.value = null
        }
    }
    return state
}

private fun persist(player: Player, dao: LibraryDao, scope: CoroutineScope, finished: Boolean) {
    val id = player.currentMediaItem?.mediaId ?: return
    val duration = player.duration.coerceAtLeast(0L)
    val position = player.currentPosition.coerceAtLeast(0L)
    val progress = when {
        finished -> 100
        duration > 0 -> (position * 100 / duration).toInt().coerceIn(0, 100)
        else -> 0
    }
    val saved = if (finished) 0L else position
    scope.launch { dao.updatePlayback(id, saved, progress, duration) }
}

/** Guarda la posición del audiolibro aunque la pantalla del reproductor esté cerrada. */
@Composable
fun PlaybackPersistence(controller: MediaController?, dao: LibraryDao) {
    val scope = rememberCoroutineScope()
    DisposableEffect(controller) {
        val c = controller
        if (c == null) return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) persist(c, dao, scope, c.playbackState == Player.STATE_ENDED)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) persist(c, dao, scope, true)
            }
        }
        c.addListener(listener)
        onDispose { c.removeListener(listener) }
    }
    LaunchedEffect(controller) {
        while (true) {
            delay(5_000)
            val c = controller
            if (c != null && c.isPlaying) persist(c, dao, scope, false)
        }
    }
}

private fun buildMediaItem(item: LibraryItem): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(item.title)
        .setArtist(item.author)
        .setArtworkUri(item.coverPath?.let { Uri.fromFile(File(it)) })
        .build()
    return MediaItem.Builder()
        .setMediaId(item.id)
        .setUri(Uri.fromFile(File(item.localPath)))
        .setMediaMetadata(metadata)
        .build()
}

private fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

private val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

@Composable
fun AudioPlayerScreen(item: LibraryItem, controller: MediaController?, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(item.lastPosition) }
    var duration by remember { mutableLongStateOf(item.durationMs) }
    var speed by remember { mutableFloatStateOf(1f) }
    var seeking by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    DisposableEffect(controller, item.id) {
        val c = controller
        if (c == null) return@DisposableEffect onDispose { }
        if (c.currentMediaItem?.mediaId != item.id) {
            c.setMediaItem(buildMediaItem(item), item.lastPosition.coerceAtLeast(0L))
            c.prepare()
        }
        isPlaying = c.isPlaying
        speed = c.playbackParameters.speed
        position = c.currentPosition
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (c.duration > 0) duration = c.duration
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                speed = playbackParameters.speed
            }
        }
        c.addListener(listener)
        onDispose { c.removeListener(listener) }
    }

    LaunchedEffect(controller, item.id) {
        while (true) {
            val c = controller
            if (c != null && c.currentMediaItem?.mediaId == item.id) {
                if (!seeking) position = c.currentPosition
                if (c.duration > 0) duration = c.duration
            }
            delay(500)
        }
    }

    Column(
        Modifier.fillMaxSize().background(Ink).systemBarsPadding().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onClose) { Text("‹ Volver", color = Gold) }
        }
        Spacer(Modifier.height(8.dp))
        BookCover(item, Modifier.size(width = 210.dp, height = 280.dp))
        Spacer(Modifier.height(20.dp))
        Text(
            item.title, color = Cream, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Text(item.author, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(20.dp))
        Slider(
            value = position.toFloat().coerceIn(0f, maxOf(duration, 1L).toFloat()),
            valueRange = 0f..maxOf(duration, 1L).toFloat(),
            onValueChange = {
                seeking = true
                position = it.toLong()
            },
            onValueChangeFinished = {
                controller?.seekTo(position)
                seeking = false
            },
            colors = SliderDefaults.colors(
                thumbColor = Gold, activeTrackColor = Gold, inactiveTrackColor = Panel
            )
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(position), color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("-" + formatTime(duration - position), color = Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { controller?.seekBack() }) {
                Text("↺ 15", color = Gold, fontSize = 16.sp)
            }
            Button(
                onClick = {
                    val c = controller
                    if (c != null) {
                        if (c.isPlaying) {
                            c.pause()
                        } else {
                            if (Build.VERSION.SDK_INT >= 33 &&
                                ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            c.play()
                        }
                    }
                },
                enabled = controller != null,
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)
            ) {
                Text(if (isPlaying) "❚❚  Pausa" else "▶  Reproducir", fontSize = 15.sp)
            }
            TextButton(onClick = { controller?.seekForward() }) {
                Text("30 ↻", color = Gold, fontSize = 16.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = {
            val next = SPEEDS[(SPEEDS.indexOfFirst { it == speed }.let { if (it < 0) 1 else it } + 1) % SPEEDS.size]
            controller?.setPlaybackSpeed(next)
        }) {
            Text("Velocidad ${speed}x", color = Muted, fontSize = 13.sp)
        }
    }
}
