package io.github.arielfikru.rabbitrocket.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkSkipperTest {

    @Test
    fun shortlinkOnlyMatchesAnAlias() {
        assertTrue(LinkSkipper.isShortlink("https://droplink.co/6ijtT"))
        assertTrue(LinkSkipper.isShortlink(" https://www.droplink.co/TFshAjYQ "))
        assertFalse(LinkSkipper.isShortlink("https://droplink.co/"))
        assertFalse(LinkSkipper.isShortlink("https://droplink.co/links/go"))
        assertFalse(LinkSkipper.isShortlink("https://example.com/6ijtT"))
        assertFalse(LinkSkipper.isShortlink("droplink.co/6ijtT"))
        assertFalse(LinkSkipper.isShortlink(null))
    }

    @Test
    fun hopSitesAreLearnedFromTheLinkpagePath() {
        assertFalse(LinkSkipper.isHop("https://hop-test.example/some-post/"))
        assertTrue(LinkSkipper.isHop("https://hop-test.example/Linkpage%202.3.0/safe2-v2.php"))
        assertTrue(LinkSkipper.isHop("https://hop-test.example/some-post/"))
        assertFalse(LinkSkipper.isHop("https://www.mediafire.com/folder/abc/ZIP1"))
    }

    @Test
    fun unwrapsGoogleAndBingReferrers() {
        assertEquals(
            "https://tech8s.net/a-post/",
            LinkSkipper.unwrapReferrer("https://www.google.com/url?sa=t&source=web&url=https://tech8s.net/a-post/&usg=x"),
        )
        // u=a1 + unpadded base64url of https://game5s.com/how-to-enable-and-use-double-tap-feature-on-apple-watch/
        assertEquals(
            "https://game5s.com/how-to-enable-and-use-double-tap-feature-on-apple-watch/",
            LinkSkipper.unwrapReferrer(
                "https://www.bing.com/ck/a?!&&p=1&u=a1aHR0cHM6Ly9nYW1lNXMuY29tL2hvdy10by1lbmFibGUtYW5kLXVzZS1kb3VibGUtdGFwLWZlYXR1cmUtb24tYXBwbGUtd2F0Y2gv&ntb=1",
            ),
        )
        assertEquals("https://example.com/x", LinkSkipper.unwrapReferrer("https://example.com/x"))
    }

    @Test
    fun readsTheSessionStorageState() {
        val state = """{"adl_flow":"1","adl_alias":"6ijtT","adl_site":"site1","adl_sig":"aa80"}"""
        assertEquals(
            mapOf("adl_flow" to "1", "adl_alias" to "6ijtT", "adl_site" to "site1", "adl_sig" to "aa80"),
            LinkSkipper.parseFlatJson(state),
        )
        assertEquals("https://a.b/url?x=1&y=2", LinkSkipper.jsUnescape("""https:\/\/a.b\/url?x=1&y=2"""))
    }

    @Test
    fun readsTheFormThatCarriesTheMarker() {
        val page = """
            <form role="search" method="get" action="https://tech8s.net/"><input name="s" value=""></form>
            <form action="https://game5s.com/Linkpage%202.3.0/safe2-v2.php" method="post">
              <input type="hidden" name="adl_step" value="2"/>
              <input type="hidden" name="adl_sig" value="a&amp;b"/>
              <input type="hidden" name="adl_start_next" value="1">
            </form>
        """.trimIndent()
        val (action, fields) = LinkSkipper.parseFormWithAction(page, "adl_")!!
        assertEquals("https://game5s.com/Linkpage%202.3.0/safe2-v2.php", action)
        assertEquals(mapOf("adl_step" to "2", "adl_sig" to "a&b", "adl_start_next" to "1"), fields)
        assertNull(LinkSkipper.parseForm(page, "ad_form_data"))
    }
}
