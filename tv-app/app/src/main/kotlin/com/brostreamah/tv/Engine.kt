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

/** Ready-to-play stream with the page it came from (needed as the Referer). */
internal class PlayableStream(val candidate: StreamCandidate, val check: StreamCheck)

internal class Engine(context: Context) {
    private val mapper = jacksonObjectMapper()
    private val sources = listOf(ManPornSource(), GayVidsSource(), GayPornTubeSource())
    val hidden = HiddenVideos(context)
    private val remoteBlocklist = BlocklistLoader(mapper)
    private val pipeline = Pipeline(
        sources,
        Deduplicator { id -> sources.firstOrNull { it.id == id }?.let { SourceProfile(it.reliability, it.speed) } },
        verdicts = PersistentVerdictStore(mapper),
        blocklist = { remoteBlocklist.get().let { Blocklist(it.ids + hidden.ids(), it.rejectTerms) } },
    )

    val rows: List<CategoryRow> get() = Rows.all

    suspend fun loadRow(row: CategoryRow, page: Int): List<ItemData> = pipeline.loadRow(row, page)

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
