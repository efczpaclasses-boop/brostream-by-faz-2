package com.brostreamah.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.brostreamah.ItemData
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun AgeGateScreen(onAccept: () -> Unit, onExit: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.fillMaxSize().padding(64.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("BroStream AH", fontSize = 40.sp, color = Accent)
        Spacer(Modifier.height(16.dp))
        Text("This app contains adult content and is for people aged 18 or over.", fontSize = 20.sp)
        Text("It does not host videos. It lists videos from other websites; use it only where that is lawful.", fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Button(onClick = onAccept, modifier = Modifier.focusRequester(focus)) { Text("I am 18 or older") }
            Button(onClick = onExit) { Text("Exit") }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun HomeScreen(model: HomeModel, engine: Engine, onPlay: (ItemData) -> Unit, onSearch: () -> Unit, onSettings: () -> Unit) {
    var toHide by remember { mutableStateOf<ItemData?>(null) }
    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("BroStream AH", fontSize = 28.sp, color = Accent, modifier = Modifier.weight(1f))
            Button(onClick = onSearch) { Text("Search") }
            Spacer(Modifier.padding(horizontal = 8.dp))
            Button(onClick = onSettings) { Text("Settings") }
        }
        LazyColumn(contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(model.rows, key = { it.row.key }) { ui ->
                RowSection(ui, model, engine, onPlay) { toHide = it }
            }
        }
    }
    toHide?.let { item -> HideDialog(item, onConfirm = { model.hide(item); toHide = null }, onCancel = { toHide = null }) }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RowSection(ui: RowUi, model: HomeModel, engine: Engine, onPlay: (ItemData) -> Unit, onLongPress: (ItemData) -> Unit) {
    LaunchedEffect(ui) { model.load(ui) }
    when (ui.status) {
        RowStatus.HIDDEN -> Unit
        RowStatus.IDLE, RowStatus.LOADING -> Column(Modifier.padding(horizontal = 48.dp)) {
            Text(ui.row.title, fontSize = 20.sp)
            Text("Loading…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RowStatus.FAILED -> Column(Modifier.padding(horizontal = 48.dp)) {
            Text(ui.row.title, fontSize = 20.sp)
            Text("Could not load. Check the connection, then use Settings > Reload.", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RowStatus.LOADED -> Column {
            Text(ui.row.title, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 48.dp, vertical = 6.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                itemsIndexed(ui.items, key = { _, it -> it.url }) { index, item ->
                    if (index >= ui.items.size - 3) LaunchedEffect(ui.items.size) { model.loadMore(ui) }
                    VideoCard(item, engine, onPlay = { onPlay(item) }, onLongPress = { onLongPress(item) })
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HideDialog(item: ItemData, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onCancel) {
        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(24.dp)) {
            Text("Hide this video?", fontSize = 22.sp)
            Text(item.title, fontSize = 14.sp, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp))
            Text("It will not appear again on this device.", fontSize = 14.sp)
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onConfirm, modifier = Modifier.focusRequester(focus)) { Text("Hide it") }
                Button(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SearchScreen(engine: Engine, onPlay: (ItemData) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ItemData>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var toHide by remember { mutableStateOf<ItemData?>(null) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun run() {
        if (query.isBlank() || busy) return
        busy = true
        scope.launch {
            results = try { engine.search(query.trim()) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { emptyList() }
            busy = false
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)) {
        Text("Search", fontSize = 28.sp, color = Accent)
        Spacer(Modifier.height(12.dp))
        BasicTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 22.sp),
            cursorBrush = SolidColor(Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { run() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus)
                .border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(8.dp)).padding(14.dp),
        )
        Spacer(Modifier.height(8.dp))
        when {
            busy -> Text("Searching all sources…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            results == null -> Text("Type a word and press the search key on the keyboard.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            results!!.isEmpty() -> Text("Nothing found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(240.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                gridItems(results!!, key = { it.url }) { item ->
                    VideoCard(item, engine, onPlay = { onPlay(item) }, onLongPress = { toHide = item })
                }
            }
        }
    }
    toHide?.let { item ->
        HideDialog(item, onConfirm = { engine.hide(item); results = results?.filterNot { it.url == item.url }; toHide = null }, onCancel = { toHide = null })
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SettingsScreen(engine: Engine, model: HomeModel, onBack: () -> Unit) {
    var hiddenCount by remember { mutableStateOf(engine.hidden.ids().size) }
    var report by remember { mutableStateOf<List<String>?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", fontSize = 28.sp, color = Accent)
        Text("Videos you hid: $hiddenCount", fontSize = 18.sp)
        Button(onClick = { engine.hidden.clear(); hiddenCount = 0 }) { Text("Show hidden videos again") }
        Button(onClick = { model.retryFailed(); onBack() }) { Text("Reload rows that failed") }
        Button(onClick = {
            if (!checking) { checking = true; scope.launch { report = Diagnostics.run(); checking = false } }
        }) { Text(if (checking) "Checking…" else "Run diagnostics") }
        report?.forEach { Text(it, fontSize = 14.sp) }
        Text(
            "Videos come from ManPorn, GayVids and GayPornTube. Each one needs clear male-only evidence in its title, tags or " +
                "description; unclear ones are held back. Anything that suggests a minor is always rejected.",
            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Version 1.0", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onBack) { Text("Back") }
    }
}
