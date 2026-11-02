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
 * Male Chaturbate rooms from the official affiliate online-rooms API only.
 *
 * Gender stays `m`. [CAMPAIGN_CODE] stays `0esJu`. Rows can add `hd=true`,
 * one `region`, and up to five `tag` parameters. Each page is ordered HD first,
 * then by `num_users`. The API does not return a media playlist, and this file
 * does not request one. Rooms open through the affiliate room link.
 */
object ChaturbateMaleLive {

    const val CAMPAIGN_CODE = "0esJu"

    const val ROW_KEY = "CB_LIVE_MALE"
    const val ROW_NAME = "🔴 Live Male Cams (Chaturbate)"
    private const val PAGE_SIZE = 40
    private const val MARKER = "cb_live_male"
    private const val API = "https://chaturbate.com/api/public/affiliates/onlinerooms/"
    private const val MAX_TAGS = 5

    val REGIONS = listOf(
        "northamerica" to "North America",
        "europe_russia" to "Europe & Russia",
        "southamerica" to "South America",
        "asia" to "Asia",
        "other" to "Other regions",
    )

    /** Single-tag rows. The affiliate API accepts at most five tag parameters. */
    val TAGS = listOf(
        "muscle", "bear", "latino", "daddy", "twink", "uncut",
        "asian", "black", "hairy", "jock", "chubby", "bigcock",
    )

    private val mapper = jacksonObjectMapper()

    val isConfigured: Boolean
        get() = CAMPAIGN_CODE.isNotBlank() && !CAMPAIGN_CODE.startsWith("REPLACE_")

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Room(
        val username: String = "",
        @JsonProperty("display_name") val displayName: String? = null,
        @JsonProperty("room_subject") val roomSubject: String? = null,
        val gender: String? = null,
        val age: Int? = null,
        @JsonProperty("num_users") val numUsers: Int? = null,
        val tags: List<String>? = null,
        @JsonProperty("image_url") val imageUrl: String? = null,
        @JsonProperty("image_url_360x270") val imageUrl360: String? = null,
        @JsonProperty("chat_room_url") val chatRoomUrl: String? = null,
        @JsonProperty("chat_room_url_revshare") val chatRoomUrlRevshare: String? = null,
        @JsonProperty("iframe_embed") val iframeEmbed: String? = null,
        @JsonProperty("iframe_embed_revshare") val iframeEmbedRevshare: String? = null,
        @JsonProperty("current_show") val currentShow: String? = null,
        @JsonProperty("is_hd") val isHd: Boolean? = null,
    )

    data class Query(
        val hd: Boolean = false,
        val region: String? = null,
        val tags: List<String> = emptyList(),
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LiveItem(
        val kind: String = MARKER,
        val username: String = "",
        val title: String = "",
        val poster: String? = null,
        val roomUrl: String = "",
        val embedUrl: String? = null,
        val viewers: Int? = null,
        val tags: List<String> = emptyList(),
        val subject: String? = null,
        val hd: Boolean = false,
    )

    fun handlesRow(data: String): Boolean = data == ROW_KEY || data.startsWith("CB|")

    fun handles(data: String): Boolean =
        data.contains("\"kind\":\"$MARKER\"") || Regex("^https://([a-z]+\\.)?chaturbate\\.com/").containsMatchIn(data)

    fun rows(): List<OfficialRow> {
        val rows = mutableListOf(
            OfficialRow(ROW_KEY, ROW_NAME, core = true, defaultOn = true),
            OfficialRow("CB|hd", "🔴 Chaturbate HD men", core = true, defaultOn = true),
        )
        REGIONS.forEach { (id, label) ->
            rows += OfficialRow("CB|region|$id", "🔴 Chaturbate · $label", core = false, defaultOn = true)
        }
        TAGS.forEach { tag ->
            rows += OfficialRow("CB|tag|$tag", "🔴 Chaturbate · #$tag", core = false, defaultOn = true)
        }
        return rows
    }

    /** Extra AND-row when the viewer has saved two to five tag rows. */
    fun mixRow(selectedTagIds: List<String>): OfficialRow? {
        val tags = selectedTagIds.map { it.removePrefix("CB|tag|") }.filter { it in TAGS }.distinct().take(MAX_TAGS)
        if (tags.size !in 2..MAX_TAGS) return null
        return OfficialRow("CB|tags|${tags.joinToString(",")}", "🔴 Chaturbate · ${tags.joinToString(" + ")}", core = true, defaultOn = true)
    }

    internal fun queryFor(row: String): Query = when {
        row == "CB|hd" -> Query(hd = true)
        row.startsWith("CB|region|") -> Query(region = row.removePrefix("CB|region|").takeIf { it.isNotBlank() })
        row.startsWith("CB|tag|") -> Query(tags = listOfNotNull(row.removePrefix("CB|tag|").takeIf { it.isNotBlank() }))
        row.startsWith("CB|tags|") -> Query(tags = row.removePrefix("CB|tags|").split(',').map { it.trim() }.filter { it.isNotEmpty() })
        else -> Query()
    }

    internal fun apiUrl(page: Int, query: Query): String {
        val offset = (page - 1).coerceAtLeast(0) * PAGE_SIZE
        val tags = query.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_TAGS)
        return buildString {
            append(API)
            append("?format=json&wm=")
            append(URLEncoder.encode(CAMPAIGN_CODE, "UTF-8"))
            append("&client_ip=request_ip&gender=m&limit=")
            append(PAGE_SIZE)
            append("&offset=")
            append(offset)
            if (query.hd) append("&hd=true")
            query.region?.takeIf { it.isNotBlank() }?.let {
                append("&region=")
                append(URLEncoder.encode(it, "UTF-8"))
            }
            tags.forEach {
                append("&tag=")
                append(URLEncoder.encode(it, "UTF-8"))
            }
        }
    }

    internal fun parseRooms(body: String): List<Room> {
        val root: JsonNode = runCatching { mapper.readTree(body) }.getOrNull() ?: return emptyList()
        val array = when {
            root.isArray -> root
            root.has("results") -> root.get("results")
            root.has("rooms") -> root.get("rooms")
            else -> return emptyList()
        }
        return array.mapNotNull { runCatching { mapper.treeToValue(it, Room::class.java) }.getOrNull() }
    }

    internal fun present(rooms: List<Room>): List<Room> = rooms
        .filter { it.username.isNotBlank() && (it.gender == null || it.gender == "m") }
        .filter { it.age == null || it.age >= 18 }
        .filter { it.currentShow == null || it.currentShow == "public" }
        .orderHdThenPopularity(hd = { it.isHd == true }, popularity = { (it.numUsers ?: 0).toLong() })

    private fun roomLink(room: Room): String =
        room.chatRoomUrlRevshare?.takeIf { it.startsWith("http") }
            ?: room.chatRoomUrl?.takeIf { it.startsWith("http") }
            ?: "https://chaturbate.com/${room.username}/"

    private fun embedLink(room: Room): String? = officialEmbedSrc(room.iframeEmbedRevshare ?: room.iframeEmbed)

    private suspend fun fetchRooms(page: Int, query: Query): Pair<List<Room>, Boolean> {
        if (!isConfigured) return emptyList<Room>() to false
        val body = runCatching { app.get(apiUrl(page, query), timeout = 20).text }.getOrNull()
            ?: return emptyList<Room>() to false
        val parsed = parseRooms(body)
        return present(parsed) to (parsed.size >= PAGE_SIZE)
    }

    suspend fun homePage(api: MainAPI, page: Int, rowName: String, rowKey: String = ROW_KEY): HomePageResponse {
        val (rooms, full) = fetchRooms(page, queryFor(rowKey))
        val items = rooms.map { room ->
            val item = LiveItem(
                username = room.username,
                title = room.displayName?.takeIf { it.isNotBlank() } ?: room.username,
                poster = room.imageUrl360 ?: room.imageUrl,
                roomUrl = roomLink(room),
                embedUrl = embedLink(room),
                viewers = room.numUsers,
                tags = room.tags.orEmpty(),
                subject = room.roomSubject,
                hd = room.isHd == true,
            )
            val viewers = item.viewers?.let { " · $it" }.orEmpty()
            val hd = if (item.hd) " HD" else ""
            api.newLiveSearchResponse("🔴 ${item.title}$hd$viewers", mapper.writeValueAsString(item), TvType.Live) {
                posterUrl = item.poster
            }
        }
        return newHomePageResponse(HomePageList(rowName, items, true), hasNext = full)
    }

    suspend fun search(api: MainAPI, query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val needle = query.trim().lowercase()
        val (rooms, _) = fetchRooms(1, Query())
        return rooms.filter { room ->
            listOf(room.username, room.displayName, room.roomSubject, room.tags.orEmpty().joinToString(" "))
                .any { it?.lowercase()?.contains(needle) == true }
        }.map { room ->
            val item = LiveItem(
                username = room.username,
                title = room.displayName?.takeIf { it.isNotBlank() } ?: room.username,
                poster = room.imageUrl360 ?: room.imageUrl,
                roomUrl = roomLink(room),
                embedUrl = embedLink(room),
                viewers = room.numUsers,
                tags = room.tags.orEmpty(),
                subject = room.roomSubject,
                hd = room.isHd == true,
            )
            api.newLiveSearchResponse("🔴 ${item.title}", mapper.writeValueAsString(item), TvType.Live) {
                posterUrl = item.poster
            }
        }
    }

    private fun parseItem(data: String): LiveItem? {
        if (data.startsWith("https://")) {
            val user = Regex("[?&]room=([A-Za-z0-9_]+)").find(data)?.groupValues?.get(1)
                ?: data.substringAfter("chaturbate.com/").trim('/').substringBefore('/').substringBefore('?')
            return LiveItem(username = user, title = user, roomUrl = data).takeIf { user.isNotBlank() }
        }
        return runCatching { mapper.readValue<LiveItem>(data) }.getOrNull()?.takeIf { it.kind == MARKER && it.username.isNotBlank() }
    }

    suspend fun load(api: MainAPI, data: String): LoadResponse? {
        val item = parseItem(data) ?: return null
        val plot = buildString {
            item.subject?.takeIf { it.isNotBlank() }?.let { appendLine(it); appendLine() }
            item.viewers?.let { appendLine("👀 $it watching now") }
            if (item.hd) appendLine("HD room")
            appendLine("Open room (official link): ${item.roomUrl}")
            item.embedUrl?.let { appendLine("Official embed player: $it") }
            appendLine("Live rooms open in the browser or Chaturbate app. The affiliate feed does not include a media playlist.")
        }
        return api.newLiveStreamLoadResponse(item.title, item.roomUrl, data) {
            posterUrl = item.poster
            this.plot = plot
            tags = listOf("Live", "Chaturbate") + item.tags.take(8)
        }
    }

    /**
     * The affiliate payload has room pages and iframe HTML, not a media playlist.
     * Nothing is passed to the player, and no private stream endpoint is called.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun loadLinks(data: String, callback: (ExtractorLink) -> Unit): Boolean = false
}
