package com.brostreamah.tv

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn as AndroidOptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.brostreamah.ItemData
import com.brostreamah.StreamExtractor
import com.brostreamah.sources.Web

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun PlayerScreen(engine: Engine, item: ItemData, onClose: () -> Unit) {
    var streams by remember(item) { mutableStateOf<List<PlayableStream>?>(null) }
    var failed by remember(item) { mutableStateOf(false) }
    LaunchedEffect(item) {
        streams = try {
            engine.streams(item)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyList()
        }
    }
    BackHandler(onBack = onClose)
    val list = streams
    when {
        list == null -> Message("Finding a working stream…", item.title)
        list.isEmpty() || failed -> Column(
            Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("This video could not be played.", fontSize = 24.sp)
            Text("Its links may have expired or the site may be blocking this connection. Try another video.", fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
            Button(onClick = onClose) { Text("Back") }
        }
        else -> VideoPlayer(list, item.url, onFailed = { failed = true })
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Message(title: String, detail: String) {
    Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 24.sp)
        Text(detail, fontSize = 14.sp, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}

@AndroidOptIn(UnstableApi::class)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun VideoPlayer(streams: List<PlayableStream>, referer: String, onFailed: () -> Unit) {
    val context = LocalContext.current
    var index by remember { mutableIntStateOf(0) }
    var controlsVisible by remember { mutableStateOf(true) }
    val player = remember { ExoPlayer.Builder(context).build() }
    val dataSource = remember(referer) {
        DefaultHttpDataSource.Factory().setUserAgent(Web.USER_AGENT).setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Referer" to referer))
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            // A link that stops working falls back to the next quality instead of ending playback.
            override fun onPlayerError(error: PlaybackException) {
                if (index + 1 < streams.size) index += 1 else onFailed()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }

    LaunchedEffect(index) {
        val stream = streams[index].candidate
        val resume = if (player.playbackState == Player.STATE_IDLE) 0L else player.currentPosition
        val media = MediaItem.fromUri(stream.url)
        val source = if (stream.isHls) HlsMediaSource.Factory(dataSource).createMediaSource(media)
        else ProgressiveMediaSource.Factory(dataSource).createMediaSource(media)
        player.setMediaSource(source)
        player.prepare()
        if (resume > 0) player.seekTo(resume)
        player.playWhenReady = true
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    controllerShowTimeoutMs = 4000
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { controlsVisible = it == View.VISIBLE })
                    isFocusable = true
                    requestFocus()
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (streams.size > 1 && controlsVisible) {
            Row(Modifier.align(Alignment.TopEnd).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                streams.forEachIndexed { i, s ->
                    val note = if (s.candidate.isHls || s.check.rangeable) "" else " (no seeking)"
                    Button(onClick = { index = i }) {
                        Text(StreamExtractor.label(s.candidate.quality) + note + if (i == index) "  ✓" else "", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
