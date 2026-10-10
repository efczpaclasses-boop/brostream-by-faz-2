package com.brostreamah.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brostreamah.CategoryRow
import com.brostreamah.Freshness
import com.brostreamah.ItemData
import com.brostreamah.SortRule
import com.brostreamah.canonicalId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.random.Random

internal enum class RowStatus { IDLE, LOADING, LOADED, HIDDEN, FAILED }

/** What one row (on Home) or one category page shows. Reading these from composables redraws them when a load finishes. */
internal class RowUi(val row: CategoryRow, val browsing: Boolean) {
    var items by mutableStateOf<List<ItemData>>(emptyList())
    var status by mutableStateOf(RowStatus.IDLE)
    var page = 0
    var more = true
    var loadingMore = false
    /** Home rows share videos fairly; a browsed category shows everything that fits. */
    val stateKey get() = if (browsing) "browse|" + row.key else row.key
}

/** Holds rows for the life of the app, so coming back from a video does not reload everything. */
internal class HomeModel(private val engine: Engine) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // A few rows at a time: the sites should not be hit with dozens of detail-page storms at once.
    private val gate = Semaphore(3)
    // New every launch, so shuffled rows come out differently each time the app opens.
    private val sessionSeed = System.nanoTime()

    private val special = engine.rows.filter { it.special }
    private val optional = engine.rows.filter { !it.special }
    private val rowUis = HashMap<String, RowUi>()
    private val browseUis = HashMap<String, RowUi>()

    var homeRows by mutableStateOf<List<RowUi>>(emptyList())
        private set
    var enabledKeys by mutableStateOf(engine.selection.keys())
        private set

    init { rebuildHome() }

    /** Categories the viewer can pick from (everything except New Videos and Hot Videos). */
    val categories: List<CategoryRow> get() = optional

    fun isOnHome(row: CategoryRow) = row.key in enabledKeys

    fun toggleOnHome(row: CategoryRow) {
        enabledKeys = if (row.key in enabledKeys) enabledKeys - row.key else enabledKeys + row.key
        engine.selection.save(enabledKeys)
        rebuildHome()
    }

    private fun rebuildHome() {
        val chosen = optional.filter { it.key in enabledKeys }
        homeRows = (special + chosen).map { row -> rowUis.getOrPut(row.key) { RowUi(row, browsing = false) } }
    }

    fun browse(row: CategoryRow): RowUi = browseUis.getOrPut(row.key) { RowUi(row, browsing = true) }

    private fun ownerFor(ui: RowUi): (ItemData) -> CategoryRow? {
        if (ui.browsing || ui.row.special) return { null }
        val chosen = optional.filter { it.key in enabledKeys }
        return { item -> chosen.firstOrNull { it.topic?.accepts(item, false) == true } }
    }

    /** Unseen videos first, then ones shown before, then ones already watched. Time-sorted rows keep their order. */
    private fun arrange(ui: RowUi, items: List<ItemData>): List<ItemData> {
        val keepOrder = ui.row.sort == SortRule.NEWEST || ui.row.sort == SortRule.MOST_VIEWED
        val random = Random(sessionSeed xor ui.row.key.hashCode().toLong() xor System.currentTimeMillis() / 600_000)
        return Freshness.arrange(items, { engine.seen.status(canonicalId(it)) }, keepOrder, random)
    }

    private suspend fun fetch(ui: RowUi): List<ItemData>? {
        val owner = ownerFor(ui)
        val minItems = if (ui.browsing) 1 else ui.row.minItems
        val all = ArrayList<ItemData>()
        var page = 1
        // Read deeper pages until enough videos are new to this viewer, so Home keeps changing.
        while (page <= 4) {
            val got = gate.withPermit { engine.loadRow(ui.row, page, owner, ui.stateKey, if (page == 1) minItems else 1) }
            if (page == 1 && got.isEmpty()) return null
            if (got.isEmpty()) break
            all += got
            ui.page = page
            val fresh = all.count { engine.seen.status(canonicalId(it)) == 0 }
            if (fresh >= 24 || (ui.row.hasWindow && all.size >= 24)) break
            page++
        }
        return arrange(ui, all).also { engine.seen.markShown(it.take(24).map(::canonicalId)) }
    }

    fun load(ui: RowUi) {
        if (ui.status != RowStatus.IDLE) return
        ui.status = RowStatus.LOADING
        scope.launch {
            val items = try { fetch(ui) } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                ui.status = RowStatus.FAILED
                return@launch
            }
            ui.more = true
            ui.items = items.orEmpty()
            ui.status = if (items.isNullOrEmpty()) RowStatus.HIDDEN else RowStatus.LOADED
        }
    }

    /** Re-reads a row and swaps the result in only when it arrives, so the screen does not flash. */
    fun reload(ui: RowUi) {
        if (ui.status == RowStatus.LOADING || ui.status == RowStatus.IDLE) return
        scope.launch {
            val items = try { fetch(ui) } catch (e: CancellationException) { throw e } catch (e: Throwable) { return@launch }
            if (items.isNullOrEmpty()) return@launch
            ui.more = true
            ui.items = items
            ui.status = RowStatus.LOADED
        }
    }

    fun refreshAll() {
        homeRows.forEach { ui -> if (ui.status == RowStatus.FAILED || ui.status == RowStatus.HIDDEN) ui.status = RowStatus.IDLE else reload(ui) }
    }

    fun loadMore(ui: RowUi) {
        if (ui.status != RowStatus.LOADED || ui.loadingMore || !ui.more) return
        ui.loadingMore = true
        scope.launch {
            try {
                val next = gate.withPermit { engine.loadRow(ui.row, ui.page + 1, ownerFor(ui), ui.stateKey, 1) }
                if (next.isEmpty()) ui.more = false else {
                    ui.page += 1
                    val known = ui.items.map { it.url }.toSet()
                    ui.items = ui.items + next.filter { it.url !in known }
                }
            } catch (e: CancellationException) {
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
        (rowUis.values + browseUis.values).forEach { ui ->
            if (ui.items.any { it.url == item.url }) ui.items = ui.items.filterNot { it.url == item.url }
        }
    }

    fun played(item: ItemData) { engine.seen.markWatched(canonicalId(item)) }

    fun retryFailed() = (rowUis.values + browseUis.values).filter { it.status == RowStatus.FAILED }.forEach { it.status = RowStatus.IDLE }
}
