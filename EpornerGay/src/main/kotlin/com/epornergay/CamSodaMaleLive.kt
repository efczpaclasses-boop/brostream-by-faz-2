package com.epornergay

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

/**
 * Male CamSoda rooms from the official affiliate feed
 * `https://feed.camsoda.com/api/v1/browse/online_embed`.
 *
 * [AFFILIATE_ID] is the `id` parameter. It is empty by default. While it is
 * empty, catalogue rows are hidden and a single note is shown instead.
 * Playback uses `link_iframe` and `livestream_hls` exactly as returned.
 *
 * The same feed, and the public affiliate descriptions of it, expose live
 * rooms only. No official video or VOD catalogue was found, so none is added.
 */
object CamSodaMaleLive {

    /**
     * EMPTY BY DEFAULT.
     * Set this to your CamSoda affiliate id to show male online_embed rows.
     */
    const val AFFILIATE_ID = ""

    const val NOTE_ROW = "CS|note"
    private const val API = "https://feed.camsoda.com/api/v1/browse/online_embed"
    private const val MARKER = "cs_live_male"
    private const val NOTE = "cs_note"

    /** Tags accepted by the feed's `tag` parameter. `tags` is ignored by the feed. */
    val TAGS = listOf("muscular", "daddy", "hd", "blowjob", "tattoos", "fetish")

    private val mapper = jacksonObjectMapper()

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Model(
        val username: String = "",
        val name: String = "",
        val gender: String = "",
        val viewers: Long = 0,
        val thumb: String? = null,
        val tags: List<String> = emptyList(),
        val subject: String? = null,
        val link: String? = null,
        @JsonProperty("link_iframe") val linkIframe: String? = null,
        @JsonProperty("livestream_hls") val livestreamHls: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Response(val results: List<Model> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Item(
        val kind: String = MARKER,
        val username: String = "",
        val title: String = "",
        val poster: String? = null,
        val pageUrl: String = "",
        val iframe: String? = null,
        val hls: String? = null,
        val viewers: Long = 0,
        val hd: Boolean = false,
        val tags: List<String> = emptyList(),
        val subject: String? = null,
        val note: String? = null,
    )

    fun configured(id: String = AFFILIATE_ID): Boolean = id.isNotBlank()

    fun rows(id: String = AFFILIATE_ID): List<OfficialRow> {
        if (!configured(id)) return emptyList()
        val rows = mutableListOf(OfficialRow("CS|male", "🟠 CamSoda men", core = true, defaultOn = true))
        TAGS.forEach { tag ->
            rows += OfficialRow("CS|tag|$tag", "🟠 CamSoda · #$tag", core = false, defaultOn = false)
        }
        return rows
    }

    fun handlesRow(data: String): Boolean = data == NOTE_ROW || data.startsWith("CS|")
    fun handles(data: String): Boolean = data.contains("\"kind\":\"$MARKER\"") || data.contains("\"kind\":\"$NOTE\"")

    internal fun apiUrl(page: Int, tag: String?, id: String = AFFILIATE_ID): String = buildString {
        append(API)
        append("?id=")
        append(URLEncoder.encode(id, "UTF-8"))
        append("&index=")
        append(page.coerceAtLeast(1))
        append("&gender=m")
        tag?.takeIf { it.isNotBlank() }?.let {
            append("&tag=")
            append(URLEncoder.encode(it, "UTF-8"))
        }
    }

    internal fun parse(body: String): List<Model> =
        runCatching { mapper.readValue<Response>(body).results }.getOrDefault(emptyList())

    internal fun present(models: List<Model>, tag: String?): List<Model> = models
        .filter { it.gender.equals("m", true) && it.username.isNotBlank() }
        .filter { tag.isNullOrBlank() || it.tags.any { value -> value.equals(tag, true) } }
        .orderHdThenPopularity(
            hd = { model -> model.tags.any { it.equals("hd", true) } },
            popularity = { it.viewers },
        )

    private fun tagFor(row: String): String? = row.removePrefix("CS|tag|").takeIf { row.startsWith("CS|tag|") && it.isNotBlank() }

    private fun item(model: Model): Item {
        val hd = model.tags.any { it.equals("hd", true) }
        val page = model.link?.takeIf { it.startsWith("http") } ?: model.linkIframe?.takeIf { it.startsWith("http") }
            ?: "https://www.camsoda.com/${model.username}"
        return Item(
            username = model.username,
            title = model.name.ifBlank { model.username },
            poster = model.thumb?.takeIf { it.startsWith("http") },
            pageUrl = page,
            iframe = model.linkIframe?.takeIf { it.startsWith("http") },
            hls = model.livestreamHls?.takeIf { it.startsWith("http") },
            viewers = model.viewers,
            hd = hd,
            tags = model.tags,
            subject = model.subject,
        )
    }

    suspend fun homePage(api: MainAPI, page: Int, rowName: String, rowKey: String): HomePageResponse {
        if (!configured()) {
            val note = Item(
                kind = NOTE,
                title = "CamSoda rows are hidden",
                pageUrl = API,
                note = "CAMSODA_AFFILIATE_ID in CamSodaMaleLive.kt is empty. Set it to your CamSoda affiliate id to show the official male online_embed feed. That feed is live rooms only; it does not document a video-on-demand catalogue.",
            )
            val card = api.newMovieSearchResponse(note.title, mapper.writeValueAsString(note), TvType.NSFW, false) {}
            return newHomePageResponse(HomePageList(rowName, listOf(card), false), hasNext = false)
        }
        val tag = tagFor(rowKey)
        val body = runCatching { app.get(apiUrl(page, tag), timeout = 20).text }.getOrNull()
        val parsed = body?.let(::parse).orEmpty()
        val rooms = present(parsed, tag)
        val items = rooms.map { model ->
            val entry = item(model)
            val hd = if (entry.hd) " HD" else ""
            api.newLiveSearchResponse("🟠 ${entry.title}$hd · ${entry.viewers}", mapper.writeValueAsString(entry), TvType.Live, false) {
                posterUrl = entry.poster
            }
        }
        return newHomePageResponse(HomePageList(rowName, items, true), hasNext = parsed.isNotEmpty())
    }

    suspend fun search(api: MainAPI, query: String): List<SearchResponse> {
        if (!configured() || query.isBlank()) return emptyList()
        val needle = query.trim().lowercase()
        val body = runCatching { app.get(apiUrl(1, null), timeout = 20).text }.getOrNull() ?: return emptyList()
        return present(parse(body), null).filter { model ->
            listOf(model.username, model.name, model.subject, model.tags.joinToString(" ")).any { it?.lowercase()?.contains(needle) == true }
        }.map { model ->
            val entry = item(model)
            api.newLiveSearchResponse("🟠 ${entry.title}", mapper.writeValueAsString(entry), TvType.Live, false) { posterUrl = entry.poster }
        }
    }

    suspend fun load(api: MainAPI, data: String): LoadResponse? {
        val item = runCatching { mapper.readValue<Item>(data) }.getOrNull() ?: return null
        if (item.kind == NOTE) {
            return api.newMovieLoadResponse(item.title, item.pageUrl, TvType.NSFW, data) {
                plot = item.note
                tags = listOf("CamSoda")
            }
        }
        if (item.kind != MARKER) return null
        return api.newLiveStreamLoadResponse(item.title, item.pageUrl, data) {
            posterUrl = item.poster
            plot = buildString {
                item.subject?.let { appendLine(it) }
                appendLine("${item.viewers} watching")
                item.iframe?.let { appendLine("Official iframe: $it") }
                item.hls?.let { appendLine("Official HLS: $it") }
            }
            tags = listOf("Live", "CamSoda") + item.tags.take(8)
        }
    }

    suspend fun loadLinks(data: String, callback: (ExtractorLink) -> Unit): Boolean {
        val item = runCatching { mapper.readValue<Item>(data) }.getOrNull()?.takeIf { it.kind == MARKER } ?: return false
        var any = false
        item.hls?.let { hls ->
            any = true
            callback(newExtractorLink(
                "CamSoda",
                if (item.hd) "CamSoda HD" else "CamSoda",
                hls,
                if (hls.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            ) {
                referer = "https://www.camsoda.com/"
                quality = if (item.hd) Qualities.P720.value else Qualities.Unknown.value
            })
        }
        item.iframe?.let { iframe ->
            any = true
            callback(newExtractorLink("CamSoda", "CamSoda embed", iframe, ExtractorLinkType.VIDEO) {
                referer = "https://www.camsoda.com/"
                quality = Qualities.Unknown.value
            })
        }
        return any
    }
}
