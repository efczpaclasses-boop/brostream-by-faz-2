package com.brostreamah

import java.net.URI
import java.util.Locale
import java.util.concurrent.CancellationException

/** Identity for catalogue pages only; playback URLs retain their signed query strings. */
internal fun catalogueUrlKey(value: String): String? = attempt {
    val uri = URI(value).normalize()
    require(uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank())
    val port = if (uri.port == -1 || uri.port == 80 && uri.scheme.equals("http", true) ||
        uri.port == 443 && uri.scheme.equals("https", true)) "" else ":${uri.port}"
    val query = uri.rawQuery.orEmpty().split('&').filter { part ->
        val key = part.substringBefore('=').lowercase(Locale.ROOT)
        part.isNotBlank() && !key.startsWith("utm_") && key !in setOf("fbclid", "gclid")
    }.sorted().joinToString("&")
    "${uri.host.lowercase(Locale.ROOT)}$port${uri.rawPath.orEmpty().trimEnd('/')}" +
        if (query.isEmpty()) "" else "?$query"
}

internal fun isHttpUrl(value: String): Boolean = attempt {
    val uri = URI(value)
    uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank()
} == true

/** Count actual deliveries, not just a resolver returning without an exception. */
internal class LinkDeliveries {
    private val delivered = HashSet<String>()

    @Synchronized
    fun emit(url: String, callback: () -> Unit) {
        if (!isHttpUrl(url) || delivered.contains(url)) return
        callback()
        delivered.add(url)
    }

    @Synchronized
    fun hasResults(): Boolean = delivered.isNotEmpty()
}

internal inline fun <T> attempt(block: () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}
