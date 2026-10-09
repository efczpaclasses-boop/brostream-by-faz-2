package com.brostreamah

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.Assert.*
import org.junit.Test

class ContentPolicyTest {
    private val cases: Map<String, List<String>> = jacksonObjectMapper().readValue(
        checkNotNull(javaClass.classLoader?.getResourceAsStream("classification_cases.json")),
    )

    private fun item(title: String, tags: List<String> = emptyList(), performers: List<String> = emptyList(),
                     genders: List<String> = emptyList(), description: String = "") =
        ItemData("T", "https://t.example/videos/1/x/", title, tags = tags, performers = performers, genders = genders, description = description)

    private fun verdict(title: String) = ContentPolicy.classify(item(title)).verdict

    @Test fun `dataset is large enough to mean something`() {
        assertTrue(cases.getValue("female").size >= 20)
        assertTrue(cases.getValue("lesbian").size >= 5)
        assertTrue(cases.getValue("bisexual").size >= 8)
        assertTrue(cases.getValue("maleOnly").size >= 10)
    }

    @Test fun `every female case is rejected`() {
        val leaks = cases.getValue("female").filter { verdict(it) != Verdict.REJECT }
        assertEquals("female titles that were not rejected: $leaks", emptyList<String>(), leaks)
    }

    @Test fun `every lesbian case is rejected`() {
        val leaks = cases.getValue("lesbian").filter { verdict(it) != Verdict.REJECT }
        assertEquals(emptyList<String>(), leaks)
    }

    @Test fun `every bisexual or mixed case is rejected`() {
        val leaks = cases.getValue("bisexual").filter { verdict(it) != Verdict.REJECT }
        assertEquals(emptyList<String>(), leaks)
    }

    @Test fun `every male only case is accepted`() {
        val missed = cases.getValue("maleOnly").filter { verdict(it) != Verdict.ACCEPT }
        assertEquals("male titles that were not accepted: $missed", emptyList<String>(), missed)
    }

    @Test fun `ambiguous titles are never accepted and never rejected`() {
        val wrong = cases.getValue("ambiguous").filter { verdict(it) != Verdict.AMBIGUOUS }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test fun `words inside other words do not count`() {
        assertNull(ContentPolicy.femaleHit("moment of truth"))
        assertNull(ContentPolicy.femaleHit("therapist"))
        assertNull(ContentPolicy.femaleHit("shell"))
        assertNull(ContentPolicy.femaleHit("hero"))
        assertNull(ContentPolicy.maleHit("manager"))   // "man" inside a longer word is not evidence
        assertNotNull(ContentPolicy.femaleHit("MOM"))
        assertNotNull(ContentPolicy.femaleHit("step-mom"))
        assertNotNull(ContentPolicy.femaleHit("step_mom"))
    }

    @Test fun `a dedicated gay site is not trusted without evidence`() {
        // The same unclear title must not be shown just because the source is a gay site.
        val unclear = item("Video 12345").copy(source = "GAYVIDS")
        assertEquals(Verdict.AMBIGUOUS, ContentPolicy.classify(unclear).verdict)
    }

    @Test fun `positive tags make an unclear title acceptable`() {
        assertEquals(Verdict.ACCEPT, ContentPolicy.classify(item("Scene 12", tags = listOf("Gay", "Muscle"))).verdict)
        assertEquals(Verdict.ACCEPT, ContentPolicy.classify(item("Clip", description = "Gay men at the beach")).verdict)
    }

    @Test fun `female metadata rejects an otherwise male title`() {
        assertEquals(Verdict.REJECT, ContentPolicy.classify(item("Gay scene", tags = listOf("Female"))).verdict)
        assertEquals(Verdict.REJECT, ContentPolicy.classify(item("Gay guys", tags = listOf("Bisexual"))).verdict)
        assertEquals(Verdict.REJECT, ContentPolicy.classify(item("Gay scene", performers = listOf("Mia"))).verdict)
        assertEquals(Verdict.REJECT, ContentPolicy.classify(item("Gay guys", description = "with his girlfriend")).verdict)
    }

    @Test fun `declared performer genders decide`() {
        assertEquals(Verdict.REJECT, ContentPolicy.classify(item("Scene 1", genders = listOf("male", "female"))).verdict)
        assertEquals(Verdict.REJECT, ContentPolicy.classify(item("Gay scene", genders = listOf("transgender"))).verdict)
        assertEquals(Verdict.ACCEPT, ContentPolicy.classify(item("Scene 1", genders = listOf("male", "Male"))).verdict)
    }

    @Test fun `blocklist rejects by id and by term`() {
        val block = Blocklist.parse("""{"ids":["T:1"],"rejectTerms":["forbiddenword"]}""", jacksonObjectMapper())
        assertEquals(Verdict.REJECT, ContentPolicy.classify(item("Gay guys"), block).verdict)
        val other = item("Gay guys forbiddenword").copy(url = "https://t.example/videos/2/y/")
        assertEquals(Verdict.REJECT, ContentPolicy.classify(other, block).verdict)
        val fine = item("Gay guys").copy(url = "https://t.example/videos/3/z/")
        assertEquals(Verdict.ACCEPT, ContentPolicy.classify(fine, block).verdict)
        assertEquals(Blocklist.EMPTY, Blocklist.parse("not json", jacksonObjectMapper()))
    }

    @Test fun `quarantine is bounded and releasable`() {
        val quarantine = Quarantine(capacity = 2)
        val a = item("a").copy(url = "https://t.example/videos/1/a/")
        val b = item("b").copy(url = "https://t.example/videos/2/b/")
        val c = item("c").copy(url = "https://t.example/videos/3/c/")
        quarantine.add(a, "x"); quarantine.add(b, "x"); quarantine.add(c, "x")
        assertFalse(quarantine.contains(a))
        assertTrue(quarantine.contains(c))
        quarantine.release(c)
        assertFalse(quarantine.contains(c))
    }
}
