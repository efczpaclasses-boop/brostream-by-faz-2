package com.brostreamah.tv

import android.content.Context
import com.brostreamah.Blocklist
import com.brostreamah.BlocklistLoader
import com.brostreamah.CategoryRow
import com.brostreamah.Deduplicator
import com.brostreamah.ItemData
import com.brostreamah.PersistentVerdictStore
import com.brostreamah.Pipeline
import com.brostreamah.Rows
import com.brostreamah.SourceProfile
import com.brostreamah.StreamCandidate
import com.brostreamah.StreamCheck
import com.brostreamah.StreamValidator
import com.brostreamah.canonicalId
import com.brostreamah.sources.GayPornTubeSource
import com.brostreamah.sources.GayVidsSource
import com.brostreamah.sources.ManPornSource
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.Collections

/** Videos the viewer hid by long-pressing. Kept on the device; applied on top of the shared blocklist. */
internal class HiddenVideos(context: Context) {
    private val prefs = context.getSharedPreferences("brostream_tv", Context.MODE_PRIVATE)

    @Synchronized fun ids(): Set<String> = prefs.getString("hidden", "").orEmpty().split('\n').filter { it.isNotBlank() }.toSet()

    @Synchronized fun add(id: String) { prefs.edit().putString("hidden", (ids() + id).joinToString("\n")).apply() }

    @Synchronized fun clear() { prefs.edit().remove("hidden").apply() }
}

/** Which categories the viewer chose to see on Home (New Videos and Hot Videos are always there). */
internal class HomeSelection(context: Context) {
    private val prefs = context.getSharedPreferences("brostream_tv", Context.MODE_PRIVATE)

    fun keys(): List<String> =
        prefs.getString("home_rows", null)?.split('\n')?.filter { it.isNotBlank() } ?: DEFAULTS

    fun save(keys: List<String>) { prefs.edit().putString("home_rows", keys.joinToString("\n")).apply() }

    companion object {
        val DEFAULTS = listOf(
            "ALL|amateur", "ALL|twink", "ALL|bear", "ALL|daddy", "ALL|hunk", "MP|/categories/muscle/",
            "MP|/categories/latino/", "MP|/categories/asian/", "ALL|first-time", "ALL|threesome",
        )
    }
}

/**
 * Remembers what was already shown or watched, so Home puts videos the viewer has not seen first.
 * "Shown" fades after three days so a quiet source never leaves a row empty; "watched" lasts three weeks.
 */
internal class SeenStore(context: Context, private val now: () -> Long = System::currentTimeMillis) {
    private val prefs = context.getSharedPreferences("brostream_tv", Context.MODE_PRIVATE)
    private class Entry(val at: Long, val watched: Boolean)
    private val map = LinkedHashMap<String, Entry>()

    init {
        prefs.getString("seen", "").orEmpty().lineSequence().forEach { line ->
            val p = line.split('\t')
            if (p.size == 3) p[1].toLongOrNull()?.let { map[p[0]] = Entry(it, p[2] == "w") }
        }
    }

    /** 0 = new to this viewer, 1 = shown before, 2 = watched. */
    @Synchronized fun status(id: String): Int {
        val e = map[id] ?: return 0
        val age = now() - e.at
        return when {
            e.watched -> if (age < 21 * DAY) 2 else 0
            age < 3 * DAY -> 1
            else -> 0
        }
    }

    @Synchronized fun markShown(ids: List<String>) {
        ids.forEach { id -> if (map[id]?.watched != true) { map.remove(id); map[id] = Entry(now(), false) } }
        save()
    }

    @Synchronized fun markWatched(id: String) { map.remove(id); map[id] = Entry(now(), true); save() }

    private fun save() {
        while (map.size > 4000) map.remove(map.keys.first())
        prefs.edit().putString("seen", map.entries.joinToString("\n") { "${it.key}\t${it.value.at}\t${if (it.value.watched) "w" else "s"}" }).apply()
    }

    private companion object { const val DAY = 86_400_000L }
}

/** Ready-to-play stream with the page it came from (needed as the Referer). */
internal class PlayableStream(val candidate: StreamCandidate, val check: StreamCheck)

internal class Engine(context: Context) {
    private val mapper = jacksonObjectMapper()
    private val sources = listOf(ManPornSource(), GayVidsSource(), GayPornTubeSource())
    val hidden = HiddenVideos(context)
    val selection = HomeSelection(context)
    val seen = SeenStore(context)
    private val remoteBlocklist = BlocklistLoader(mapper)
    private val pipeline = Pipeline(
        sources,
        Deduplicator { id -> sources.firstOrNull { it.id == id }?.let { SourceProfile(it.reliability, it.speed) } },
        verdicts = PersistentVerdictStore(mapper),
        blocklist = { remoteBlocklist.get().let { Blocklist(it.ids + hidden.ids(), it.rejectTerms) } },
    )

    val rows: List<CategoryRow> get() = Rows.all

    /**
     * [owner] decides which row a video belongs to on Home (so a video is not repeated across rows); browsing a single
     * category passes `{ null }` so every video that fits is shown.
     */
    suspend fun loadRow(
        row: CategoryRow, page: Int, owner: (ItemData) -> CategoryRow?, stateKey: String, minItems: Int,
    ): List<ItemData> = pipeline.loadRow(row, page, owner = owner, stateKey = stateKey, minItems = minItems)

    suspend fun search(query: String): List<ItemData> = pipeline.search(query)

    fun hide(item: ItemData) = hidden.add(canonicalId(item))

    fun sourceLabel(item: ItemData): String = sources.firstOrNull { it.id == item.source }?.shortLabel ?: ""

    fun siteOf(item: ItemData): String = sources.firstOrNull { it.id == item.source }?.baseUrl ?: item.url

    /** Every stream that answered a test request, seekable and higher quality first. */
    suspend fun streams(item: ItemData): List<PlayableStream> {
        val source = sources.firstOrNull { it.id == item.source } ?: return emptyList()
        val found = Collections.synchronizedList(mutableListOf<Pair<StreamCandidate, StreamCheck>>())
        pipeline.playable(source, item) { candidate, check -> found.add(candidate to check) }
        return StreamValidator.order(found.toList()).map { PlayableStream(it.first, it.second) }
    }
}
