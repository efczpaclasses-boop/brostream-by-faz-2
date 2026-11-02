package com.epornergay

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URLEncoder

/**
 * Optional gay rows from the Pornhub webmasters API
 * (`/webmasters/search`, `/webmasters/categories`, `/webmasters/video_embed_code`).
 *
 * Category rows are the `*-gay` slugs from the official categories payload.
 * Search hits are kept only when `segment` is `gay`. The details page opens
 * the iframe URL returned by `/video_embed_code`, not a URL built locally.
 */
object PornhubGayWebmasters {

    private const val API = "https://www.pornhub.com/webmasters"
    private const val MARKER = "ph_gay"
    private val mapper = jacksonObjectMapper()

    /**
     * `category` values ending in `-gay` from the official `/webmasters/categories`
     * JSON. A live call from this environment is redirected to the site homepage,
     * so the rows use that captured list. [gayCategorySlugs] still reads a live
     * JSON body when one is available.
     */
    val GAY_CATEGORIES = listOf(
        "amateur-gay", "asian-gay", "bareback-gay", "bear-gay", "big-dick-gay", "black-gay",
        "blowjob-gay", "cartoon-gay", "casting-gay", "chubby-gay", "closed-captions-gay",
        "compilation-gay", "creampie-gay", "cumshot-gay", "daddy-gay", "euro-gay", "feet-gay",
        "fetish-gay", "group-gay", "handjob-gay", "hd-porn-gay", "hunks-gay", "interracial-gay",
        "japanese-gay", "jock-gay", "latino-gay", "massage-gay", "mature-gay", "military-gay",
        "muscle-gay", "pornstar-gay", "pov-gay", "public-gay", "reality-gay", "rough-sex-gay",
        "solo-male-gay", "straight-guys-gay", "tattooed-men-gay", "uncut-gay",
        "verified-amateurs-gay", "vintage-gay", "webcam-gay",
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Video(
        val title: String = "",
        @JsonProperty("video_id") val videoId: String = "",
        val segment: String = "",
        val views: JsonNode? = null,
        val url: String = "",
        val thumb: String? = null,
        @JsonProperty("default_thumb") val defaultThumb: String? = null,
        val rating: JsonNode? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchResponseBody(val videos: List<Video> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Category(val category: String = "")

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CategoriesBody(val categories: List<Category> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Embed(val code: String = "")

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EmbedBody(val embed: Embed? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Item(
        val kind: String = MARKER,
        val id: String = "",
        val title: String = "",
        val poster: String? = null,
        val pageUrl: String = "",
        val views: Long = 0,
        val category: String = "",
    )

    fun rows(): List<OfficialRow> = GAY_CATEGORIES.map { slug ->
        OfficialRow("PH|$slug", "Pornhub · ${slug.removeSuffix("-gay")}", core = false, defaultOn = true)
    }

    fun handlesRow(data: String): Boolean = data.startsWith("PH|")
    fun handles(data: String): Boolean = data.contains("\"kind\":\"$MARKER\"")

    internal fun gayCategorySlugs(body: String): List<String> {
        val parsed = runCatching { mapper.readValue<CategoriesBody>(body) }.getOrNull() ?: return emptyList()
        return parsed.categories.map { it.category }.filter { it.endsWith("-gay") }.distinct()
    }

    internal fun parseSearch(body: String): List<Video> =
        runCatching { mapper.readValue<SearchResponseBody>(body).videos }.getOrDefault(emptyList())

    internal fun gayOnly(videos: List<Video>): List<Video> =
        videos.filter { it.segment.equals("gay", true) && it.videoId.isNotBlank() && it.title.isNotBlank() }
            .sortedByDescending { viewsOf(it.views) }

    internal fun viewsOf(node: JsonNode?): Long = when {
        node == null || node.isNull -> 0L
        node.isNumber -> node.asLong()
        node.isTextual -> node.asText().filter { it.isDigit() }.toLongOrNull() ?: 0L
        else -> 0L
    }

    internal fun embedSrc(body: String): String? {
        val code = runCatching { mapper.readValue<EmbedBody>(body).embed?.code }.getOrNull()
        return officialEmbedSrc(code)
    }

    internal fun searchUrl(page: Int, category: String?, query: String?): String = buildString {
        append(API)
        append("/search?thumbsize=medium&ordering=mostviewed&period=weekly&page=")
        append(page.coerceAtLeast(1))
        category?.takeIf { it.isNotBlank() }?.let {
            append("&category=")
            append(URLEncoder.encode(it, "UTF-8"))
        }
        query?.takeIf { it.isNotBlank() }?.let {
            append("&search=")
            append(URLEncoder.encode(it, "UTF-8"))
        }
    }

    private fun embedEndpoint(id: String) = "$API/video_embed_code?id=${URLEncoder.encode(id, "UTF-8")}"

    private fun item(video: Video, category: String): Item? {
        if (!video.url.startsWith("http")) return null
        return Item(
            id = video.videoId,
            title = video.title,
            poster = video.thumb?.takeIf { it.startsWith("http") } ?: video.defaultThumb?.takeIf { it.startsWith("http") },
            pageUrl = video.url,
            views = viewsOf(video.views),
            category = category,
        )
    }

    private suspend fun fetch(page: Int, category: String?, query: String?): Pair<List<Item>, Boolean> {
        val body = runCatching { app.get(searchUrl(page, category, query), timeout = 25).text }.getOrNull()
            ?: return emptyList<Item>() to false
        if (!body.trim().startsWith("{")) return emptyList<Item>() to false
        val parsed = parseSearch(body)
        val kept = gayOnly(parsed)
        return kept.mapNotNull { item(it, category.orEmpty()) } to (parsed.size >= 20)
    }

    suspend fun homePage(api: MainAPI, page: Int, rowName: String, rowKey: String): HomePageResponse {
        val category = rowKey.removePrefix("PH|")
        val (items, more) = fetch(page, category, null)
        val cards = items.map { entry ->
            api.newMovieSearchResponse("${entry.title} · ${entry.views}", mapper.writeValueAsString(entry), TvType.NSFW) {
                posterUrl = entry.poster
            }
        }
        return newHomePageResponse(HomePageList(rowName, cards, true), hasNext = more && cards.isNotEmpty())
    }

    suspend fun search(api: MainAPI, query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val (items, _) = fetch(1, null, query.trim())
        return items.map { entry ->
            api.newMovieSearchResponse(entry.title, mapper.writeValueAsString(entry), TvType.NSFW) { posterUrl = entry.poster }
        }
    }

    suspend fun officialEmbed(id: String): String? {
        if (id.isBlank()) return null
        val body = runCatching { app.get(embedEndpoint(id), timeout = 20).text }.getOrNull() ?: return null
        if (!body.trim().startsWith("{")) return null
        return embedSrc(body)
    }

    suspend fun load(api: MainAPI, data: String): LoadResponse? {
        val item = runCatching { mapper.readValue<Item>(data) }.getOrNull()?.takeIf { it.kind == MARKER } ?: return null
        val embed = officialEmbed(item.id)
        val open = embed ?: item.pageUrl
        return api.newMovieLoadResponse(item.title, open, TvType.NSFW, data) {
            posterUrl = item.poster
            plot = if (embed != null) "Official embed from /video_embed_code: $embed" else
                "The webmasters embed endpoint did not return an iframe for this id."
            tags = listOfNotNull("Pornhub", item.category.takeIf { it.isNotBlank() })
        }
    }

    /**
     * Playback is the iframe from `/video_embed_code`. That document is not a
     * media file, so it is opened from the details page instead of the player.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun loadLinks(data: String, callback: (ExtractorLink) -> Unit): Boolean = false
}
