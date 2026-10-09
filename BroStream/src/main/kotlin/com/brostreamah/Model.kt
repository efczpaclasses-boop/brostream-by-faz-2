package com.brostreamah

/**
 * One catalogue entry. Only the lean fields travel inside the CloudStream URL (see [lean]); the rest is
 * evidence gathered from listing cards and detail pages for classification, ranking and deduplication.
 * Every field needs a default so older serialised URLs keep loading.
 */
data class ItemData(
    val source: String = "",
    val url: String = "",
    val title: String = "",
    val poster: String? = null,
    val durationSec: Int = 0,
    val performers: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val genders: List<String> = emptyList(),
    val description: String = "",
    val views: Long = 0,
    /** 0..100, or 0 when the source shows no rating. */
    val rating: Int = 0,
    /** Epoch milliseconds, or 0 when the source shows no upload time. */
    val uploadedAt: Long = 0,
    /** True once the detail page has been read and merged. */
    val detailed: Boolean = false,
) {
    fun lean() = ItemData(source, url, title, poster, durationSec)
}

data class VideoDetails(
    val title: String,
    val poster: String?,
    val plot: String?,
    val tags: List<String>,
    val performers: List<String> = emptyList(),
    val genders: List<String> = emptyList(),
    val uploadedAt: Long = 0,
    val views: Long = 0,
    val rating: Int = 0,
)

/** [quality] is the vertical resolution, or 0 when the page does not say. */
data class StreamCandidate(val url: String, val quality: Int, val isHls: Boolean)

data class SourceHealth(val source: String, val ok: Boolean, val items: Int)

/** Merges what a detail page revealed into a catalogue entry. */
internal fun ItemData.withDetails(details: VideoDetails): ItemData = copy(
    tags = (tags + details.tags).distinct(),
    performers = (performers + details.performers).distinct(),
    genders = (genders + details.genders).distinct(),
    description = details.plot.orEmpty().ifBlank { description },
    views = maxOf(views, details.views),
    rating = if (details.rating > 0) details.rating else rating,
    uploadedAt = if (details.uploadedAt > 0) details.uploadedAt else uploadedAt,
    poster = poster ?: details.poster?.ifBlank { null },
    detailed = true,
)
