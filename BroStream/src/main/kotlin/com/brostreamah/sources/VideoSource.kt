package com.brostreamah.sources

import com.brostreamah.ItemData
import com.brostreamah.SourceHealth
import com.brostreamah.StreamCandidate
import com.brostreamah.VideoDetails

/** Every provider exposes the same four operations and owns its own fixed base URL. */
interface VideoSource {
    val id: String
    /** Prefix used in main-page row keys, e.g. "MP|/categories/muscle/". */
    val prefix: String
    val label: String
    val shortLabel: String
    val baseUrl: String
    /** 0..100, used to pick the best copy of a duplicated video. */
    val reliability: Int
    /** 0..100, relative loading speed. */
    val speed: Int

    fun searchPath(query: String): String

    suspend fun catalogue(page: Int, path: String): List<ItemData>
    suspend fun search(page: Int, query: String): List<ItemData> = catalogue(page, searchPath(query))
    suspend fun details(item: ItemData): VideoDetails?
    suspend fun playback(item: ItemData): List<StreamCandidate>
    suspend fun health(): SourceHealth
}
