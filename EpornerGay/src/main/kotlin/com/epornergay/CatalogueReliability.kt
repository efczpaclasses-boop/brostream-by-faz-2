package com.epornergay

import java.net.URI
import java.text.Normalizer
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

internal fun catalogueTitleKey(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        .replace(Regex("\\b(4k|2160p|1080p|720p|480p|hd|full video)\\b"), "")
        .replace(Regex("[^\\p{L}\\p{N}]"), "")

internal fun catalogueKeys(url: String, title: String): List<String> {
    val urlKey = catalogueUrlKey(url) ?: return emptyList()
    val titleKey = catalogueTitleKey(title)
    val uri = URI(url)
    val itemId = Regex("/(?:videos?|watch)/(\\d+)(?:/|$)").find(uri.path.orEmpty())?.groupValues?.get(1)
    return listOfNotNull(
        "url:$urlKey",
        itemId?.let { "id:${uri.host.lowercase(Locale.ROOT)}:$it" },
        titleKey.takeIf { it.isNotEmpty() }?.let { "title:$it" },
    )
}

internal fun <T> uniqueCatalogue(items: List<T>, keys: (T) -> List<String>): List<T> {
    val seen = HashSet<String>()
    return items.filter { item ->
        val identities = keys(item).filter(String::isNotBlank)
        (identities.isNotEmpty() && identities.none(seen::contains)).also { accepted ->
            if (accepted) seen.addAll(identities)
        }
    }
}

/** Claims are atomic and page-aware, and expire instead of hiding entries indefinitely. */
internal class CatalogueClaims(
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = 15 * 60 * 1000L,
    private val maxKeys: Int = 8192,
) {
    private data class Owner(val row: String, val page: Int, val expiresAt: Long)
    private val owners = LinkedHashMap<String, Owner>()

    init {
        require(ttlMillis > 0 && maxKeys > 0)
    }

    @Synchronized
    fun <T> select(row: String, page: Int, items: List<T>, limit: Int, keys: (T) -> List<String>): List<T> {
        require(page >= 1 && limit >= 0)
        prune()
        owners.entries.removeAll { (_, owner) -> owner.row == row && (page == 1 || owner.page == page) }
        val result = ArrayList<T>()
        for (item in items) {
            if (result.size >= limit) break
            val identities = keys(item).filter(String::isNotBlank).distinct()
            if (identities.isEmpty() || identities.any(owners::containsKey)) continue
            val owner = Owner(row, page, now() + ttlMillis)
            identities.forEach { owners[it] = owner }
            result.add(item)
        }
        while (owners.size > maxKeys) owners.remove(owners.keys.first())
        return result
    }

    private fun prune() {
        val time = now()
        owners.entries.removeAll { (_, owner) -> owner.expiresAt <= time }
    }
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
