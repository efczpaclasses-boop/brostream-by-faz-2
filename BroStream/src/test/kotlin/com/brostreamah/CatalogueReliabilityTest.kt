package com.brostreamah

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

class CatalogueReliabilityTest {
    private val profiles = mapOf(
        "A" to SourceProfile(reliability = 90, speed = 50),
        "B" to SourceProfile(reliability = 60, speed = 90),
    )
    private fun dedup() = Deduplicator { profiles[it] }
    private fun item(
        title: String, id: String = title.hashCode().toString(), source: String = "A",
        duration: Int = 0, poster: String? = null, performers: List<String> = emptyList(),
    ) = ItemData(source, "https://$source.example/videos/$id/slug/", title, poster, duration, performers)

    @Test fun `canonical id uses the numeric ID within a provider`() {
        assertEquals("A:42", canonicalId(item("x", id = "42")))
        assertEquals(canonicalId(item("one", id = "42")), canonicalId(item("renamed", id = "42")))
        assertNotEquals(canonicalId(item("same", id = "1", source = "A")), canonicalId(item("same", id = "1", source = "B")))
    }

    @Test fun `same canonical ID is collapsed even when the title changes`() {
        val result = dedup().collapse(listOf(item("Mountain walk", id = "1"), item("Totally different words", id = "1")))
        assertEquals(1, result.size)
    }

    @Test fun `studio prefixes resolution labels and punctuation are ignored`() {
        assertEquals(Titles.normalize("City Tour"), Titles.normalize("[Studio X] City Tour 1080p"))
        assertEquals(Titles.normalize("City Tour"), Titles.normalize("StudioX - City-Tour (4K)!"))
        assertEquals("a b", Titles.normalize("A: b"))   // too short to treat "A" as a studio
    }

    @Test fun `same video on different providers is detected`() {
        val a = item("Mountain Walk", id = "1", source = "A")
        val b = item("[Studio] Mountain Walk 720p", id = "9", source = "B")
        assertEquals(1, dedup().collapse(listOf(a, b)).size)
    }

    @Test fun `slightly renamed copies match by similarity`() {
        val a = item("Two guys hiking up the mountain trail together", id = "1", duration = 600)
        val b = item("Two guys hiking up the mountain trail", id = "2", source = "B", duration = 601)
        assertEquals(1, dedup().collapse(listOf(a, b)).size)
    }

    @Test fun `similar titles with different durations stay separate`() {
        val a = item("Mountain walk part", id = "1", duration = 600)
        val b = item("Mountain walk part", id = "2", source = "B", duration = 900)
        assertEquals(2, dedup().collapse(listOf(a, b)).size)
    }

    @Test fun `unrelated titles stay separate`() {
        assertEquals(2, dedup().collapse(listOf(item("Mountain walk"), item("City tour"))).size)
    }

    @Test fun `shared thumbnail and a related title match`() {
        val thumb = "https://img.example/a1b2c3d4e5f6.jpg"
        val a = item("Garden party fun", id = "1", poster = thumb)
        val b = item("Garden party", id = "2", source = "B", poster = thumb)
        assertEquals(1, dedup().collapse(listOf(a, b)).size)
        val generic = item("Garden party fun", id = "3", poster = "https://img.example/1.jpg")
        val other = item("Garden party", id = "4", source = "B", poster = "https://img.example/1.jpg")
        assertEquals(2, dedup().collapse(listOf(generic, other)).size)
    }

    @Test fun `shared performer lowers the title threshold`() {
        val a = item("Jake and Max in the garden", id = "1", performers = listOf("Jake Stone"))
        val b = item("Jake and Max garden scene", id = "2", source = "B", performers = listOf("jake stone"))
        assertEquals(1, dedup().collapse(listOf(a, b)).size)
    }

    @Test fun `best copy wins by resolution then reliability then speed`() {
        val low = item("Mountain Walk", id = "1", source = "A")
        val hd = item("Mountain Walk 1080p", id = "2", source = "B")
        assertEquals(listOf(hd), dedup().collapse(listOf(low, hd)))
        assertEquals(listOf(hd), dedup().collapse(listOf(hd, low)))
        val a = item("Mountain Walk", id = "3", source = "A")
        val b = item("Mountain Walk", id = "4", source = "B")
        assertEquals(listOf(a), dedup().collapse(listOf(b, a)))
    }

    @Test fun `result does not depend on input order`() {
        val items = listOf(
            item("Mountain Walk 720p", id = "1", source = "A"), item("Mountain Walk", id = "2", source = "B"),
            item("City tour", id = "3", source = "A"),
        )
        val forward = dedup().collapse(items).map(::canonicalId).toSet()
        val backward = dedup().collapse(items.reversed()).map(::canonicalId).toSet()
        assertEquals(forward, backward)
    }

    @Test fun `later pages do not repeat earlier pages of the same row`() {
        val d = dedup()
        val one = item("Mountain walk"); val two = item("City tour")
        assertEquals(listOf(one), d.admit("row", 1, listOf(one), 60))
        assertEquals(listOf(two), d.admit("row", 2, listOf(one, two), 60))
        assertTrue(d.admit("row", 3, listOf(item("City Tour 1080p", id = "77", source = "B")), 60).isEmpty())
    }

    @Test fun `reloading a page does not hide it`() {
        val d = dedup()
        val one = item("Mountain walk")
        repeat(3) { assertEquals(listOf(one), d.admit("row", 1, listOf(one, one), 60)) }
        d.admit("row", 2, listOf(item("City tour")), 60)
        assertEquals(1, d.admit("row", 2, listOf(item("City tour")), 60).size)
    }

    @Test fun `rows are independent of one another`() {
        val d = dedup()
        val one = item("Mountain walk")
        assertEquals(listOf(one), d.admit("row-a", 1, listOf(one), 60))
        assertEquals(listOf(one), d.admit("row-b", 1, listOf(one), 60))
    }

    @Test fun `candidates beyond the limit are not remembered`() {
        val d = dedup()
        val one = item("Mountain walk"); val two = item("City tour")
        assertEquals(listOf(one), d.admit("row", 1, listOf(one, two), 1))
        assertEquals(listOf(two), d.admit("row", 2, listOf(two), 60))
    }

    @Test fun `row ownership depends only on the item`() {
        val amateurOral = ItemData("MANPORN", "https://manporn.xxx/videos/1/a/", "Amateur gay blowjob at home")
        val oralOnly = amateurOral.copy(title = "Great blowjob")
        val plain = amateurOral.copy(title = "Sunny day")
        val amateurRow = Rows.find("ALL|amateur-blowjobs")!!
        val oralRow = Rows.find("MP|/categories/blowjob/")!!
        assertSame(amateurRow, Rows.ownerOf(amateurOral))
        assertTrue(Rows.accepts(amateurRow, amateurOral))
        assertFalse(Rows.accepts(oralRow, amateurOral))
        assertTrue(Rows.accepts(oralRow, oralOnly))
        assertNull(Rows.ownerOf(plain))
        assertTrue(Rows.accepts(Rows.find("MP|/")!!, plain))
        // Same answer every time, whichever row asks first.
        repeat(3) { assertSame(amateurRow, Rows.ownerOf(amateurOral)) }
    }

    @Test fun `blowjob compilations are separate from ordinary blowjobs`() {
        val compilation = ItemData("MANPORN", "https://manporn.xxx/videos/2/a/", "Best blowjob compilation of the year")
        val ordinary = compilation.copy(title = "Gay blowjob in the car")
        assertSame(Rows.find("ALL|blowjob-compilations"), Rows.ownerOf(compilation))
        assertFalse(Rows.accepts(Rows.find("MP|/categories/blowjob/")!!, compilation))
        assertTrue(Rows.accepts(Rows.find("MP|/categories/blowjob/")!!, ordinary))
        assertFalse(Rows.accepts(Rows.find("ALL|blowjob-compilations")!!, ordinary))
    }

    @Test fun `homemade means self made and not studio`() {
        val row = Rows.find("GV|/categories/homemade/")!!
        val home = ItemData("GAYVIDS", "https://www.gayvids.tv/videos/3/a/", "Homemade video with my bro")
        assertTrue(Rows.accepts(row, home))
        assertFalse(Rows.accepts(row, home.copy(title = "Homemade style, Falcon Studio presents")))
        // "amateur" alone is not "homemade".
        assertFalse(row.topic!!.accepts(home.copy(title = "Amateur guys"), viaSourceCategory = false))
    }

    @Test fun `Brazilian and Latino rows need tags or description not a lone title word`() {
        val brazil = Rows.find("GV|/categories/brazilian/")!!.topic!!
        val titleOnly = ItemData("GAYVIDS", "https://www.gayvids.tv/videos/4/a/", "Brazilian guys")
        assertFalse(brazil.accepts(titleOnly, viaSourceCategory = false))
        assertTrue(brazil.accepts(titleOnly.copy(tags = listOf("Brazilian")), viaSourceCategory = false))
        assertTrue(brazil.accepts(titleOnly.copy(description = "two guys from Brazil"), viaSourceCategory = true))
        assertTrue(brazil.needsDetails)
    }

    @Test fun `no row heading claims popularity the sort does not provide`() {
        Rows.all.forEach { row ->
            assertFalse(row.title, Regex("\\b(top|hottest|best)\\b", RegexOption.IGNORE_CASE).containsMatchIn(row.title))
        }
        assertEquals(SortRule.NEWEST, Rows.find("ALL|new-today")!!.sort)
        assertEquals(SortRule.MOST_VIEWED, Rows.find("ALL|popular-week")!!.sort)
    }

    @Test fun `every row feed points at a known source prefix`() {
        val prefixes = setOf("MP", "GV", "GPT")
        Rows.all.forEach { row ->
            assertTrue(row.key, row.feeds.isNotEmpty())
            row.feeds.forEach { assertTrue("${row.key} ${it.prefix}", it.prefix in prefixes) }
        }
        assertEquals(Rows.all.size, Rows.all.map { it.key }.toSet().size)
    }

    @Test fun `MyVidster is gone`() {
        assertTrue(Rows.all.none { it.key.contains("CURATED", true) || it.title.contains("MyVidster", true) })
    }

    @Test fun `every provider uses its own base URL`() {
        assertEquals("https://manporn.xxx", com.brostreamah.sources.ManPornSource.BASE_URL)
        assertEquals("https://www.gayvids.tv", com.brostreamah.sources.GayVidsSource.BASE_URL)
        assertEquals("https://www.gayporntube.com", com.brostreamah.sources.GayPornTubeSource.BASE_URL)
    }

    @Test fun `malformed and non network URLs are rejected`() {
        for (url in listOf("", "javascript:alert(1)", "file:///tmp/example", "https:///missing-host", "https://bad host/")) {
            assertFalse(isHttpUrl(url))
        }
    }

    @Test fun `no callback means resolver failure`() {
        val deliveries = LinkDeliveries()
        attempt { Unit }
        assertFalse(deliveries.hasResults())
    }

    @Test fun `invalid and repeated URLs are not delivered`() {
        val deliveries = LinkDeliveries()
        var calls = 0
        deliveries.emit("javascript:example") { calls++ }
        repeat(3) { deliveries.emit("https://media.example/sample.mp4?token=one") { calls++ } }
        assertEquals(1, calls)
        assertTrue(deliveries.hasResults())
    }

    @Test fun `signed playback URLs retain their query strings`() {
        val deliveries = LinkDeliveries()
        val seen = mutableListOf<String>()
        val urls = listOf("https://media.example/sample.mp4?token=one", "https://media.example/sample.mp4?token=two")
        urls.forEach { url -> deliveries.emit(url) { seen.add(url) } }
        assertEquals(urls, seen)
    }

    @Test fun `failed callback does not count as success`() {
        val deliveries = LinkDeliveries()
        attempt { deliveries.emit("https://media.example/sample.mp4") { throw IOException("delivery failed") } }
        assertFalse(deliveries.hasResults())
    }

    @Test fun `ordinary failures are handled while cancellation propagates`() {
        assertNull(attempt<Unit> { throw IOException("timeout") })
        try {
            attempt<Unit> { throw CancellationException("closed") }
            fail("Cancellation must not be swallowed")
        } catch (_: CancellationException) {
        }
    }
}
