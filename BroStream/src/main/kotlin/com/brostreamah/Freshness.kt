package com.brostreamah

import kotlin.random.Random

/** Orders a row so a viewer keeps seeing different videos: new ones first, then seen, then watched. */
internal object Freshness {
    /**
     * [status] is 0 for a video new to the viewer, 1 for one shown before, 2 for one already watched. Rows sorted by
     * time or views ([keepOrder]) keep their order inside each group; other rows are shuffled so they differ each time.
     */
    fun arrange(items: List<ItemData>, status: (ItemData) -> Int, keepOrder: Boolean, random: Random): List<ItemData> {
        val groups = items.groupBy(status)
        fun part(n: Int) = (groups[n] ?: emptyList()).let { if (keepOrder || n == 2) it else it.shuffled(random) }
        return part(0) + part(1) + part(2)
    }
}
