package io.github.arielfikru.rabbitrocket.hls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaTypesTest {
    @Test
    fun tokenisedMp4WithTrailingSlashIsAFile() {
        val u = "https://cdn.example.com/get_file/3/abc/123000/123456/123456.mp4/?v-acctoken=xyz&rnd=1730000000"
        assertEquals(MediaKind.File, MediaTypes.classify(u))
        assertEquals("mp4", MediaTypes.extFor(u, null, null))
    }

    @Test
    fun playlistsAreHls() {
        assertEquals(MediaKind.Hls, MediaTypes.classify("https://x.test/a/master.m3u8?t=1"))
        assertEquals(MediaKind.Hls, MediaTypes.classify("https://x.test/live", "application/vnd.apple.mpegurl"))
    }

    @Test
    fun segmentsAndNonMediaAreIgnored() {
        assertNull(MediaTypes.classify("https://x.test/seg_001.ts"))
        assertNull(MediaTypes.classify("https://x.test/app.js"))
        assertNull(MediaTypes.classify("blob:https://x.test/123"))
    }

    @Test
    fun extFallsBackToFileNameThenMime() {
        assertEquals("webm", MediaTypes.extFor("https://x.test/dl?id=1", null, "clip.webm"))
        assertEquals("mkv", MediaTypes.extFor("https://x.test/dl?id=1", "video/x-matroska", null))
        assertEquals("mp4", MediaTypes.extFor("https://x.test/dl?id=1", null, null))
    }
}
