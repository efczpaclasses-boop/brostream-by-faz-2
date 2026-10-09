package com.brostreamah

import java.util.Locale

internal const val WORD_BEFORE = "(?<![\\p{L}\\p{N}])"
internal const val WORD_AFTER = "(?![\\p{L}\\p{N}])"

/** Whole-word, case-insensitive, Unicode-aware; spaces in a term also match "-" and "_". */
internal fun wordRegex(vararg terms: String): Regex {
    val body = terms.sortedByDescending { it.length }
        .joinToString("|") { Regex.escape(it).replace(" ", "\\E[\\s_-]+\\Q") }
    return Regex("$WORD_BEFORE(?:$body)$WORD_AFTER", RegexOption.IGNORE_CASE)
}

enum class Verdict { ACCEPT, REJECT, AMBIGUOUS }

data class Assessment(val verdict: Verdict, val reason: String)

/**
 * Men-only policy. No source is trusted by default: an item is shown only with positive evidence of
 * male-only content, and any sign of a woman (words, abbreviations, names, metadata) rejects it.
 * Matching is whole-word and Unicode-aware, so "mom" never matches inside "moment".
 */
internal object ContentPolicy {
    private const val BEFORE = WORD_BEFORE
    private const val AFTER = WORD_AFTER

    private val femaleEnglish = wordRegex(
        "lesbian", "lesbians", "lesbo", "lez", "girl", "girls", "girly", "gal", "gals", "woman", "women", "female", "females",
        "femme", "milf", "milfs", "gilf", "mom", "moms", "mommy", "mum", "mother", "mothers", "stepmom", "stepmother",
        "step mom", "step mother", "wife", "wives", "hotwife", "hot wife", "daughter", "stepdaughter", "step daughter",
        "sister", "stepsister", "step sister", "sis", "girlfriend", "gf", "ex gf", "bride", "babe", "babes", "chick", "chicks",
        "lady", "ladies", "madam", "mistress", "dominatrix", "domme", "pussy", "vagina", "clit", "tits", "titty", "titties",
        "boobs", "boobies", "breasts", "busty", "bbw", "pregnant", "shemale", "she male", "trans", "transgender", "tranny",
        "tgirl", "t girl", "ladyboy", "futa", "schoolgirl", "cougar", "granny", "aunt", "auntie", "actress", "she", "her", "hers",
        "herself", "nun", "teacher miss",
    )
    private val femaleOther = wordRegex(
        // Spanish
        "mujer", "mujeres", "chica", "chicas", "niña", "niñas", "esposa", "esposas", "novia", "hermana", "madre", "mamá", "mami",
        "señora", "lesbiana", "lesbianas", "tía", "abuela", "rubia",
        // Portuguese
        "menina", "meninas", "mulher", "mulheres", "namorada", "irmã", "mãe", "mae", "safada", "safadas", "novinha", "gostosa",
        // French
        "fille", "filles", "femme", "femmes", "maman", "mère", "copine", "sœur", "soeur", "belle-mère", "lesbienne",
        // German
        "frau", "frauen", "mädchen", "madchen", "mutter", "mama", "schwester", "tante", "oma", "lesbe", "weib",
        // Italian
        "donna", "donne", "ragazza", "ragazze", "mamma", "sorella", "moglie", "lesbica", "nonna",
        // Russian
        "девушка", "девушки", "женщина", "женщины", "мама", "мать", "жена", "сестра", "лесбиянки", "лесби",
    )
    /** Scripts written without spaces cannot use word boundaries, so these match as substrings. */
    private val femaleCjk = Regex("女|人妻|レズ|母|姐|妈|妻|姊")
    private val femaleNames = wordRegex(
        "sophia", "olivia", "emma", "ava", "isabella", "charlotte", "amelia", "evelyn", "abigail", "emily", "ella", "scarlett",
        "chloe", "victoria", "lily", "hannah", "natalie", "brandi", "kendra", "kayla", "tiffany", "jessica", "jennifer",
        "ashley", "amanda", "melissa", "stephanie", "nicole", "samantha", "katie", "megan", "lauren", "brittany", "courtney",
        "mia", "lana", "angela", "alexis texas", "kim kardashian", "jenna", "bella", "tanya", "natasha", "svetlana",
    )
    /** Scene-composition shorthand and mixed-pairing phrases, e.g. "MMF", "m/f", "bi couple". */
    private val mixedPairing = Regex(
        "$BEFORE(?:mmf|ffm|mff|fmm|mfm|fmf|m\\s?[/+x&]\\s?f|f\\s?[/+x&]\\s?m|bi\\s?mmf|bi\\s?couple)$AFTER",
        RegexOption.IGNORE_CASE,
    )
    private val mixedPhrases = wordRegex(
        "straight couple", "boy and girl", "guy and girl", "man and woman", "husband and wife", "couple and a guy",
        "swinger", "swingers", "threesome with a woman",
    )
    /** Bisexual scenes almost always include women; the policy is men-only, so they are rejected outright. */
    private val bisexual = wordRegex("bisexual", "bi sexual", "bisexuals", "bi male", "bi men", "bi guys", "bi guy", "bi curious female")

    private val maleEvidence = wordRegex(
        "gay", "gays", "guy", "guys", "man", "men", "male", "males", "boy", "boys", "dude", "dudes", "twink", "twinks", "bear",
        "bears", "cub", "cubs", "daddy", "daddies", "dad", "jock", "jocks", "stud", "studs", "hunk", "hunks", "bro", "bros",
        "brother", "brothers", "stepbro", "stepbrother", "stepdad", "stepson", "step bro", "step dad", "step son", "lad", "lads",
        "boyfriend", "boyfriends", "m/m", "hombres", "chicos", "homens", "garotos", "rapazes", "gay porn",
    )
    private val maleGenders = setOf("male", "m", "man", "men", "boy")

    fun femaleHit(text: String): String? {
        val found = listOf(femaleEnglish, femaleOther, femaleNames, femaleCjk).firstNotNullOfOrNull { it.find(text)?.value }
        return found
    }

    fun mixedHit(text: String): String? =
        listOf(mixedPairing, mixedPhrases, bisexual).firstNotNullOfOrNull { it.find(text)?.value }

    fun maleHit(text: String): String? = maleEvidence.find(text)?.value

    fun classify(item: ItemData, blocklist: Blocklist = Blocklist.EMPTY, siteDeclaresMaleOnly: Boolean = false): Assessment {
        if (item.url.isBlank() || item.title.isBlank()) return Assessment(Verdict.REJECT, "missing url or title")
        blocklist.blocks(item)?.let { return Assessment(Verdict.REJECT, it) }

        // Metadata first: declared performer genders are stronger than any wording.
        val genders = item.genders.map { it.trim().lowercase(Locale.ROOT) }.filter(String::isNotEmpty)
        genders.firstOrNull { it !in maleGenders }?.let { return Assessment(Verdict.REJECT, "performer gender: $it") }

        val fields = buildList {
            add("title" to item.title)
            item.tags.forEach { add("tag" to it) }
            item.performers.forEach { add("performer" to it) }
            if (item.description.isNotBlank()) add("description" to item.description)
        }
        for ((kind, text) in fields) {
            femaleHit(text)?.let { return Assessment(Verdict.REJECT, "female term in $kind: $it") }
            mixedHit(text)?.let { return Assessment(Verdict.REJECT, "mixed or bisexual in $kind: $it") }
        }

        if (genders.isNotEmpty()) return Assessment(Verdict.ACCEPT, "all declared performers are male")
        // Description alone is weak (it may be marketing copy); tags, title and performer metadata count.
        for ((kind, text) in fields) {
            if (kind == "description" || kind == "performer") continue
            maleHit(text)?.let { return Assessment(Verdict.ACCEPT, "male evidence in $kind: $it") }
        }
        item.description.takeIf(String::isNotBlank)?.let { text ->
            maleHit(text)?.let { return Assessment(Verdict.ACCEPT, "male evidence in description: $it") }
        }
        // Weak evidence: the site itself is a gay-only site. It never applies to an item whose detail page has not
        // been read, and never overrides any negative evidence found above.
        if (siteDeclaresMaleOnly && item.detailed && (item.tags.isNotEmpty() || item.description.isNotBlank())) {
            return Assessment(Verdict.ACCEPT, "weak: gay-only site, detail page shows no contrary evidence")
        }
        return Assessment(Verdict.AMBIGUOUS, "no positive male evidence")
    }
}

/** Items held back because they could not be shown safely; never rendered on any row. */
internal class Quarantine(private val capacity: Int = 500) {
    data class Entry(val item: ItemData, val reason: String)

    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    fun add(item: ItemData, reason: String) {
        val key = canonicalId(item)
        entries.remove(key)
        entries[key] = Entry(item, reason)
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    @Synchronized
    fun contains(item: ItemData) = entries.containsKey(canonicalId(item))

    @Synchronized
    fun release(item: ItemData) { entries.remove(canonicalId(item)) }

    @Synchronized
    fun snapshot(): List<Entry> = entries.values.toList()
}
