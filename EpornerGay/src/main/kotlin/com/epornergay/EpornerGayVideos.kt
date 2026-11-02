package com.epornergay

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.BitSet

/**
 * Gay videos from the official Eporner API v2 search method:
 * `gay=2` (only gay), `lq=0` (low quality excluded), and an `order` value.
 * The catalogue link is the `embed` iframe URL from the response.
 *
 * Removed ids come from `/api/v2/video/removed/`. The documented txt form is
 * about 150MB, so it is streamed once into a compact filter and not kept as
 * text. Until that filter is ready, a page is shown unfiltered rather than
 * blocking the row on the download.
 */
object EpornerGayVideos {

    private const val SEARCH = "https://www.eporner.com/api/v2/video/search/"
    private const val PAGE_SIZE = 30
    private const val MARKER = "ep_gay"

    val ORDERS = listOf(
        "top-weekly" to "Top this week",
        "most-popular" to "Most popular",
        "latest" to "Latest",
        "top-rated" to "Top rated",
    )

    val CATEGORIES = listOf(
        "amateur", "bear", "muscle", "latino", "daddy", "twink", "group", "solo",
        "bareback", "jock", "asian", "black", "outdoor", "blowjob", "hunk",
        "compilation", "interracial", "webcam",
    )

    private val mapper = jacksonObjectMapper()

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    data class Item(
        val kind: String = MARKER,
        val id: String = "",
        val title: String = "",
        val poster: String? = null,
        val pageUrl: String = "",
        val embedUrl: String = "",
        val views: Long = 0,
        val tags: List<String> = emptyList(),
    )

    fun rows(): List<OfficialRow> {
        val rows = ORDERS.map { (order, label) ->
            OfficialRow("EP|$order|all", "Eporner · $label", core = true, defaultOn = true)
        }.toMutableList()
        CATEGORIES.forEach { query ->
            rows += OfficialRow("EP|top-weekly|$query", "Eporner · $query", core = false, defaultOn = true)
        }
        return rows
    }

    fun handlesRow(data: String): Boolean = data.startsWith("EP|")
    fun handles(data: String): Boolean = data.contains("\"kind\":\"$MARKER\"")

    internal fun parts(row: String): Pair<String, String> {
        val bits = row.split('|')
        val order = bits.getOrNull(1)?.takeIf { it in ORDERS.map { order -> order.first } } ?: "top-weekly"
        val query = bits.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "all"
        return order to query
    }

    internal fun apiUrl(page: Int, order: String, query: String): String =
        SEARCH + "?gay=2&lq=0&format=json&thumbsize=medium&per_page=$PAGE_SIZE" +
            "&page=${page.coerceAtLeast(1)}" +
            "&order=${URLEncoder.encode(order, "UTF-8")}" +
            "&query=${URLEncoder.encode(query, "UTF-8")}"

    internal fun parseSearch(body: String): EpornerSearchResponse =
        runCatching { mapper.readValue<EpornerSearchResponse>(body) }.getOrDefault(EpornerSearchResponse())

    internal fun removedIds(body: String): Set<String> {
        val trimmed = body.trim()
        if (trimmed.startsWith("[")) {
            val node = runCatching { mapper.readTree(trimmed) }.getOrNull() ?: return emptySet()
            return node.mapNotNull { it.get("id")?.asText() }.filter { it.isNotBlank() }.toSet()
        }
        return trimmed.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    internal fun withoutRemoved(videos: List<EpornerVideo>, removed: Set<String>): List<EpornerVideo> =
        videos.filter { it.id.isBlank() || it.id !in removed }

    internal fun withoutRemoved(videos: List<EpornerVideo>, removed: RemovedIdBloom): List<EpornerVideo> =
        videos.filter { it.id.isBlank() || !removed.mightContain(it.id) }

    internal fun embedUrl(video: EpornerVideo): String? {
        val value = video.embed.trim()
        if (value.startsWith("http")) return value.replace("\\/", "/")
        return officialEmbedSrc(value)
    }

    private fun item(video: EpornerVideo): Item? {
        val embed = embedUrl(video) ?: return null
        if (video.id.isBlank() || video.title.isBlank()) return null
        return Item(
            id = video.id,
            title = video.title,
            poster = video.defaultThumb?.src?.takeIf { it.startsWith("http") },
            pageUrl = video.url.takeIf { it.startsWith("http") } ?: embed,
            embedUrl = embed,
            views = video.views,
            tags = video.keywords.split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(12),
        )
    }

    private suspend fun fetch(page: Int, order: String, query: String): Pair<List<Item>, Boolean> {
        val body = runCatching { app.get(apiUrl(page, order, query), timeout = 25).text }.getOrNull()
            ?: return emptyList<Item>() to false
        val parsed = parseSearch(body)
        val kept = EpornerRemovedFeed.apply(parsed.videos)
        val items = kept.mapNotNull(::item)
        return items to (page < parsed.totalPages && parsed.videos.isNotEmpty())
    }

    suspend fun homePage(api: MainAPI, page: Int, rowName: String, rowKey: String): HomePageResponse {
        val (order, query) = parts(rowKey)
        val (items, more) = fetch(page, order, query)
        val cards = items.map { entry ->
            api.newMovieSearchResponse(entry.title, mapper.writeValueAsString(entry), TvType.NSFW) {
                posterUrl = entry.poster
            }
        }
        return newHomePageResponse(HomePageList(rowName, cards, true), hasNext = more)
    }

    suspend fun search(api: MainAPI, query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val (items, _) = fetch(1, "most-popular", query.trim())
        return items.map { entry ->
            api.newMovieSearchResponse(entry.title, mapper.writeValueAsString(entry), TvType.NSFW) { posterUrl = entry.poster }
        }
    }

    suspend fun load(api: MainAPI, data: String): LoadResponse? {
        val item = runCatching { mapper.readValue<Item>(data) }.getOrNull()?.takeIf { it.kind == MARKER && it.embedUrl.startsWith("http") }
            ?: return null
        return api.newMovieLoadResponse(item.title, item.embedUrl, TvType.NSFW, data) {
            posterUrl = item.poster
            plot = "Official embed: ${item.embedUrl}"
            tags = item.tags
        }
    }

    /**
     * The API's playback field is an iframe URL, not a media file. The details
     * page opens that URL. No media file is extracted from the embed page.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun loadLinks(data: String, callback: (ExtractorLink) -> Unit): Boolean = false
}

/** Compact membership test for the large removed-id feed. */
internal class RemovedIdBloom(bits: Int = 1 shl 27, private val hashCount: Int = 4) {
    private val mask = bits - 1
    private val bits = BitSet(bits)

    fun add(id: String) {
        if (id.isBlank()) return
        repeat(hashCount) { seed -> bits.set(index(id, seed)) }
    }

    fun mightContain(id: String): Boolean {
        if (id.isBlank()) return false
        return (0 until hashCount).all { seed -> bits.get(index(id, seed)) }
    }

    private fun index(id: String, seed: Int): Int {
        var hash = seed * 0x9E3779B9.toInt()
        id.forEach { char -> hash = hash * 16777619 xor char.code }
        return hash and mask
    }
}

internal object EpornerRemovedFeed {
    private val lock = Any()
    @Volatile private var bloom: RemovedIdBloom? = null
    @Volatile private var started = false
    @Volatile private var retryAt = 0L

    fun apply(videos: List<EpornerVideo>): List<EpornerVideo> {
        start()
        val ready = bloom ?: return videos
        return EpornerGayVideos.withoutRemoved(videos, ready)
    }

    private fun start() {
        if (bloom != null || started || System.currentTimeMillis() < retryAt) return
        synchronized(lock) {
            if (bloom != null || started || System.currentTimeMillis() < retryAt) return
            started = true
            Thread {
                try {
                    bloom = download()
                } catch (_: Throwable) {
                    started = false
                    retryAt = System.currentTimeMillis() + 15 * 60 * 1000
                }
            }.apply { name = "eporner-removed"; isDaemon = true }.start()
        }
    }

    private fun download(): RemovedIdBloom {
        val connection = URL(REMOVED_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "BroStream/8")
        try {
            return connection.inputStream.bufferedReader().use { reader ->
                val built = RemovedIdBloom()
                while (true) {
                    val line = reader.readLine() ?: break
                    val id = line.trim()
                    if (id.isNotEmpty()) built.add(id)
                }
                built
            }
        } finally {
            connection.disconnect()
        }
    }

    private const val REMOVED_URL = "https://www.eporner.com/api/v2/video/removed/?format=txt"
}

internal fun epornerRate(node: JsonNode?): Double = when {
    node == null || node.isNull -> 0.0
    node.isNumber -> node.asDouble()
    node.isTextual -> node.asText().toDoubleOrNull() ?: 0.0
    else -> 0.0
}
