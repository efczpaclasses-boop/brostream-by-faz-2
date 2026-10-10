package com.brostreamah

import com.brostreamah.sources.GayPornTubeSource
import com.brostreamah.sources.GayVidsSource
import com.brostreamah.sources.ManPornSource
import com.brostreamah.sources.VideoSource
import com.brostreamah.sources.Web
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class BroStreamProvider : MainAPI() {
    // Identity only. Every source builds its URLs from its own fixed base URL.
    override var mainUrl = ManPornSource.BASE_URL
    override var name = "BroStream by Faz_AH_"
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
    private val pipeline = Pipeline(
        sources, Deduplicator { id -> byId[id]?.let { SourceProfile(it.reliability, it.speed) } },
        verdicts = PersistentVerdictStore(mapper), blocklist = BlocklistLoader(mapper)::get,
    )

    init {
        // CloudStream's own client does the network work for this host.
        Web.fetch = { url, headers, maxBytes ->
            attempt { app.get(url, headers = headers, timeout = 25) }?.let { response ->
                Web.Raw(
                    response.code,
                    response.headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() },
                    Web.readUpTo(response.body.byteStream(), maxBytes),
                )
            }
        }
    }

    override val mainPage = mainPageOf(*Rows.all.map { it.key to it.title }.toTypedArray())

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        require(page >= 1) { "Page numbers start at 1" }
        val row = Rows.find(request.data)
        val items = try {
            if (row == null) emptyList() else pipeline.loadRow(row, page)
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (problem: Throwable) {
            // Show the failure instead of a blank screen, so it can be reported and fixed.
            val note = "⚠ ${request.name}: ${problem.javaClass.simpleName} ${problem.message.orEmpty()}".take(160)
            return newHomePageResponse(HomePageList(request.name, listOf(
                newMovieSearchResponse(note, "https://error.invalid/", TvType.NSFW),
            ), false), hasNext = false)
        }
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
