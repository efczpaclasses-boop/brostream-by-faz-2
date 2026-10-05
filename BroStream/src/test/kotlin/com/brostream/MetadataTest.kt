package com.brostream

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.Assert.*
import org.junit.Test

class MetadataTest {
    private val now = 1_800_000_000_000L

    @Test fun `ISO dates honour their offset`() {
        assertEquals(1_759_665_600_000L, Metadata.time("2025-10-05T12:00:00Z"))
        assertEquals(Metadata.time("2025-10-05T12:00:00Z"), Metadata.time("2025-10-05T16:00:00+04:00"))
        assertEquals(Metadata.time("2025-10-05T00:00:00Z"), Metadata.time("2025-10-05"))
        assertEquals(0L, Metadata.time("2025-13-45"))
        assertEquals(0L, Metadata.time("not a date"))
        assertEquals(0L, Metadata.time(null))
    }

    @Test fun `relative times count back from now`() {
        assertEquals(now - 3 * 3_600_000L, Metadata.time("3 hours ago", now))
        assertEquals(now - 86_400_000L, Metadata.time("Yesterday", now))
        assertEquals(now - 2 * 604_800_000L, Metadata.time("2 weeks ago", now))
    }

    @Test fun `view counts understand suffixes and separators`() {
        assertEquals(12_345L, Metadata.count("12,345 views"))
        assertEquals(1_200_000L, Metadata.count("1.2M views"))
        assertEquals(15_000L, Metadata.count("15K"))
        assertEquals(0L, Metadata.count("no digits"))
    }

    @Test fun `ratings normalise to 0 to 100`() {
        assertEquals(92, Metadata.rating("92%"))
        assertEquals(92, Metadata.rating("4.6"))
        assertEquals(0, Metadata.rating(""))
    }

    @Test fun `durations parse minutes and hours`() {
        assertEquals(754, Metadata.duration("12:34"))
        assertEquals(3723, Metadata.duration("1:02:03"))
        assertEquals(0, Metadata.duration("soon"))
    }

    @Test fun `structured data supplies tags performers genders and stream`() {
        val json = """
            {"@context":"https://schema.org","@graph":[{"@type":"VideoObject","name":"Clip","description":"d",
            "uploadDate":"2025-10-05T12:00:00Z","contentUrl":"https://cdn.example/v_720.mp4",
            "keywords":"gay, men","actor":[{"name":"A","gender":"male"},{"name":"B","gender":"female"}],
            "interactionStatistic":{"userInteractionCount":"4,200"},"aggregateRating":{"ratingValue":"4"}}]}
        """
        val data = Metadata.structured(listOf(json), jacksonObjectMapper(), now)
        assertEquals(listOf("gay", "men"), data.tags)
        assertEquals(listOf("male", "female"), data.genders)
        assertEquals(listOf("A", "B"), data.performers)
        assertEquals(4200L, data.views)
        assertEquals(80, data.rating)
        assertEquals(1_759_665_600_000L, data.uploadedAt)
        assertEquals(listOf("https://cdn.example/v_720.mp4"), data.contentUrls)
        assertEquals(Metadata.Structured(), Metadata.structured(listOf("garbage"), jacksonObjectMapper()))
    }
}
