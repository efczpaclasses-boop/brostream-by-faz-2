package com.brostream.sources

import com.brostream.ItemData
import org.jsoup.nodes.Document

class ManPornSource : HtmlVideoSource() {
    companion object {
        const val BASE_URL = "https://manporn.xxx"
    }

    override val id = "MANPORN"
    override val prefix = "MP"
    override val label = "ManPorn"
    override val shortLabel = "MP"
    override val baseUrl = BASE_URL
    override val reliability = 80
    override val speed = 70

    override fun searchPath(query: String) = "/search/?q=${Web.encode(query)}"

    override fun pageUrl(page: Int, path: String): String = when {
        page <= 1 -> "$BASE_URL$path"
        path.contains("?q=") -> "$BASE_URL/search/$page/?q=${path.substringAfter("?q=")}"
        else -> "$BASE_URL${path.trimEnd('/')}/$page/"
    }

    override fun parseCards(doc: Document): List<ItemData> = doc.select("div.thumb").mapNotNull { el ->
        val a = el.selectFirst("a[href*=/videos/]") ?: return@mapNotNull null
        val image = el.selectFirst("img")
        val title = image?.attr("alt").orEmpty().ifBlank { a.attr("title").ifBlank { a.text().trim() } }
        val href = Web.absolute(a.attr("href"), BASE_URL) ?: return@mapNotNull null
        if (title.isBlank()) return@mapNotNull null
        withCardMeta(
            ItemData(source = id, url = href, title = title, poster = Web.absolute(poster(image?.attr("data-src"), image?.attr("src")), BASE_URL)),
            el,
        )
    }
}
