package com.brostreamah

import kotlin.math.ln

enum class SortRule {
    /** Keep the order the site returned. */
    SOURCE_ORDER,
    /** Newest upload first; needs real upload timestamps. */
    NEWEST,
    /** Most viewed first within the time window; needs real view counts. */
    MOST_VIEWED,
    /** Combined rating, views, resolution and provider reliability. */
    RANKED,
}

internal class Feed(val prefix: String, val path: String)

/**
 * What an item must show to belong to a category. Evidence is scored: a tag hit is worth 3, a title hit 2,
 * a description hit 1, and arriving through the site's own matching category 1. Rows demand [minScore] so
 * a single word in a title is not enough where the category needs verified tags.
 */
internal class Topic(
    val any: Regex? = null,
    val allOf: List<Regex> = emptyList(),
    val none: Regex? = null,
    val minScore: Int = 2,
) {
    fun score(item: ItemData, viaSourceCategory: Boolean): Int {
        val everything = (listOf(item.title, item.description) + item.tags).joinToString(" \n ")
        if (none?.containsMatchIn(everything) == true) return 0
        if (allOf.any { !it.containsMatchIn(everything) }) return 0
        val rules = (if (any != null) listOf(any) else emptyList()) + allOf
        if (rules.isEmpty()) return minScore
        var score = if (viaSourceCategory) 1 else 0
        for (rule in rules) {
            if (item.tags.any { rule.containsMatchIn(it) }) score += 3
            if (rule.containsMatchIn(item.title)) score += 2
            if (rule.containsMatchIn(item.description)) score += 1
        }
        return score
    }

    fun accepts(item: ItemData, viaSourceCategory: Boolean = false) = score(item, viaSourceCategory) >= minScore
    /** True when tags or a description (not just the listing title) must be read to decide. */
    val needsDetails: Boolean get() = minScore >= 3
}

/** One home row: where it reads from, what belongs in it, how it is ordered, and when it is hidden. */
internal class CategoryRow(
    val key: String,
    val title: String,
    val feeds: List<Feed>,
    val topic: Topic? = null,
    val sort: SortRule = SortRule.SOURCE_ORDER,
    val fallbackQuery: String = "",
    /** Only uploads from the last [windowMillis] qualify (New Today, Popular This Week). */
    val windowMillis: Long = 0,
    /** Fewer surviving videos than this on page 1 means the row is hidden after fallbacks. */
    val minItems: Int = 8,
) {
    val hasWindow get() = windowMillis > 0
}

private fun feed(prefix: String, path: String) = Feed(prefix, path)
private const val DAY = 86_400_000L

private val oral = wordRegex("blowjob", "blowjobs", "blow job", "suck", "sucking", "sucked", "oral", "deepthroat", "deep throat", "head", "cocksucking", "cocksucker")
private val compilation = wordRegex("compilation", "compilations", "compil", "best of", "megamix", "mega mix", "collection", "supercut", "mix", "top 10", "top 20")
private val cum = wordRegex("cum", "cumshot", "cumshots", "facial", "facials", "jizz", "load", "loads", "creampie")
private val amateur = wordRegex("amateur", "amateurs", "real life")
private val homemade = wordRegex("homemade", "home made", "home video", "self shot", "selfshot", "self filmed", "filmed at home", "made at home", "diy")
private val studio = wordRegex(
    "studio", "productions", "entertainment", "presents", "presented by", "official", "premium", "exclusive", "men com", "helix",
    "corbin fisher", "falcon", "raging stallion", "next door", "bel ami", "lucas", "active duty", "sean cody", "randy blue", "cockyboys",
)
private val straight = wordRegex("straight", "curious", "bestie", "friend", "friends", "buddy", "buddies")
private val experiment = wordRegex("straight", "curious", "experiment", "experimenting")
private val firstTime = wordRegex("first time", "first-time", "virgin", "first experience", "first contact", "first")
private val creator = wordRegex("onlyfans", "only fans", "creator", "fan site", "webcam", "cam", "subscriber")
private val brazil = wordRegex("brazil", "brazilian", "brazilians", "brasil", "brasileiro", "brasileiros", "carioca", "paulista")
private val latino = wordRegex("latino", "latinos", "latin", "hispanic", "mexican", "colombian", "venezuelan", "argentine", "argentinian", "chilean", "peruvian", "cuban", "puerto rican")
private val pnp = wordRegex("pnp", "p n p", "slam", "slamming", "slammed", "tina", "chemsex", "party and play", "party n play")
private val muscle = wordRegex("muscle", "muscles", "muscular", "bodybuilder", "bodybuilders", "ripped")
private val jock = wordRegex("jock", "jocks", "athlete", "athletes", "football", "wrestler", "wrestlers", "rugby")
private val bigDick = wordRegex("big cock", "big dick", "bigcock", "huge cock", "big dicks", "monster cock", "hung", "huge dick")
private val bareback = wordRegex("bareback", "barebacking", "raw", "breed", "breeding")
private val group = wordRegex("group", "orgy", "orgies", "gangbang", "foursome", "threesome", "fivesome")
private val solo = wordRegex("solo", "jerk off", "jerking off", "masturbate", "masturbates", "masturbation", "wank", "wanking")
private val outdoor = wordRegex("outdoor", "outdoors", "public", "beach", "woods", "forest", "hiking", "camping")
private val gloryhole = wordRegex("glory hole", "gloryhole", "gloryholes", "glory holes")
private val handjob = wordRegex("handjob", "hand job", "handjobs", "handy", "stroking")
private val interracial = wordRegex("interracial", "bbc", "ebony", "black guy", "black guys", "black and white")
private val asian = wordRegex("asian", "asians", "japanese", "korean", "chinese", "thai", "filipino", "vietnamese", "indonesian")
private val party = wordRegex("party", "parties", "sauna", "club", "festival")

internal object Rows {
    val all: List<CategoryRow> = listOf(
        // Real time-based rows first: they only fill when the sources expose upload times and view counts.
        CategoryRow("ALL|new-today", "🆕 New Today",
            listOf(feed("MP", "/"), feed("GV", "/"), feed("GPT", "/")),
            sort = SortRule.NEWEST, windowMillis = DAY, minItems = 6),
        CategoryRow("ALL|popular-week", "📈 Popular This Week",
            listOf(feed("MP", "/"), feed("GV", "/"), feed("GPT", "/")),
            sort = SortRule.MOST_VIEWED, windowMillis = 7 * DAY, minItems = 6),
        CategoryRow("MP|/", "🎬 Gay Men", listOf(feed("MP", "/")), fallbackQuery = "gay men"),
        CategoryRow("GV|/", "🎞️ Gay Videos", listOf(feed("GV", "/")), fallbackQuery = "gay men"),

        // Specific rows are listed before broad ones: the first matching row owns an item.
        CategoryRow("ALL|amateur-blowjobs", "👄 Amateur Blowjobs",
            listOf(feed("MP", "/search/?q=amateur+gay+blowjob"), feed("GV", "/search/amateur-gay-blowjob/")),
            Topic(allOf = listOf(oral, amateur), none = compilation), SortRule.RANKED, "amateur gay blowjob"),
        CategoryRow("GV|/categories/homemade/", "🎥 Homemade (Non-Studio)",
            listOf(feed("GV", "/categories/homemade/"), feed("MP", "/search/?q=homemade+gay")),
            Topic(any = homemade, none = studio), SortRule.RANKED, "homemade gay"),
        CategoryRow("ALL|amateur", "🏠 Amateur Men",
            listOf(feed("GV", "/categories/amateur/"), feed("MP", "/search/?q=amateur+gay"), feed("GPT", "/search/videos/amateur-gay/page1.html")),
            Topic(any = amateur, none = oral), SortRule.RANKED, "amateur men"),
        CategoryRow("GPT|/search/videos/straight-guy-blowjob/page1.html", "🔥 Straight Guys Getting Serviced",
            listOf(feed("GPT", "/search/videos/straight-guy-blowjob/page1.html")),
            Topic(allOf = listOf(oral, straight), none = compilation), fallbackQuery = "straight guy gay blowjob"),
        CategoryRow("GV|/search/straight-friends-gay/", "🤝 Straight Friends Playing Around",
            listOf(feed("GV", "/search/straight-friends-gay/")),
            Topic(allOf = listOf(wordRegex("straight", "curious"), wordRegex("friend", "friends", "bestie", "buddy", "buddies"))),
            fallbackQuery = "straight friends gay experimenting"),
        CategoryRow("ALL|first-time", "🫣 First Time",
            listOf(feed("MP", "/search/?q=gay+first+time"), feed("GV", "/categories/first-time/")),
            Topic(any = firstTime), SortRule.RANKED, "gay first time"),
        CategoryRow("ALL|creators", "📱 Creators & Webcam",
            listOf(feed("GV", "/search/gay-onlyfans/"), feed("MP", "/categories/webcam/")),
            Topic(any = creator), SortRule.RANKED, "gay onlyfans creator men"),
        CategoryRow("ALL|blowjob-compilations", "💦 Blowjob Compilations",
            listOf(feed("MP", "/categories/compilation/"), feed("GV", "/categories/compilation/")),
            Topic(allOf = listOf(oral, compilation)), fallbackQuery = "gay blowjob compilation"),
        CategoryRow("MP|/categories/blowjob/", "👄 Blowjobs",
            listOf(feed("MP", "/categories/blowjob/")),
            Topic(any = oral, none = compilation), fallbackQuery = "gay blowjob"),
        CategoryRow("GV|/categories/compilation/", "🎞️ Cumshot Compilations",
            listOf(feed("GV", "/categories/compilation/"), feed("MP", "/categories/cumshot/")),
            Topic(allOf = listOf(compilation, cum), none = oral), fallbackQuery = "cum compilation"),
        CategoryRow("GV|/categories/brazilian/", "🇧🇷 Brazilian Men",
            listOf(feed("GV", "/categories/brazilian/")),
            Topic(any = brazil, minScore = 3), fallbackQuery = "brazilian men"),
        CategoryRow("MP|/categories/latino/", "🌶️ Latino Men",
            listOf(feed("MP", "/categories/latino/")),
            Topic(any = latino, minScore = 3), fallbackQuery = "latino men"),
        CategoryRow("GPT|/search/videos/pnp-slam/page1.html", "🔥 PNP & Slam",
            listOf(feed("GPT", "/search/videos/pnp-slam/page1.html")), Topic(any = pnp), fallbackQuery = "pnp slam"),
        CategoryRow("GV|/categories/party/", "🎉 Party & Group Play",
            listOf(feed("GV", "/categories/party/")), Topic(any = party), fallbackQuery = "gay party"),
        CategoryRow("GV|/search/straight-curious-guys/", "Straight & Curious Guys",
            listOf(feed("GV", "/search/straight-curious-guys/")),
            Topic(allOf = listOf(experiment)), fallbackQuery = "straight curious gay men"),
        CategoryRow("MP|/categories/muscle/", "💪 Muscle Men", listOf(feed("MP", "/categories/muscle/")),
            Topic(any = muscle), fallbackQuery = "muscle men"),
        CategoryRow("GV|/search/gay-jock/", "🔥 Jocks", listOf(feed("GV", "/search/gay-jock/")),
            Topic(any = jock), fallbackQuery = "gay jock"),
        CategoryRow("GV|/categories/big-cock/", "Big Dick", listOf(feed("GV", "/categories/big-cock/")),
            Topic(any = bigDick), fallbackQuery = "big cock men"),
        CategoryRow("MP|/categories/bareback/", "Bareback", listOf(feed("MP", "/categories/bareback/")),
            Topic(any = bareback), fallbackQuery = "gay bareback"),
        CategoryRow("GV|/categories/group-sex/", "Group & Orgies", listOf(feed("GV", "/categories/group-sex/")),
            Topic(any = group), fallbackQuery = "gay group"),
        CategoryRow("MP|/categories/solo/", "Solo Men", listOf(feed("MP", "/categories/solo/")),
            Topic(any = solo), fallbackQuery = "solo male"),
        CategoryRow("GV|/categories/outdoor/", "Outdoor & Public", listOf(feed("GV", "/categories/outdoor/")),
            Topic(any = outdoor), fallbackQuery = "gay outdoor"),
        CategoryRow("MP|/categories/cumshot/", "Cumshots", listOf(feed("MP", "/categories/cumshot/")),
            Topic(any = cum, none = compilation), fallbackQuery = "gay cumshot"),
        CategoryRow("GV|/categories/gloryhole/", "Gloryholes", listOf(feed("GV", "/categories/gloryhole/")),
            Topic(any = gloryhole), fallbackQuery = "gay gloryhole"),
        CategoryRow("MP|/categories/handjob/", "Handjobs", listOf(feed("MP", "/categories/handjob/")),
            Topic(any = handjob), fallbackQuery = "gay handjob"),
        CategoryRow("GV|/categories/interracial/", "Interracial Men", listOf(feed("GV", "/categories/interracial/")),
            Topic(any = interracial), fallbackQuery = "gay interracial"),
        CategoryRow("MP|/categories/asian/", "Asian Men", listOf(feed("MP", "/categories/asian/")),
            Topic(any = asian), fallbackQuery = "asian gay men"),
    )

    private val byKey = all.associateBy { it.key }
    fun find(key: String): CategoryRow? = byKey[key]

    /**
     * Fixed ownership: the earliest row in [all] with a topic that an item satisfies owns it, using only the
     * item's own evidence. The result never depends on which row CloudStream happens to load first.
     */
    fun ownerOf(item: ItemData): CategoryRow? =
        all.firstOrNull { it.topic != null && it.topic.accepts(item, viaSourceCategory = false) }

    fun accepts(row: CategoryRow, item: ItemData): Boolean {
        val topic = row.topic ?: return true
        if (!topic.accepts(item, viaSourceCategory = true)) return false
        val owner = ownerOf(item)
        return owner == null || owner === row
    }

    /** Ranking used by RANKED rows: rating, views, resolution and provider reliability. */
    fun rank(item: ItemData, profile: (String) -> SourceProfile?): Double =
        item.rating * 5.0 + ln(1.0 + item.views) * 40.0 + copyScore(item, profile) / 1000.0
}
