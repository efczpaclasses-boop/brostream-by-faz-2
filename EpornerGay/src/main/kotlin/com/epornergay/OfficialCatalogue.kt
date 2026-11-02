package com.epornergay

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * Homepage rows for the official feeds. CloudStream plugins can open a settings
 * screen (`Plugin.openSettings`), so category rows are selectable there. Core
 * rows stay visible. A saved empty selection leaves only those core rows.
 */
object OfficialCatalogue {

    const val PREFS = "brostream_official_rows"
    const val PREFS_KEY = "enabled_row_ids"

    fun allRows(): List<OfficialRow> =
        ChaturbateMaleLive.rows() +
            StripchatMaleLive.rows() +
            CamSodaMaleLive.rows() +
            EpornerGayVideos.rows() +
            PornhubGayWebmasters.rows()

    fun rowPairs(saved: Set<String>?): List<Pair<String, String>> {
        val visible = selectedOfficialRows(allRows(), saved).toMutableList()
        if (saved != null) {
            val tags = saved.filter { it.startsWith("CB|tag|") }
            ChaturbateMaleLive.mixRow(tags)?.let { visible += it }
        }
        return visible.map { it.id to it.label }
    }

    fun mainPage(saved: Set<String>?): List<MainPageData> = mainPageOf(*rowPairs(saved).toTypedArray())

    fun handlesRow(data: String): Boolean =
        ChaturbateMaleLive.handlesRow(data) ||
            StripchatMaleLive.handlesRow(data) ||
            CamSodaMaleLive.handlesRow(data) ||
            EpornerGayVideos.handlesRow(data) ||
            PornhubGayWebmasters.handlesRow(data)

    fun handles(data: String): Boolean =
        ChaturbateMaleLive.handles(data) ||
            StripchatMaleLive.handles(data) ||
            CamSodaMaleLive.handles(data) ||
            EpornerGayVideos.handles(data) ||
            PornhubGayWebmasters.handles(data)

    suspend fun home(api: MainAPI, page: Int, request: MainPageRequest): HomePageResponse? = when {
        ChaturbateMaleLive.handlesRow(request.data) -> ChaturbateMaleLive.homePage(api, page, request.name, request.data)
        StripchatMaleLive.handlesRow(request.data) -> StripchatMaleLive.homePage(api, page, request.name, request.data)
        CamSodaMaleLive.handlesRow(request.data) -> CamSodaMaleLive.homePage(api, page, request.name, request.data)
        EpornerGayVideos.handlesRow(request.data) -> EpornerGayVideos.homePage(api, page, request.name, request.data)
        PornhubGayWebmasters.handlesRow(request.data) -> PornhubGayWebmasters.homePage(api, page, request.name, request.data)
        else -> null
    }

    suspend fun load(api: MainAPI, data: String): LoadResponse? = when {
        ChaturbateMaleLive.handles(data) -> ChaturbateMaleLive.load(api, data)
        StripchatMaleLive.handles(data) -> StripchatMaleLive.load(api, data)
        CamSodaMaleLive.handles(data) -> CamSodaMaleLive.load(api, data)
        EpornerGayVideos.handles(data) -> EpornerGayVideos.load(api, data)
        PornhubGayWebmasters.handles(data) -> PornhubGayWebmasters.load(api, data)
        else -> null
    }

    suspend fun loadLinks(data: String, callback: (ExtractorLink) -> Unit): Boolean = when {
        ChaturbateMaleLive.handles(data) -> ChaturbateMaleLive.loadLinks(data, callback)
        StripchatMaleLive.handles(data) -> StripchatMaleLive.loadLinks(data, callback)
        CamSodaMaleLive.handles(data) -> CamSodaMaleLive.loadLinks(data, callback)
        EpornerGayVideos.handles(data) -> EpornerGayVideos.loadLinks(data, callback)
        PornhubGayWebmasters.handles(data) -> PornhubGayWebmasters.loadLinks(data, callback)
        else -> false
    }

    suspend fun search(api: MainAPI, query: String): List<SearchResponse> = buildList {
        addAll(runCatching { ChaturbateMaleLive.search(api, query) }.getOrDefault(emptyList()))
        addAll(runCatching { StripchatMaleLive.search(api, query) }.getOrDefault(emptyList()))
        addAll(runCatching { CamSodaMaleLive.search(api, query) }.getOrDefault(emptyList()))
        addAll(runCatching { EpornerGayVideos.search(api, query) }.getOrDefault(emptyList()))
        addAll(runCatching { PornhubGayWebmasters.search(api, query) }.getOrDefault(emptyList()))
    }
}
