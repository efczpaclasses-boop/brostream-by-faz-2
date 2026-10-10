package com.brostreamah.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brostreamah.CategoryRow
import com.brostreamah.ItemData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal enum class RowStatus { IDLE, LOADING, LOADED, HIDDEN, FAILED }

/** What one home row shows. Reading these from composables re-draws them when a load finishes. */
internal class RowUi(val row: CategoryRow) {
    var items by mutableStateOf<List<ItemData>>(emptyList())
    var status by mutableStateOf(RowStatus.IDLE)
    var page = 0
    var more = true
    var loadingMore = false
}

/** Holds home rows for the life of the app, so coming back from a video does not reload everything. */
internal class HomeModel(private val engine: Engine) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // A few rows at a time: the sites should not be hit with thirty detail-page storms at once.
    private val gate = Semaphore(3)
    val rows: List<RowUi> = engine.rows.map { RowUi(it) }

    fun load(ui: RowUi) {
        if (ui.status != RowStatus.IDLE) return
        ui.status = RowStatus.LOADING
        scope.launch {
            val items = try {
                gate.withPermit { engine.loadRow(ui.row, 1) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ui.status = RowStatus.FAILED
                return@launch
            }
            ui.page = 1
            ui.items = items
            ui.status = if (items.isEmpty()) RowStatus.HIDDEN else RowStatus.LOADED
        }
    }

    fun loadMore(ui: RowUi) {
        if (ui.status != RowStatus.LOADED || ui.loadingMore || !ui.more) return
        ui.loadingMore = true
        scope.launch {
            try {
                val next = gate.withPermit { engine.loadRow(ui.row, ui.page + 1) }
                if (next.isEmpty()) ui.more = false else { ui.page += 1; ui.items = ui.items + next }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ui.more = false
            } finally {
                ui.loadingMore = false
            }
        }
    }

    fun hide(item: ItemData) {
        engine.hide(item)
        rows.forEach { ui -> if (ui.items.any { it.url == item.url }) ui.items = ui.items.filterNot { it.url == item.url } }
    }

    fun retryFailed() = rows.filter { it.status == RowStatus.FAILED }.forEach { it.status = RowStatus.IDLE }
}
