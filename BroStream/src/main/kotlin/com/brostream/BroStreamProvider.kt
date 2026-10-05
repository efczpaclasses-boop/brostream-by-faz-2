package com.brostream

import com.brostream.sources.GayPornTubeSource
import com.brostream.sources.GayVidsSource
import com.brostream.sources.ManPornSource
import com.brostream.sources.VideoSource
import com.brostream.sources.Web
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.utils.*

class BroStreamProvider : MainAPI() {
    // Identity only. Every source builds its URLs from its own fixed base URL.
    override var mainUrl = ManPornSource.BASE_URL
    override var name = "BroStream by Faz 2"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val mapper = jacksonObjectMapper()
    private val sources: List<VideoSource> = listOf(ManPornSource(), GayVidsSource(), GayPornTubeSource())
    private val byId = sources.associateBy { it.id }
    private val blocklistCache = ExpiringCache<Blocklist>(max = 1, ttlMillis = 6 * 3_600_000L)
    private val pipeline = Pipeline(
        sources, Deduplicator { id -> byId[id]?.let { SourceProfile(it.reliability, it.speed) } },
        verdicts = PersistentVerdictStore(), blocklist = ::blocklist,
    )

    /** Corrections from blocklist.json on the builds branch; an unreachable file means no extra blocks. */
    private suspend fun blocklist(): Blocklist {
        blocklistCache.get("list")?.let { return it }
        val fetched = attempt { app.get(BLOCKLIST_URL, timeout = 15) }?.takeIf { it.code in 200..299 }
            ?.let { Blocklist.parse(it.text, mapper) } ?: Blocklist.EMPTY
        blocklistCache.put("list", fetched)
        return fetched
    }

    /** Inspection outcomes survive restarts, so unclear videos are not downloaded again every session. */
    private inner class PersistentVerdictStore : VerdictStore {
        private val memory = MemoryVerdictStore()
        private var loaded = false
        private var pending = 0

        private fun load() {
            if (loaded) return
            loaded = true
            attempt { getKey<String>(VERDICTS_KEY) }?.let { text ->
                attempt { mapper.readTree(text) }?.fields()?.forEach { (id, node) ->
                    val verdict = attempt { Verdict.valueOf(node.path("v").asText()) } ?: return@forEach
                    memory.put(id, StoredVerdict(verdict, node.path("t").asLong()))
                }
            }
        }

        @Synchronized override fun get(id: String): StoredVerdict? { load(); return memory.get(id) }

        @Synchronized override fun put(id: String, verdict: StoredVerdict) {
            load()
            memory.put(id, verdict)
            if (++pending >= 20) { pending = 0; save() }
        }

        private fun save() {
            // Only the newest entries are kept, so the stored value stays small.
            val snapshot = memory.recent(1500).associate { (id, v) -> id to mapOf("v" to v.verdict.name, "t" to v.at) }
            attempt { setKey(VERDICTS_KEY, mapper.writeValueAsString(snapshot)) }
        }
    }

    companion object {
        private const val BLOCKLIST_URL =
            "https://raw.githubusercontent.com/efczpaclasses-boop/brostream-by-faz-2/builds/blocklist.json"
        private const val VERDICTS_KEY = "brostream_verdicts_v1"
    }

    override val mainPage = mainPageOf(*Rows.all.map { it.key to it.title }.toTypedArray())

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        require(page >= 1) { "Page numbers start at 1" }
        val row = Rows.find(request.data)
        val items = if (row == null) emptyList() else pipeline.loadRow(row, page)
        // A row with too few verified videos is hidden rather than shown under an empty heading.
        if (items.isEmpty()) return newHomePageResponse(emptyList<HomePageList>(), false)
        return newHomePageResponse(HomePageList(request.name, items.map { it.response() }, true), hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return pipeline.search(query).map { it.response() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val item = parse(url) ?: return null
        val source = byId[item.source]
        val details = source?.let { attempt { it.details(item) } }
        val tags = details?.tags?.takeIf { it.isNotEmpty() } ?: listOf("Gay Men", source?.label ?: item.source)
        return newMovieLoadResponse(details?.title ?: item.title, url, TvType.NSFW, url) {
            posterUrl = details?.poster?.ifBlank { null } ?: item.poster
            plot = details?.plot
            this.tags = tags
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val item = parse(data) ?: return false
        val source = byId[item.source] ?: return false
        val deliveries = LinkDeliveries()
        attempt {
            pipeline.playable(source, item) { stream, check ->
                val seekNote = if (stream.isHls || check.rangeable) "" else " (no seeking)"
                val label = "${source.label} ${StreamExtractor.label(stream.quality)}$seekNote"
                val link = newExtractorLink(
                    source.label, label, stream.url, if (stream.isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    referer = item.url
                    quality = if (stream.quality > 0) stream.quality else Qualities.Unknown.value
                    headers = mapOf("User-Agent" to Web.USER_AGENT, "Referer" to item.url)
                }
                deliveries.emit(stream.url) { callback(link) }
            }
        }
        return deliveries.hasResults()
    }

    private fun ItemData.response(): SearchResponse {
        val label = byId[source]?.shortLabel ?: source
        return newMovieSearchResponse("[$label] $title", mapper.writeValueAsString(lean()), TvType.NSFW) {
            posterUrl = poster
        }
    }

    private fun parse(value: String): ItemData? = attempt { mapper.readValue(value, ItemData::class.java) }
        ?.takeIf { isHttpUrl(it.url) && it.title.isNotBlank() }
}
