package com.brostream.sources

import com.brostream.ItemData
import org.jsoup.nodes.Document

class GayPornTubeSource : HtmlVideoSource() {
    companion object {
        const val BASE_URL = "https://www.gayporntube.com"
    }

    override val id = "GAYPORNTUBE"
    override val prefix = "GPT"
    override val label = "GayPornTube"
    override val shortLabel = "GPT"
    override val baseUrl = BASE_URL
    override val reliability = 70
    override val speed = 60

    override fun searchPath(query: String) = "/search/videos/${Web.slug(query)}/page1.html"

    override fun pageUrl(page: Int, path: String): String {
        val paged = when {
            path.contains("page1.html") -> path.replace("page1.html", "page$page.html")
            page <= 1 -> path
            else -> path.trimEnd('/') + "/page$page.html"
        }
        return "$BASE_URL$paged"
    }

    override fun parseCards(doc: Document): List<ItemData> =
        doc.select("div.item.item-col[data-video-id]").mapNotNull { el ->
            val a = el.selectFirst("a.image[href][title]") ?: return@mapNotNull null
            val href = Web.absolute(a.attr("href"), BASE_URL) ?: return@mapNotNull null
            val title = a.attr("title").ifBlank { a.text().trim() }
            if (title.isBlank()) return@mapNotNull null
            val image = el.selectFirst("img[data-src], img[src]")
            withCardMeta(
                ItemData(source = id, url = href, title = title, poster = Web.absolute(poster(image?.attr("data-src"), image?.attr("src")), BASE_URL)),
                el,
            )
        }
}
