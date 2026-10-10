package com.brostreamah

import com.brostreamah.sources.VideoSource
import com.brostreamah.sources.Web
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Runs [transform] on every element at once and keeps the original order. */
internal suspend fun <A, B> List<A>.pmap(transform: suspend (A) -> B): List<B> =
    coroutineScope { map { async { transform(it) } }.awaitAll() }

internal class ExpiringCache<V : Any>(
    private val max: Int = 400,
    private val ttlMillis: Long = 30 * 60_000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val map = object : LinkedHashMap<String, Pair<Long, V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, V>>?) = size > max
    }

    @Synchronized
    fun get(key: String): V? {
        val entry = map[key] ?: return null
        if (entry.first <= now()) { map.remove(key); return null }
        return entry.second
    }

    @Synchronized
    fun put(key: String, value: V) { map[key] = now() + ttlMillis to value }
}

/**
 * Everything between a site's raw listing and what the screen shows: classify, read detail pages for
 * unclear items, quarantine what stays unclear, apply the category's rules, rank, deduplicate, fall back.
 */
internal class Pipeline(
    private val sources: List<VideoSource>,
    private val dedup: Deduplicator,
    val quarantine: Quarantine = Quarantine(),
    private val now: () -> Long = System::currentTimeMillis,
    private val detailLimit: Int = 20,
    private val verdicts: VerdictStore = MemoryVerdictStore(),
    private val blocklist: suspend () -> Blocklist = { Blocklist.EMPTY },
    val stats: PolicyStats = PolicyStats(),
    private val ownerOf: (ItemData) -> CategoryRow? = Rows::ownerOf,
    private val trustSiteDeclaration: Boolean = false,
) {
    private fun classify(item: ItemData, block: Blocklist): Assessment = ContentPolicy.classify(
        item, block, trustSiteDeclaration && sources.firstOrNull { it.id == item.source }?.declaresMaleOnly == true,
    )

    private val negativeTtl = 6 * 3_600_000L
    private val byPrefix = sources.associateBy { it.prefix }
    private val profile = { id: String -> sources.firstOrNull { it.id == id }?.let { SourceProfile(it.reliability, it.speed) } }
    private val details = ExpiringCache<VideoDetails>()

    private suspend fun enrich(item: ItemData): ItemData {
        val key = canonicalId(item)
        val known = details.get(key)
        val found = known ?: sources.firstOrNull { it.id == item.source }?.let { attempt { it.details(item) } }
            ?.also { details.put(key, it) }
        return if (found == null) item else item.withDetails(found)
    }

    private fun needsMore(item: ItemData, row: CategoryRow?): Boolean {
        if (row == null) return false
        val topic = row.topic
        // A title that does not settle the category sends us to the video's own tags.
        if (topic != null && !item.detailed && !topic.accepts(item, true)) return true
        return row.hasWindow && (item.uploadedAt == 0L || row.sort == SortRule.MOST_VIEWED && item.views == 0L)
    }

    /** Keeps only items with positive male-only evidence; unclear ones are inspected, then quarantined. */
    suspend fun vet(items: List<ItemData>, row: CategoryRow?): List<ItemData> {
        val block = blocklist()
        val time = now()
        // Items inspected recently and found rejected or still unclear are skipped without another request.
        val first = items.filter { item ->
            val known = verdicts.get(canonicalId(item))
            if (known != null && known.verdict != Verdict.ACCEPT && time - known.at < negativeTtl) return@filter false
            val assessment = classify(item, block)
            if (assessment.verdict == Verdict.REJECT) stats.record(assessment)
            assessment.verdict != Verdict.REJECT
        }
        val wanted = first.indices.filter { i ->
            !first[i].detailed && (classify(first[i], block).verdict == Verdict.AMBIGUOUS || needsMore(first[i], row))
        }.take(detailLimit)
        val enriched = first.toMutableList()
        wanted.chunked(10).forEach { chunk ->
            chunk.pmap { i -> i to enrich(first[i]) }.forEach { (i, item) -> enriched[i] = item }
        }
        return enriched.filter { item ->
            val assessment = classify(item, block)
            stats.record(assessment)
            // Remember an outcome only once the detail page was read, or the item is clearly fine.
            if (item.detailed || assessment.verdict == Verdict.ACCEPT) {
                verdicts.put(canonicalId(item), StoredVerdict(assessment.verdict, time))
            }
            when (assessment.verdict) {
                Verdict.ACCEPT -> true.also { quarantine.release(item) }
                Verdict.REJECT -> false
                Verdict.AMBIGUOUS -> false.also { quarantine.add(item, assessment.reason) }
            }
        }
    }

    /** Applies the category's relevance rule, time window and sort rule. */
    fun select(row: CategoryRow, items: List<ItemData>, owner: (ItemData) -> CategoryRow? = ownerOf): List<ItemData> {
        val owned = items.filter { item ->
            (row.topic?.accepts(item, viaSourceCategory = true) ?: true) && owner(item).let { it == null || it === row }
        }
        val windowed = if (!row.hasWindow) owned else owned.filter {
            it.uploadedAt > 0 && now() - it.uploadedAt in -600_000L..row.windowMillis &&
                (row.sort != SortRule.MOST_VIEWED || it.views > 0)
        }
        return when (row.sort) {
            SortRule.SOURCE_ORDER -> windowed
            SortRule.NEWEST -> windowed.sortedByDescending { it.uploadedAt }
            SortRule.MOST_VIEWED -> windowed.sortedWith(compareByDescending<ItemData> { it.views }.thenByDescending { it.rating })
            SortRule.RANKED -> windowed.sortedByDescending { Rows.rank(it, profile) }
        }
    }

    private fun interleave(lists: List<List<ItemData>>): List<ItemData> {
        val result = ArrayList<ItemData>()
        var index = 0
        while (lists.any { index < it.size }) {
            lists.forEach { if (index < it.size) result.add(it[index]) }
            index++
        }
        return result
    }

    /** Videos for one row page, or an empty list when page 1 cannot reach [CategoryRow.minItems]. */
    suspend fun loadRow(
        row: CategoryRow,
        page: Int,
        limit: Int = 60,
        /** Which row owns an item. Null means no exclusivity: the item may appear in every row it fits (category browsing). */
        owner: (ItemData) -> CategoryRow? = ownerOf,
        /** Key under which already-shown videos are remembered; give browsing its own so it never disturbs Home. */
        stateKey: String = row.key,
        minItems: Int = row.minItems,
    ): List<ItemData> {
        val raw = interleave(row.feeds.pmap { feed ->
            byPrefix[feed.prefix]?.let { source -> attempt { source.catalogue(page, feed.path) } }.orEmpty()
        })
        val vetted = vet(raw, row)
        var shown = dedup.admit(stateKey, page, select(row, vetted, owner), limit)
        if (page == 1 && shown.size < minItems && row.fallbackQuery.isNotBlank()) {
            // Prefer providers the row did not already read; if it read them all, search them all again.
            val tried = row.feeds.map { it.prefix }.toSet()
            val alternatives = sources.filter { it.prefix !in tried }.ifEmpty { sources }
            val others = alternatives.pmap { source -> attempt { source.search(page, row.fallbackQuery) }.orEmpty() }.flatten()
            shown = dedup.admit(stateKey, page, select(row, vetted + vet(others, row), owner), limit)
        }
        return if (page == 1 && shown.size < minItems) emptyList() else shown
    }

    suspend fun search(query: String, limit: Int = 80): List<ItemData> {
        val found = sources.pmap { source -> attempt { source.search(1, query) }.orEmpty() }.flatten()
        return dedup.collapse(vet(found, null)).take(limit)
    }

    /**
     * Tests each candidate with a small request in parallel and hands every playable one to [onPlayable] as soon
     * as it answers. Returns how many were delivered. If none played, the watch page is loaded again once, since
     * expiring tokens in the first set of URLs may already have lapsed.
     */
    suspend fun playable(source: VideoSource, item: ItemData, onPlayable: suspend (StreamCandidate, StreamCheck) -> Unit): Int {
        repeat(2) {
            val candidates = attempt { source.playback(item) }.orEmpty()
            val delivered = java.util.concurrent.atomic.AtomicInteger()
            candidates.chunked(6).forEach { chunk ->
                chunk.pmap { candidate ->
                    val check = Web.probe(candidate.url, item.url, candidate.isHls)
                    if (check.playable) { delivered.incrementAndGet(); onPlayable(candidate, check) }
                }
            }
            if (delivered.get() > 0) return delivered.get()
        }
        return 0
    }
}
