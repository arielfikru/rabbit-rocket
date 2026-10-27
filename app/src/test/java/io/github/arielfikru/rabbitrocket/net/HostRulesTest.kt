package io.github.arielfikru.rabbitrocket.net

import io.github.arielfikru.rabbitrocket.fmtResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostRulesTest {
    private val rules = HostRules.parse(
        """
        ! comment
        ||doubleclick.net^
        ||ads.example.com^${'$'}important
        ||tracker.io^${'$'}client=foo
        @@||good.ads.example.com^
        ||com^
        /banner\d+/
        """.trimIndent(),
    )

    @Test fun blocksHostAndSubdomains() {
        assertTrue(rules.isBlocked("doubleclick.net"))
        assertTrue(rules.isBlocked("stats.g.doubleclick.net"))
        assertTrue(rules.isBlocked("ADS.example.com."))
    }

    @Test fun allowRuleWins() {
        assertFalse(rules.isBlocked("good.ads.example.com"))
        assertTrue(rules.isBlocked("bad.ads.example.com"))
    }

    @Test fun skipsUnsupportedAndTldRules() {
        assertFalse(rules.isBlocked("tracker.io"))
        assertFalse(rules.isBlocked("example.com"))
        assertEquals(2, rules.size)
    }

    @Test fun siteGroupsSubdomains() {
        assertEquals("example.com", HostRules.site("cdn.static.example.com"))
        assertEquals("detik.co.id", HostRules.site("m.detik.co.id"))
        assertEquals("localhost", HostRules.site("localhost"))
    }

    @Test fun resolutionUsesShortSide() {
        assertEquals("1080p · 1920×1080", fmtResolution(1920, 1080))
        assertEquals("720p · 720×1280", fmtResolution(720, 1280))
    }
}
