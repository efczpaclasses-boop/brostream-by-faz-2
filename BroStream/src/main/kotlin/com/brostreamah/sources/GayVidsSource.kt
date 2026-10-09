package com.brostreamah.sources

import com.brostreamah.ItemData
import org.jsoup.nodes.Document

class GayVidsSource : HtmlVideoSource() {
    companion object {
        const val BASE_URL = "https://www.gayvids.tv"
    }

    override val id = "GAYVIDS"
    override val prefix = "GV"
    override val label = "GayVids"
    override val shortLabel = "GV"
    override val baseUrl = BASE_URL
    override val reliability = 75
    override val speed = 65

    override fun searchPath(query: String) = "/search/${Web.slug(query)}/"

    override fun pageUrl(page: Int, path: String): String =
        if (page <= 1) "$BASE_URL$path" else "$BASE_URL${path.trimEnd('/')}/$page/"

    override fun parseCards(doc: Document): List<ItemData> = doc.select(".list-videos .item").mapNotNull { el ->
        val a = el.selectFirst("a[href*=/videos/][title]") ?: return@mapNotNull null
        val href = Web.absolute(a.attr("href"), BASE_URL) ?: return@mapNotNull null
        val title = a.attr("title").ifBlank { el.selectFirst(".title")?.text().orEmpty() }
        if (title.isBlank()) return@mapNotNull null
        val image = el.selectFirst("img")
        withCardMeta(
            ItemData(source = id, url = href, title = title, poster = Web.absolute(poster(image?.attr("data-original"), image?.attr("src")), BASE_URL)),
            el,
        )
    }
}
