package com.brostreamah.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.brostreamah.ItemData
import com.brostreamah.sources.Web

internal fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** One video tile: thumbnail, title and a duration badge. OK plays it; holding OK offers to hide it. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun VideoCard(item: ItemData, engine: Engine, onPlay: () -> Unit, onLongPress: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier.width(240.dp)) {
        Card(onClick = onPlay, onLongClick = onLongPress, modifier = Modifier.fillMaxWidth().height(135.dp)) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(item.poster)
                        .addHeader("Referer", engine.siteOf(item)).addHeader("User-Agent", Web.USER_AGENT)
                        .crossfade(true).build(),
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(36.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))),
                )
                val badge = formatDuration(item.durationSec)
                if (badge.isNotEmpty()) {
                    Text(
                        badge, fontSize = 12.sp, color = Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                            .clip(RoundedCornerShape(4.dp)).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Text(
            item.title, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
        Text(
            engine.sourceLabel(item), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}
