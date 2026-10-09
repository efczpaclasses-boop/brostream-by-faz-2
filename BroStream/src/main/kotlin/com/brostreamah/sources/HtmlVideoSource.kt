package com.brostreamah.sources

import com.brostreamah.ItemData
import com.brostreamah.Metadata
import com.brostreamah.SourceHealth
import com.brostreamah.StreamCandidate
import com.brostreamah.StreamExtractor
import com.brostreamah.VideoDetails
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Shared scraping flow for sites that serve plain HTML listing and watch pages. */
abstract class HtmlVideoSource : VideoSource {
    protected open val healthPath: String = "/"
    private val mapper = jacksonObjectMapper()

    protected abstract fun pageUrl(page: Int, path: String): String
    protected abstract fun parseCards(doc: Document): List<ItemData>

    override suspend fun catalogue(page: Int, path: String): List<ItemData> {
        val doc = Web.document(pageUrl(page, path)) ?: return emptyList()
        return parseCards(doc)
    }

    /** Duration, views, rating, upload time and tags that a listing card shows, when it shows them. */
    protected fun withCardMeta(item: ItemData, card: Element): ItemData = item.copy(
        durationSec = Metadata.duration(card.selectFirst(".duration, .time, .length, [class*=duration]")?.text()),
        views = Metadata.count(card.selectFirst(".views, .view-count, [class*=views]")?.text()),
        rating = Metadata.rating(card.selectFirst(".rating, .percent, [class*=rating]")?.text()),
        uploadedAt = Metadata.time(
            card.selectFirst("time[datetime]")?.attr("datetime")
                ?: card.selectFirst(".added, .date, .age, [class*=added], [class*=date]")?.text(),
        ),
        tags = (card.attr("data-tags").split(',', ' ') + card.select(".tags a, .categories a").map { it.text() })
            .map(String::trim).filter(String::isNotEmpty).distinct(),
    )

    private fun structuredBlocks(doc: Document): List<String> =
        doc.select("script[type=application/ld+json]").map { it.data() }.filter(String::isNotBlank)

    override suspend fun details(item: ItemData): VideoDetails? {
        val doc = Web.document(item.url) ?: return null
        val structured = Metadata.structured(structuredBlocks(doc), mapper)
        val tags = (structured.tags +
            doc.select("a[href*=categories], a[href*=category], a[href*=/tag/], a[href*=/tags/], .tags a, .categories a").map { it.text() } +
            doc.select("meta[property=video:tag]").map { it.attr("content") } +
            doc.selectFirst("meta[name=keywords]")?.attr("content").orEmpty().split(','))
            .map(String::trim).filter(String::isNotEmpty).distinct()
        val performers = (structured.performers +
            doc.select("a[href*=/pornstar], a[href*=/model], a[href*=/performer], a[href*=/stars/], [itemprop=actor] [itemprop=name]").map { it.text() } +
            doc.select("meta[property=video:actor]").map { it.attr("content") })
            .map(String::trim).filter(String::isNotEmpty).distinct()
        val genders = (structured.genders +
            doc.select("[itemprop=gender]").map { it.attr("content").ifBlank { it.text() } } +
            doc.select("meta[property=video:actor:gender], meta[property=profile:gender]").map { it.attr("content") })
            .map(String::trim).filter(String::isNotEmpty)
        val uploaded = structured.uploadedAt.takeIf { it > 0 } ?: Metadata.time(
            doc.selectFirst("meta[property=video:release_date], meta[property=article:published_time], meta[itemprop=uploadDate]")?.attr("content")
                ?: doc.selectFirst("time[datetime]")?.attr("datetime"),
        )
        return VideoDetails(
            title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - ")?.trim()
                .orEmpty().ifBlank { structured.title.ifBlank { item.title } },
            poster = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty().ifBlank { item.poster.orEmpty() },
            plot = doc.selectFirst("meta[property=og:description]")?.attr("content").orEmpty().ifBlank { structured.description }.ifBlank { null },
            tags = tags, performers = performers, genders = genders, uploadedAt = uploaded,
            views = structured.views.takeIf { it > 0 } ?: Metadata.count(doc.selectFirst(".views, .view-count, [itemprop=interactionCount]")?.text()),
            rating = structured.rating,
        )
    }

    override suspend fun playback(item: ItemData): List<StreamCandidate> {
        val response = app.get(item.url, headers = Web.headers, timeout = 25)
        if (response.code !in 200..299) return emptyList()
        val doc = response.document
        val structured = Metadata.structured(structuredBlocks(doc), mapper).contentUrls
        return StreamExtractor.extract(doc, response.text, item.url, structured)
    }

    override suspend fun health(): SourceHealth {
        val count = catalogue(1, healthPath).size
        return SourceHealth(id, count > 0, count)
    }

    protected fun poster(imageSrc: String?, fallback: String?): String? =
        imageSrc.orEmpty().ifBlank { fallback.orEmpty() }.ifBlank { null }
}
