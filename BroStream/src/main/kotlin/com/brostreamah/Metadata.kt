package com.brostreamah

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Parsing of the dates, counts and structured data that sites expose in cards and detail pages. */
internal object Metadata {
    private val absolute = Regex(
        "(\\d{4})-(\\d{2})-(\\d{2})(?:[T ](\\d{2}):(\\d{2})(?::(\\d{2}))?)?(?:\\.\\d+)?\\s*(Z|[+-]\\d{2}:?\\d{2})?",
    )
    private val relative = Regex(
        "(\\d+)\\s*(second|sec|minute|min|hour|hr|day|week|month|year)s?\\s+ago", RegexOption.IGNORE_CASE,
    )
    private val unitMillis = mapOf(
        "second" to 1_000L, "sec" to 1_000L, "minute" to 60_000L, "min" to 60_000L,
        "hour" to 3_600_000L, "hr" to 3_600_000L, "day" to 86_400_000L, "week" to 604_800_000L,
        "month" to 2_592_000_000L, "year" to 31_536_000_000L,
    )

    /** ISO dates ("2026-10-04T12:00:00+04:00", "2026-10-04") and "3 hours ago"; 0 when unparsable. */
    fun time(text: String?, now: Long = System.currentTimeMillis()): Long {
        val value = text?.trim().orEmpty()
        if (value.isEmpty()) return 0
        absolute.find(value)?.let { m ->
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT)
            calendar.clear()
            val (y, mo, d) = listOf(1, 2, 3).map { m.groupValues[it].toInt() }
            if (mo !in 1..12 || d !in 1..31) return 0
            calendar.set(y, mo - 1, d, m.groupValues[4].toIntOrNull() ?: 0, m.groupValues[5].toIntOrNull() ?: 0,
                m.groupValues[6].toIntOrNull() ?: 0)
            val zone = m.groupValues[7]
            val offset = if (zone.isEmpty() || zone == "Z") 0L else {
                val digits = zone.drop(1).replace(":", "")
                val sign = if (zone[0] == '-') -1 else 1
                sign * (digits.take(2).toLong() * 3_600_000L + digits.drop(2).toLong() * 60_000L)
            }
            return calendar.timeInMillis - offset
        }
        relative.find(value)?.let { m ->
            val unit = unitMillis[m.groupValues[2].lowercase(Locale.ROOT)] ?: return 0
            return now - m.groupValues[1].toLong() * unit
        }
        return when (value.lowercase(Locale.ROOT)) {
            "today" -> now
            "yesterday" -> now - 86_400_000L
            else -> 0
        }
    }

    /** "12,345", "1.2M views", "15K" to a count; 0 when absent. */
    fun count(text: String?): Long {
        val match = Regex("([\\d][\\d.,]*)\\s*([kKmMbB])?").find(text.orEmpty()) ?: return 0
        val number = match.groupValues[1]
        val suffix = match.groupValues[2].lowercase(Locale.ROOT)
        val scale = when (suffix) { "k" -> 1_000.0; "m" -> 1_000_000.0; "b" -> 1_000_000_000.0; else -> 1.0 }
        val parsed = if (suffix.isEmpty()) number.replace(",", "").replace(".", "").toDoubleOrNull()
        else number.replace(",", "").toDoubleOrNull()
        return ((parsed ?: return 0) * scale).toLong()
    }

    /** "92%" or "4.6" (out of 5) to 0..100. */
    fun rating(text: String?): Int {
        val value = text.orEmpty()
        val number = Regex("(\\d+(?:\\.\\d+)?)").find(value)?.groupValues?.get(1)?.toDoubleOrNull() ?: return 0
        return when {
            value.contains('%') -> number.toInt().coerceIn(0, 100)
            number <= 5.0 -> (number * 20).toInt()
            else -> number.toInt().coerceIn(0, 100)
        }
    }

    /** "MM:SS" or "H:MM:SS" to seconds; 0 when absent or unparsable. */
    fun duration(text: String?): Int {
        // "13 min", "45 sec", "1 h 5 min" as shown on some cards
        val words = Regex("(\\d+)\\s*(h|hr|hour|min|m|sec|s)s?\\b", RegexOption.IGNORE_CASE).findAll(text.orEmpty()).toList()
        if (words.isNotEmpty() && text?.contains(':') != true) {
            return words.sumOf { m ->
                val n = m.groupValues[1].toInt()
                when (m.groupValues[2].lowercase(Locale.ROOT)) { "h", "hr", "hour" -> n * 3600; "min", "m" -> n * 60; else -> n }
            }
        }
        val parts = text?.trim()?.split(':')?.map { it.trim().toIntOrNull() ?: return 0 } ?: return 0
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0
        }
    }

    /** What a VideoObject JSON-LD block says about a video. */
    data class Structured(
        val title: String = "", val description: String = "", val uploadedAt: Long = 0, val views: Long = 0,
        val rating: Int = 0, val tags: List<String> = emptyList(), val performers: List<String> = emptyList(),
        val genders: List<String> = emptyList(), val contentUrls: List<String> = emptyList(),
    )

    fun structured(blocks: List<String>, mapper: ObjectMapper, now: Long = System.currentTimeMillis()): Structured {
        val nodes = blocks.mapNotNull { attempt { mapper.readTree(it) } }.flatMap(::flatten)
        val video = nodes.firstOrNull { node ->
            val type = node.get("@type")
            type != null && (type.asText() == "VideoObject" || type.isArray && type.any { it.asText() == "VideoObject" })
        } ?: return Structured()
        val actors = video.get("actor")?.let { if (it.isArray) it.toList() else listOf(it) }.orEmpty()
        val keywords = video.get("keywords")?.let { if (it.isArray) it.map(JsonNode::asText) else it.asText().split(',') }
            .orEmpty().map(String::trim).filter(String::isNotEmpty)
        val stats = video.get("interactionStatistic")?.let { if (it.isArray) it.toList() else listOf(it) }.orEmpty()
        val views = stats.firstOrNull { it.path("interactionType").toString().contains("Watch") || it.has("userInteractionCount") }
            ?.path("userInteractionCount")?.asText()
        return Structured(
            title = video.path("name").asText(""),
            description = video.path("description").asText(""),
            uploadedAt = time(video.path("uploadDate").asText(""), now),
            views = count(views ?: video.path("interactionCount").asText("").ifBlank { null }),
            rating = rating(video.path("aggregateRating").path("ratingValue").asText("")),
            tags = keywords,
            performers = actors.map { it.path("name").asText("") }.filter(String::isNotEmpty),
            genders = actors.map { it.path("gender").asText("") }.filter(String::isNotEmpty),
            contentUrls = listOf("contentUrl", "embedUrl").mapNotNull { video.path(it).asText("").takeIf(String::isNotBlank) },
        )
    }

    private fun flatten(node: JsonNode): List<JsonNode> = when {
        node.isArray -> node.flatMap(::flatten)
        node.has("@graph") -> flatten(node.get("@graph"))
        else -> listOf(node)
    }
}
