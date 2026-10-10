package com.brostreamah

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class FreshnessTest {
    private fun v(n: Int) = ItemData("T", "https://t.example/videos/$n/x/", "Video $n")
    private val items = (1..30).map(::v)
    private fun status(watched: Set<Int>, shown: Set<Int>): (ItemData) -> Int = { item ->
        val n = item.title.removePrefix("Video ").toInt()
        if (n in watched) 2 else if (n in shown) 1 else 0
    }

    @Test fun `new videos come first, then shown, then watched`() {
        val watched = (1..5).toSet(); val shown = (6..15).toSet()
        val out = Freshness.arrange(items, status(watched, shown), keepOrder = false, random = Random(1))
        val statuses = out.map(status(watched, shown))
        assertEquals(statuses.sorted(), statuses)
        assertEquals(30, out.size)
        assertEquals(items.toSet(), out.toSet())
    }

    @Test fun `different seeds give different orders so the row changes between visits`() {
        val s = status(emptySet(), emptySet())
        val a = Freshness.arrange(items, s, false, Random(1)).map { it.title }
        val b = Freshness.arrange(items, s, false, Random(2)).map { it.title }
        assertNotEquals(a, b)
        assertEquals(a.toSet(), b.toSet())
    }

    @Test fun `the same seed repeats the same order`() {
        val s = status(emptySet(), emptySet())
        assertEquals(Freshness.arrange(items, s, false, Random(7)), Freshness.arrange(items, s, false, Random(7)))
    }

    @Test fun `time sorted rows keep their order but still push watched videos down`() {
        val s = status(watched = setOf(1, 2), shown = emptySet())
        val out = Freshness.arrange(items, s, keepOrder = true, random = Random(3)).map { it.title.removePrefix("Video ").toInt() }
        assertEquals((3..30).toList() + listOf(1, 2), out)
    }
}
