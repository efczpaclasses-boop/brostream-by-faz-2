package com.epornergay

/** Shared helpers for the official affiliate and webmaster feeds. */

internal fun officialEmbedSrc(html: String?): String? {
    if (html.isNullOrBlank()) return null
    val raw = Regex("""src\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        .find(html)?.groupValues?.get(1)
        ?: return null
    val src = raw.replace("&amp;", "&").replace("\\/", "/")
    return when {
        src.startsWith("//") -> "https:$src"
        src.startsWith("http://") || src.startsWith("https://") -> src
        else -> null
    }
}

internal fun <T> List<T>.orderHdThenPopularity(hd: (T) -> Boolean, popularity: (T) -> Long): List<T> =
    sortedWith(compareByDescending<T>(hd).thenByDescending(popularity))

data class OfficialRow(
    val id: String,
    val label: String,
    val core: Boolean,
    val defaultOn: Boolean,
)

/** null means the viewer has never saved a selection, so defaults apply. */
internal fun selectedOfficialRows(rows: List<OfficialRow>, saved: Set<String>?): List<OfficialRow> {
    if (saved == null) return rows.filter { it.core || it.defaultOn }
    val chosen = saved.toSet()
    return rows.filter { it.core || it.id in chosen }
}
