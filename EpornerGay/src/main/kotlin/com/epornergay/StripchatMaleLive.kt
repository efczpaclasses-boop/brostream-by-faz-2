package com.epornergay

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

/**
 * Male Stripchat rooms from the documented Stripcash models API
 * (https://go.rmhfrtnd.com/api/models, /api/models/online, /api/dicts).
 *
 * `gender=male` is not used: that parameter is ignored. Every request sends
 * `tag=men`. Category rows add a men/ tag or `profileSubculture`. HD rows send
 * `broadcastHD=true`. Playback uses only `stream.url` and `stream.urls` from
 * the response. [AFFILIATE_USER_ID] is optional and empty by default.
 */
object StripchatMaleLive {

    /** Optional Stripcash affiliate user id. Empty omits `userId`. */
    const val AFFILIATE_USER_ID = ""

    private const val API = "https://go.rmhfrtnd.com/api/models"
    private const val ONLINE = "https://go.rmhfrtnd.com/api/models/online"
    private const val PAGE_SIZE = 40
    private const val MARKER = "sc_live_male"
    private const val FIELDS =
        "id,username,gender,broadcastGender,broadcastHD,viewersCount,status,snapshotUrl,popularSnapshotUrl,previewUrlThumbSmall,stream,tags,profileSubculture"

    val TAGS = listOf(
        "bears" to "Bears",
        "muscular" to "Muscular",
        "latin" to "Latin",
        "asian" to "Asian",
        "twinks" to "Twinks",
        "jocks" to "Jocks",
        "daddies" to "Daddies",
        "hunks" to "Hunks",
        "cubs" to "Cubs",
        "chubbies" to "Chubbies",
        "otters" to "Otters",
        "uncut" to "Uncut",
        "hairy" to "Hairy",
        "leather" to "Leather",
        "big-cocks" to "Big cocks",
        "outdoor" to "Outdoor",
    )

    val SUBCULTURES = listOf(
        "subcultureBears" to "Bears",
        "subcultureStudent" to "Students",
        "subcultureGamers" to "Gamers",
        "subcultureHipster" to "Hipsters",
        "subcultureEmo" to "Emo",
        "subcultureGoth" to "Goth",
        "subcultureRomantic" to "Romantic",
        "subcultureGlamour" to "Glamour",
        "subcultureBdsm" to "BDSM",
    )

    private val maleGenders = setOf("male", "males")
    private val watchableStatuses = setOf("public", "groupshow")
    private val mapper = jacksonObjectMapper()
    private val onlineLock = Any()

    @Volatile private var onlineAt = 0L
    @Volatile private var online: Map<String, String>? = null

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Stream(val url: String? = null, val urls: Map<String, String>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Model(
        val id: Long = 0,
        val username: String = "",
        val gender: String = "",
        val broadcastHD: Boolean = false,
        val viewersCount: Long = 0,
        val status: String = "",
        val snapshotUrl: String? = null,
        val popularSnapshotUrl: String? = null,
        val previewUrlThumbSmall: String? = null,
        val tags: List<String> = emptyList(),
        val profileSubculture: String? = null,
        val stream: Stream? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ModelsResponse(val models: List<Model> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class OnlineModel(val status: String = "")

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class OnlineResponse(val models: Map<String, OnlineModel> = emptyMap())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LiveItem(
        val kind: String = MARKER,
        val username: String = "",
        val title: String = "",
        val poster: String? = null,
        val roomUrl: String = "",
        val viewers: Long = 0,
        val hd: Boolean = false,
        val tags: List<String> = emptyList(),
        val streams: List<String> = emptyList(),
    )

    data class Query(val hd: Boolean = false, val tag: String? = null, val subculture: String? = null)

    fun rows(): List<OfficialRow> {
        val rows = mutableListOf(
            OfficialRow("SC|men", "🟣 Stripchat men", core = true, defaultOn = true),
            OfficialRow("SC|hd", "🟣 Stripchat HD men", core = true, defaultOn = true),
        )
        TAGS.forEach { (slug, label) ->
            rows += OfficialRow("SC|tag|$slug", "🟣 Stripchat · $label", core = false, defaultOn = true)
        }
        SUBCULTURES.forEach { (slug, label) ->
            rows += OfficialRow("SC|sub|$slug", "🟣 Stripchat · $label", core = false, defaultOn = slug != "subcultureBdsm")
        }
        return rows
    }

    fun handlesRow(data: String): Boolean = data.startsWith("SC|")
    fun handles(data: String): Boolean = data.contains("\"kind\":\"$MARKER\"")

    internal fun queryFor(row: String): Query = when {
        row == "SC|hd" -> Query(hd = true)
        row.startsWith("SC|tag|") -> Query(tag = row.removePrefix("SC|tag|"))
        row.startsWith("SC|sub|") -> Query(subculture = row.removePrefix("SC|sub|"))
        else -> Query()
    }

    internal fun apiUrl(page: Int, query: Query, affiliateId: String = AFFILIATE_USER_ID): String {
        val offset = (page - 1).coerceAtLeast(0) * PAGE_SIZE
        val tag = when {
            !query.tag.isNullOrBlank() -> "men/${query.tag}"
            else -> "men"
        }
        return buildString {
            append(API)
            append("?tag=")
            append(URLEncoder.encode(tag, "UTF-8"))
            append("&sortBy=viewersCount&limit=")
            append(PAGE_SIZE)
            append("&offset=")
            append(offset)
            append("&fields=")
            append(URLEncoder.encode(FIELDS, "UTF-8"))
            if (query.hd) append("&broadcastHD=true")
            query.subculture?.takeIf { it.isNotBlank() }?.let {
                append("&profileSubculture=")
                append(URLEncoder.encode(it, "UTF-8"))
            }
            if (affiliateId.isNotBlank()) {
                append("&userId=")
                append(URLEncoder.encode(affiliateId, "UTF-8"))
            }
        }
    }

    internal fun parseModels(body: String): List<Model> =
        runCatching { mapper.readValue<ModelsResponse>(body).models }.getOrDefault(emptyList())

    internal fun parseOnline(body: String): Map<String, String> =
        runCatching { mapper.readValue<OnlineResponse>(body).models.mapValues { it.value.status } }.getOrDefault(emptyMap())

    internal fun streamUrls(model: Model): List<String> {
        val raw = listOfNotNull(model.stream?.url) + (model.stream?.urls?.values ?: emptyList())
        return raw.filter { it.startsWith("http") }.distinct().sortedByDescending { streamRank(it) }
    }

    internal fun present(models: List<Model>, online: Map<String, String>?, query: Query): List<Model> =
        models.filter { model ->
            model.username.isNotBlank() &&
                model.gender.lowercase() in maleGenders &&
                watchable(online?.get(model.id.toString()) ?: model.status) &&
                (!query.hd || model.broadcastHD) &&
                (query.tag.isNullOrBlank() || model.tags.any { it.equals("men/${query.tag}", true) }) &&
                (query.subculture.isNullOrBlank() || model.profileSubculture.equals(query.subculture, true))
        }.orderHdThenPopularity(hd = { it.broadcastHD }, popularity = { it.viewersCount })

    private fun watchable(status: String): Boolean = status.lowercase() in watchableStatuses

    private fun streamRank(url: String): Int = when {
        "1080" in url -> 1080
        "720" in url -> 720
        "480" in url -> 480
        "240" in url -> 240
        "160" in url -> 160
        else -> 1
    }

    private suspend fun onlineStatuses(): Map<String, String>? {
        val cached = online
        if (cached != null && System.currentTimeMillis() - onlineAt < 15_000) return cached
        val body = runCatching { app.get(ONLINE, timeout = 20).text }.getOrNull() ?: return cached
        val parsed = parseOnline(body)
        synchronized(onlineLock) {
            online = parsed
            onlineAt = System.currentTimeMillis()
        }
        return parsed
    }

    private fun item(model: Model): LiveItem? {
        if (!model.username.matches(Regex("[A-Za-z0-9_\\-]+"))) return null
        val poster = listOf(model.popularSnapshotUrl, model.snapshotUrl, model.previewUrlThumbSmall)
            .firstOrNull { it?.startsWith("http") == true }
        return LiveItem(
            username = model.username,
            title = model.username,
            poster = poster,
            roomUrl = "https://stripchat.com/${model.username}",
            viewers = model.viewersCount,
            hd = model.broadcastHD,
            tags = model.tags,
            streams = streamUrls(model),
        )
    }

    suspend fun homePage(api: MainAPI, page: Int, rowName: String, rowKey: String): HomePageResponse {
        val query = queryFor(rowKey)
        val body = runCatching { app.get(apiUrl(page, query), timeout = 25).text }.getOrNull()
        val parsed = body?.let(::parseModels).orEmpty()
        val rooms = present(parsed, runCatching { onlineStatuses() }.getOrNull(), query)
        val items = rooms.mapNotNull { model ->
            val entry = item(model) ?: return@mapNotNull null
            val hd = if (entry.hd) " HD" else ""
            api.newLiveSearchResponse("🟣 ${entry.title}$hd · ${entry.viewers}", mapper.writeValueAsString(entry), TvType.Live) {
                posterUrl = entry.poster
            }
        }
        return newHomePageResponse(HomePageList(rowName, items, true), hasNext = parsed.size >= PAGE_SIZE)
    }

    suspend fun search(api: MainAPI, query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val needle = query.trim().lowercase()
        val body = runCatching { app.get(apiUrl(1, Query()), timeout = 25).text }.getOrNull() ?: return emptyList()
        return present(parseModels(body), runCatching { onlineStatuses() }.getOrNull(), Query())
            .filter { it.username.lowercase().contains(needle) || it.tags.any { tag -> tag.lowercase().contains(needle) } }
            .mapNotNull { model ->
                val entry = item(model) ?: return@mapNotNull null
                api.newLiveSearchResponse("🟣 ${entry.title}", mapper.writeValueAsString(entry), TvType.Live) {
                    posterUrl = entry.poster
                }
            }
    }

    private fun parseItem(data: String): LiveItem? =
        runCatching { mapper.readValue<LiveItem>(data) }.getOrNull()?.takeIf { it.kind == MARKER && it.streams.isNotEmpty() }

    suspend fun load(api: MainAPI, data: String): LoadResponse? {
        val item = runCatching { mapper.readValue<LiveItem>(data) }.getOrNull()?.takeIf { it.kind == MARKER } ?: return null
        return api.newLiveStreamLoadResponse(item.title, item.roomUrl, data) {
            posterUrl = item.poster
            plot = buildString {
                appendLine("${item.viewers} watching")
                if (item.hd) appendLine("HD broadcast")
                append("Playback uses the HLS links included in the models API response.")
            }
            tags = listOf("Live", "Stripchat") + item.tags.take(8)
        }
    }

    suspend fun loadLinks(data: String, callback: (ExtractorLink) -> Unit): Boolean {
        val item = parseItem(data) ?: return false
        item.streams.forEach { stream ->
            val rank = streamRank(stream)
            callback(newExtractorLink(
                "Stripchat",
                if (rank > 1) "Stripchat ${rank}p" else "Stripchat",
                stream,
                if (stream.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            ) {
                referer = "https://stripchat.com/"
                quality = rank.takeIf { it > 1 } ?: Qualities.Unknown.value
            })
        }
        return item.streams.isNotEmpty()
    }
}
