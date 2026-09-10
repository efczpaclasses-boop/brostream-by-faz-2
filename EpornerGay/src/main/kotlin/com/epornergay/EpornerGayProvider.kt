package com.epornergay

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.utils.*
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class EpornerGayProvider : MainAPI() {
    override var mainUrl = MP_BASE
    override var name = "BroStream by Faz 2"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    companion object {
        private const val MP_BASE = "https://manporn.xxx"
        private const val GV_BASE = "https://www.gayvids.tv"
        private const val GPT_BASE = "https://www.gayporntube.com"
    }

    private val mapper = jacksonObjectMapper()
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0 Safari/537.36"
    private val headers = mapOf("User-Agent" to userAgent, "Accept" to "text/html,application/xhtml+xml")
    private val owners = ConcurrentHashMap<String, String>()
    private enum class Source { MANPORN, GAYVIDS, GAYPORNTUBE, CURATED }

    data class ItemData(
        val source: String = "",
        val url: String = "",
        val title: String = "",
        val poster: String? = null,
    )

    override val mainPage = mainPageOf(
        "MP|/" to "🔥 Fresh Gay Men",
        "GV|/" to "🆕 New Gay Videos",
        "GV|/categories/amateur/" to "🏠 Top Amateur Men",
        "MP|/search/?q=amateur+gay+blowjob" to "👄 Amateur Blowjobs",
        "GPT|/search/videos/straight-guy-blowjob/page1.html" to "🔥 Straight Guys Getting Serviced",
        "GV|/search/straight-friends-gay/" to "🤝 Straight Friends Playing Around",
        "MP|/search/?q=gay+first+time" to "🫣 First-Time Encounters",
        "GV|/search/gay-onlyfans/" to "📱 OnlyFans & Creator Men",
        "CURATED" to "⭐ MyVidster Gay Picks — 3 Profiles",
        "GV|/categories/homemade/" to "🎥 Homemade & Non-Studio",
        "MP|/categories/latino/" to "🌶️ Latino Men",
        "GV|/categories/brazilian/" to "🇧🇷 Brazilian Men",
        "MP|/categories/blowjob/" to "👄 Hottest Blowjobs",
        "GV|/categories/compilation/" to "💦 Gay Compilations",
        "MP|/categories/compilation/" to "💦 Cum & Blowjob Compilations",
        "GV|/categories/party/" to "🎉 Party & Group Play",
        "GPT|/search/videos/pnp-slam/page1.html" to "🔥 PNP & Slam",
        "MP|/categories/muscle/" to "💪 Muscle Men",
        "GV|/search/gay-jock/" to "🔥 Jocks",
        "MP|/search/?q=straight+curious+guys" to "Straight & Curious Guys",
        "GV|/categories/big-cock/" to "Big Dick",
        "MP|/categories/bareback/" to "Bareback",
        "GV|/categories/group-sex/" to "Group & Orgies",
        "MP|/categories/solo/" to "Solo Men",
        "GV|/categories/outdoor/" to "Outdoor & Public",
        "MP|/categories/cumshot/" to "Cumshots",
        "GV|/categories/gloryhole/" to "Gloryholes",
        "MP|/categories/handjob/" to "Handjobs",
        "GV|/categories/interracial/" to "Interracial Men",
        "MP|/categories/asian/" to "Asian Men",
        "GV|/categories/first-time/" to "First Time",
        "MP|/categories/webcam/" to "Webcam & Creator-Made",
    )

    private val queries = mapOf(
        "MP|/" to "gay men", "GV|/" to "new gay men", "GV|/categories/amateur/" to "amateur men",
        "MP|/search/?q=amateur+gay+blowjob" to "amateur gay blowjob",
        "GPT|/search/videos/straight-guy-blowjob/page1.html" to "straight guy gay blowjob",
        "GV|/search/straight-friends-gay/" to "straight friends gay experimenting",
        "MP|/search/?q=gay+first+time" to "gay first time",
        "GV|/search/gay-onlyfans/" to "gay onlyfans creator men",
        "GV|/categories/homemade/" to "homemade gay", "MP|/categories/latino/" to "latino men",
        "GV|/categories/brazilian/" to "brazilian men", "MP|/categories/blowjob/" to "gay blowjob",
        "GV|/categories/compilation/" to "gay compilation", "MP|/categories/compilation/" to "cum compilation",
        "GV|/categories/party/" to "gay party", "GPT|/search/videos/pnp-slam/page1.html" to "pnp slam",
        "MP|/categories/muscle/" to "muscle men", "GV|/search/gay-jock/" to "gay jock",
        "MP|/search/?q=straight+curious+guys" to "straight curious guys", "GV|/categories/big-cock/" to "big cock men",
        "MP|/categories/bareback/" to "gay bareback", "GV|/categories/group-sex/" to "gay group",
        "MP|/categories/solo/" to "solo male", "GV|/categories/outdoor/" to "gay outdoor",
        "MP|/categories/cumshot/" to "gay cumshot", "GV|/categories/gloryhole/" to "gay gloryhole",
        "MP|/categories/handjob/" to "gay handjob", "GV|/categories/interracial/" to "gay interracial",
        "MP|/categories/asian/" to "asian gay men", "GV|/categories/first-time/" to "gay first time",
        "MP|/categories/webcam/" to "gay webcam",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val primary = when {
            request.data == "CURATED" -> curated.drop((page - 1) * 20).take(20)
            request.data.startsWith("MP|") -> manPorn(page, request.data.removePrefix("MP|"))
            request.data.startsWith("GV|") -> gayVids(page, request.data.removePrefix("GV|"))
            request.data.startsWith("GPT|") -> gayPornTube(page, request.data.removePrefix("GPT|"))
            else -> emptyList()
        }
        val claimedPrimary = primary.filter(::isMenOnly).filter { matchesRow(it, request.data) }
            .filter { claim(it, request.data) }.distinctBy(::canonicalKey)
        val backup = if (request.data != "CURATED" && claimedPrimary.size < 12)
            fallback(page, queries[request.data].orEmpty(), primary.firstOrNull()?.source)
                .filter(::isMenOnly).filter { matchesRow(it, request.data) }.filter { claim(it, request.data) }
        else emptyList()
        val items = (claimedPrimary + backup).distinctBy(::canonicalKey).take(60)
        return newHomePageResponse(
            HomePageList(request.name, items.map { it.response() }, request.data != "CURATED"),
            hasNext = request.data != "CURATED" && items.isNotEmpty(),
        )
    }

    private suspend fun fallback(page: Int, query: String, failed: String?): List<ItemData> {
        if (query.isBlank()) return emptyList()
        return listOf(Source.MANPORN, Source.GAYVIDS, Source.GAYPORNTUBE).filter { it.name != failed }.amap { source ->
            runCatching {
                when (source) {
                    Source.MANPORN -> manPorn(page, "/search/?q=${encode(query)}")
                    Source.GAYVIDS -> gayVids(page, "/search/${slug(query)}/")
                    Source.GAYPORNTUBE -> gayPornTube(page, "/search/videos/${slug(query)}/page1.html")
                    Source.CURATED -> emptyList()
                }
            }.getOrDefault(emptyList())
        }.flatten()
    }

    private suspend fun manPorn(page: Int, path: String): List<ItemData> {
        val url = when {
            page <= 1 -> "$MP_BASE$path"
            path.contains("?q=") -> "$MP_BASE/search/$page/?q=${path.substringAfter("?q=")}"
            else -> "$MP_BASE${path.trimEnd('/')}/$page/"
        }
        val doc = runCatching { app.get(url, headers = headers, timeout = 25).document }.getOrNull() ?: return emptyList()
        return doc.select("div.thumb").mapNotNull { el ->
            val a = el.selectFirst("a[href*=/videos/]") ?: return@mapNotNull null
            val image = el.selectFirst("img")
            val title = image?.attr("alt").orEmpty().ifBlank { a.attr("title").ifBlank { a.text().trim() } }
            val href = absolute(a.attr("href"), MP_BASE) ?: return@mapNotNull null
            val poster = image?.attr("data-src").orEmpty().ifBlank { image?.attr("src").orEmpty() }
            if (title.isBlank()) null else ItemData(Source.MANPORN.name, href, title, absolute(poster, MP_BASE))
        }
    }

    private suspend fun gayVids(page: Int, path: String): List<ItemData> {
        val url = if (page <= 1) "$GV_BASE$path" else "$GV_BASE${path.trimEnd('/')}/$page/"
        val doc = runCatching { app.get(url, headers = headers, timeout = 25).document }.getOrNull() ?: return emptyList()
        return doc.select(".list-videos .item").mapNotNull { el ->
            val a = el.selectFirst("a[href*=/videos/][title]") ?: return@mapNotNull null
            val href = absolute(a.attr("href"), GV_BASE) ?: return@mapNotNull null
            val title = a.attr("title").ifBlank { el.selectFirst(".title")?.text().orEmpty() }
            val image = el.selectFirst("img")
            val poster = image?.attr("data-original").orEmpty().ifBlank { image?.attr("src").orEmpty() }
            if (title.isBlank()) null else ItemData(Source.GAYVIDS.name, href, title, absolute(poster, GV_BASE))
        }
    }

    private suspend fun gayPornTube(page: Int, path: String): List<ItemData> {
        val paged = if (path.contains("page1.html")) path.replace("page1.html", "page$page.html")
            else if (page <= 1) path else path.trimEnd('/') + "/page$page.html"
        val doc = runCatching { app.get("$GPT_BASE$paged", headers = headers, timeout = 25).document }.getOrNull() ?: return emptyList()
        return doc.select("div.item.item-col[data-video-id]").mapNotNull { el ->
            val a = el.selectFirst("a.image[href][title]") ?: return@mapNotNull null
            val href = absolute(a.attr("href"), GPT_BASE) ?: return@mapNotNull null
            val title = a.attr("title").ifBlank { a.text().trim() }
            val image = el.selectFirst("img[data-src], img[src]")
            val poster = image?.attr("data-src").orEmpty().ifBlank { image?.attr("src").orEmpty() }
            if (title.isBlank()) null else ItemData(Source.GAYPORNTUBE.name, href, title, absolute(poster, GPT_BASE))
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return listOf(
            runCatching { manPorn(1, "/search/?q=${encode(query)}") }.getOrDefault(emptyList()),
            runCatching { gayVids(1, "/search/${slug(query)}/") }.getOrDefault(emptyList()),
            runCatching { gayPornTube(1, "/search/videos/${slug(query)}/page1.html") }.getOrDefault(emptyList()),
        ).flatten().filter(::isMenOnly).distinctBy(::canonicalKey).take(80).map { it.response() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val item = parse(url) ?: return null
        if (item.source == Source.CURATED.name) return simpleLoad(item, url)
        val doc = runCatching { app.get(item.url, headers = headers, timeout = 25).document }.getOrNull()
            ?: return simpleLoad(item, url)
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - ")?.trim()
            .orEmpty().ifBlank { item.title }
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty().ifBlank { item.poster.orEmpty() }
        val tags = doc.select("a[href*=categories], a[href*=category], .tags a").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            posterUrl = poster
            plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            this.tags = tags
        }
    }

    private suspend fun simpleLoad(item: ItemData, data: String): LoadResponse =
        newMovieLoadResponse(item.title, data, TvType.NSFW, data) {
            posterUrl = item.poster
            tags = listOf("Gay Men", if (item.source == Source.CURATED.name) "MyVidster Pick" else item.source)
        }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val item = parse(data) ?: return false
        return when (runCatching { Source.valueOf(item.source) }.getOrNull()) {
            Source.MANPORN -> directLinks(item, "ManPorn", callback)
            Source.GAYVIDS -> directLinks(item, "GayVids", callback)
            Source.GAYPORNTUBE -> directLinks(item, "GayPornTube", callback)
            Source.CURATED -> runCatching { loadExtractor(item.url, item.url, subtitleCallback, callback); true }.getOrDefault(false)
            null -> false
        }
    }

    private suspend fun directLinks(item: ItemData, label: String, callback: (ExtractorLink) -> Unit): Boolean {
        val response = app.get(item.url, headers = headers, timeout = 25)
        val declared = response.document.select("video source[src], source[type*=video][src]").mapNotNull { absolute(it.attr("src"), item.url) }
        val embedded = Regex("https?[^\\\"']+?\\.mp4[^\\\"'< ]*", RegexOption.IGNORE_CASE)
            .findAll(response.text).map { it.value.replace("\\/", "/").replace("&amp;", "&") }
            .filterNot { it.endsWith(".mp4.jpg") }.toList()
        val streams = (declared + embedded).distinctBy { it.substringBefore('?') }
        streams.forEach { stream ->
            val q = Regex("_(\\d{3,4})p?\\.mp4", RegexOption.IGNORE_CASE).find(stream)?.groupValues?.get(1) ?: "HD"
            callback(newExtractorLink(label, "$label $q", stream, if (stream.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                referer = item.url
                quality = q.toIntOrNull() ?: Qualities.Unknown.value
                headers = mapOf("User-Agent" to userAgent, "Referer" to item.url)
            })
        }
        return streams.isNotEmpty()
    }

    private fun isMenOnly(item: ItemData): Boolean {
        if (item.url.isBlank() || item.title.isBlank()) return false
        val blocked = Regex("\\b(lesbian|girl|girls|woman|women|female|milf|mom|mommy|mother|wife|wives|daughter|sister|girlfriend|bride|babe|chick|lady|ladies|pussy|vagina|clit|tits|boobs|breasts|pregnant|shemale|trans|tranny|futa|stepmom|stepdaughter|schoolgirl|cougar|granny|doll|mujer|mujeres|chica|chicas|esposa|novia|hermana|madre|menina|mulher|esposa|namorada|irmã|mae)\\b", RegexOption.IGNORE_CASE)
        val mixed = Regex("\\b(straight couple|boy and girl|guy and girl|man and woman|husband and wife)\\b", RegexOption.IGNORE_CASE)
        return !blocked.containsMatchIn(item.title) && !mixed.containsMatchIn(item.title)
    }

    private fun matchesRow(item: ItemData, row: String): Boolean {
        val title = item.title.lowercase()
        val oral = Regex("\\b(blowjob|blow job|suck|sucking|sucked|oral|deepthroat|deep throat|head)\\b").containsMatchIn(title)
        return when (row) {
            "MP|/categories/blowjob/" -> oral
            "MP|/search/?q=amateur+gay+blowjob" -> oral && Regex("\\b(amateur|homemade|home made|real|selfmade)\\b").containsMatchIn(title)
            "GPT|/search/videos/straight-guy-blowjob/page1.html" -> oral && Regex("\\b(straight|curious|bestie|friend|buddy)\\b").containsMatchIn(title)
            "GV|/search/straight-friends-gay/" -> Regex("\\b(straight|curious)\\b").containsMatchIn(title) && Regex("\\b(friend|friends|bestie|buddy|buddies)\\b").containsMatchIn(title)
            "MP|/search/?q=gay+first+time" -> Regex("\\b(first time|first-time|virgin|first experience|first contact|experiment)\\b").containsMatchIn(title)
            "GV|/search/gay-onlyfans/" -> Regex("\\b(onlyfans|only fans|creator|fan site|webcam|subscriber)\\b").containsMatchIn(title)
            else -> true
        }
    }

    private fun claim(item: ItemData, row: String): Boolean {
        val keys = listOf(canonicalKey(item), "title:${normalize(item.title)}")
        if (keys.any { owners[it]?.let { owner -> owner != row } == true }) return false
        keys.forEach { owners.putIfAbsent(it, row) }
        return keys.all { owners[it] == row }
    }

    private fun canonicalKey(item: ItemData): String {
        val groups = Regex("/videos?/(\\d+)|/watch/(\\d+)").find(item.url)?.groupValues
        val id = groups?.drop(1)?.firstOrNull { it.isNotBlank() }
        return if (id != null) "${item.source}:$id" else "${item.source}:${normalize(item.title)}"
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("\\b(4k|2160p|1080p|720p|480p|hd|full video|gay porn|free porn)\\b"), "")
        .replace(Regex("[^a-z0-9]"), "").take(100)

    private fun ItemData.response(): SearchResponse = newMovieSearchResponse("[$sourceLabel] $title", mapper.writeValueAsString(this), TvType.NSFW) {
        posterUrl = poster
        quality = SearchQuality.HD
    }

    private val ItemData.sourceLabel: String get() = when (source) {
        Source.MANPORN.name -> "MP"; Source.GAYVIDS.name -> "GV"; Source.GAYPORNTUBE.name -> "GPT"; Source.CURATED.name -> "MV"; else -> source
    }
    private fun parse(value: String): ItemData? = runCatching { mapper.readValue(value, ItemData::class.java) }.getOrNull()
    private fun slug(value: String) = value.lowercase().trim().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun absolute(value: String?, base: String): String? = if (value.isNullOrBlank()) null else runCatching { URI(base).resolve(value).toString() }.getOrNull()

    private val curated = listOf(
        ItemData(Source.CURATED.name, "https://www.xvideos.com/video.hoeabkmf390/danish_boy_and_gay_pornstar_frederik_known_from_6mag.dk_-_10", "Danish gay performer Frederik", "https://cdn2.myvidster.com/user/thumbs/e9e4cc891049f85139491c23bd14c5b7_1.jpg"),
        ItemData(Source.CURATED.name, "https://xhamster.com/movies/2248475/we_should_hang_at_my_place_sometime..html", "Cade's Anal Awakening", "https://cdn2.myvidster.com/user/images/20July2014/320876/1070396135_1.jpg"),
        ItemData(Source.CURATED.name, "https://www.gayfuror.com/video/three-gorgeous-boys/", "Three Gorgeous Guys", "https://cdn2.myvidster.com/user/images/20August2015/5508/1536070777_1.jpg"),
        ItemData(Source.CURATED.name, "https://www.boyfriendtv.com/videos/40021/extraordinary-group-sex-with-lots-of-cum.html", "Gay Group Session", "https://cdn2.myvidster.com/user/images/29September2014/27849/249233468_1.jpg"),
        ItemData(Source.CURATED.name, "https://thisvid.com/videos/daddy-fucks-his-boy-in-the-car/", "Daddy and Guy in the Car", "https://cdn2.myvidster.com/user/thumbs/cc326e183949aeaa09b9fd3e47a0c9f1_1.jpg"),
        ItemData(Source.CURATED.name, "https://streamtape.com/v/zXADomOxXGIgvj/", "Charlie Roberts and Marcus Ruhl", "https://cdn2.myvidster.com/user/thumbs/51046ea71d9c00a506fb7969197cedbb_1.jpg"),
        ItemData(Source.CURATED.name, "https://vidara.to/v/Fc5O8LET114iE", "Aingeru — Masculine Guy", "https://cdn2.myvidster.com/user/thumbs/de10232e1c4db5d6155235e536f25b65_1.jpg"),
        ItemData(Source.CURATED.name, "https://luluvid.com/jm4pza21axea", "Twink with Two Tall Guys", "https://cdn2.myvidster.com/user/thumbs/112f6ddacde2224ed5a8df6a6415515d_1.jpg"),
        ItemData(Source.CURATED.name, "https://thisvid.com/videos/young-latino-boy-getting-serviced-until-cumming/", "Latino Guy Getting Serviced", "https://cdn2.myvidster.com/user/thumbs/f96fcc90016f57b0feacef68ffb1501d_1.jpg"),
        ItemData(Source.CURATED.name, "https://veev.to/d/6f4arg9x0f6n", "Aingeru — Oral, Solo and Anal", "https://cdn2.myvidster.com/user/thumbs/25c74b04100a307de8cc5efed257b8b3_1.jpg"),
        ItemData(Source.CURATED.name, "https://streamtape.com/v/3pRvAZx9kjFdBdq/", "Cum Play with Two Men", "https://cdn2.myvidster.com/user/thumbs/ebaec728e39abcd5f281d1c693e324e6_1.jpg"),
    )
}
