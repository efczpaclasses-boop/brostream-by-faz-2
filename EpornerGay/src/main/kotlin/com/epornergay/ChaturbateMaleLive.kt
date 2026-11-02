package com.epornergay

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/**
 * "🔴 Live Male Cams (Chaturbate)" row, powered ONLY by Chaturbate's official
 * affiliate "online rooms" API (Promo Tools → JSON/XML API in your affiliate account).
 *
 * What this does:
 *  - Lists currently-online male rooms (gender=m) with thumbnail, title, viewers and tags.
 *  - Opens each room via the official affiliate (revshare) room link / iframe embed
 *    returned by the API, which carries YOUR campaign code.
 *
 * Honest limitation:
 *  - The official API does not hand out raw HLS/m3u8 stream URLs, and this code does
 *    NOT scrape Chaturbate's private endpoints for them. CloudStream's built-in player
 *    can only play direct media links, so rooms are NOT played inside CloudStream.
 *    The details page shows the official room link, and the result's URL is that link,
 *    so "Open in browser"/share opens the room in a browser or the Chaturbate app.
 */
object ChaturbateMaleLive {

    // Public Chaturbate affiliate campaign code ("wm" slug) from Promo Tools → API.
    const val CAMPAIGN_CODE = "0esJu"

    const val ROW_KEY = "CB_LIVE_MALE"
    const val ROW_NAME = "🔴 Live Male Cams (Chaturbate)"
    private const val PAGE_SIZE = 40
    private const val MARKER = "cb_live_male"

    // Official affiliate API (v2). client_ip=request_ip lets Chaturbate apply its
    // regional rules for the device that makes the call, as the API requires.
    private const val API = "https://chaturbate.com/api/public/affiliates/onlinerooms/"

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

    /** What we store in the search-response URL so load()/loadLinks() can recognise our items. */
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
    )

    fun handles(data: String): Boolean =
        data.contains("\"kind\":\"$MARKER\"") || Regex("^https://([a-z]+\\.)?chaturbate\\.com/").containsMatchIn(data)

    private fun parseItem(data: String): LiveItem? {
        if (data.startsWith("https://")) {
            // Bookmarked/reopened room: we only know the official room link.
            val user = Regex("[?&]room=([A-Za-z0-9_]+)").find(data)?.groupValues?.get(1)
                ?: data.substringAfter("chaturbate.com/").trim('/').substringBefore('/').substringBefore('?')
            return LiveItem(username = user, title = user, roomUrl = data).takeIf { user.isNotBlank() }
        }
        return runCatching { mapper.readValue<LiveItem>(data) }.getOrNull()?.takeIf { it.kind == MARKER && it.username.isNotBlank() }
    }

    /** Official affiliate room link (falls back to building the documented affiliate link). */
    private fun roomLink(room: Room): String =
        room.chatRoomUrlRevshare?.takeIf { it.startsWith("http") }
            ?: room.chatRoomUrl?.takeIf { it.startsWith("http") }
            ?: "https://chaturbate.com/${room.username}/"

    /** Pulls the src="..." out of the official iframe embed HTML the API returns. */
    private fun embedLink(room: Room): String? {
        val html = room.iframeEmbedRevshare ?: room.iframeEmbed ?: return null
        return Regex("src=['\"]([^'\"]+)['\"]").find(html)?.groupValues?.get(1)
            ?.replace("&amp;", "&")?.let { if (it.startsWith("//")) "https:$it" else it }
    }

    private suspend fun fetchRooms(page: Int): List<Room> {
        if (!isConfigured) return emptyList()
        val offset = (page - 1).coerceAtLeast(0) * PAGE_SIZE
        val url = "$API?format=json&wm=$CAMPAIGN_CODE&client_ip=request_ip&gender=m&limit=$PAGE_SIZE&offset=$offset"
        val body = runCatching { app.get(url, timeout = 20).text }.getOrNull() ?: return emptyList()
        val root: JsonNode = runCatching { mapper.readTree(body) }.getOrNull() ?: return emptyList()
        // v2 returns {"results":[...],"count":N}; the legacy v1 feed returns a bare array.
        val array = when {
            root.isArray -> root
            root.has("results") -> root.get("results")
            else -> return emptyList()
        }
        return array.mapNotNull { runCatching { mapper.treeToValue(it, Room::class.java) }.getOrNull() }
            .filter { it.username.isNotBlank() && (it.gender == null || it.gender == "m") }
            .filter { it.currentShow == null || it.currentShow == "public" }
    }

    suspend fun homePage(api: MainAPI, page: Int, rowName: String): HomePageResponse {
        val rooms = fetchRooms(page)
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
            )
            api.newLiveSearchResponse("🔴 ${item.title}", mapper.writeValueAsString(item), TvType.Live) {
                posterUrl = item.poster
            }
        }
        return newHomePageResponse(
            HomePageList(rowName, items, true),
            hasNext = rooms.size >= PAGE_SIZE,
        )
    }

    suspend fun load(api: MainAPI, data: String): LoadResponse? {
        val item = parseItem(data) ?: return null
        val plot = buildString {
            item.subject?.takeIf { it.isNotBlank() }?.let { appendLine(it); appendLine() }
            item.viewers?.let { appendLine("👀 $it watching now") }
            appendLine("Open room (official link): ${item.roomUrl}")
            item.embedUrl?.let { appendLine("Official embed player: $it") }
            appendLine("Live rooms open in the browser/Chaturbate app, not CloudStream's player.")
            if (!isConfigured) appendLine("⚠️ Set CAMPAIGN_CODE in ChaturbateMaleLive.kt.")
        }
        return api.newLiveStreamLoadResponse(item.title, item.roomUrl, data) {
            posterUrl = item.poster
            this.plot = plot
            tags = listOf("Live", "Chaturbate") + item.tags.take(8)
        }
    }

    /**
     * Intentionally offers NO playable link: the official affiliate API returns only
     * room pages and iframe embeds (web pages), not raw media streams, and this
     * extension does not scrape Chaturbate's private stream endpoints. Rooms are opened
     * through the official room link instead (see load(): the result's URL is the
     * affiliate room link, so CloudStream's "Open in browser"/share opens it).
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun loadLinks(data: String, callback: (ExtractorLink) -> Unit): Boolean = false
}
