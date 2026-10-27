package io.github.arielfikru.rabbitrocket.hls

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class PlaylistParserTest {
    private val base = "https://cdn.example.com/show/ep1/master.m3u8"

    @Test fun masterSortedByBandwidthWithResolvedUrls() {
        val p = PlaylistParser.parse(
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
            360/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
            https://other.example.com/1080.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
            /abs/720.m3u8
            """.trimIndent(),
            base,
        ) as Playlist.Master
        assertEquals(listOf("1080p", "720p", "360p"), p.variants.map { it.quality })
        assertEquals("https://other.example.com/1080.m3u8", p.variants[0].url)
        assertEquals("https://cdn.example.com/abs/720.m3u8", p.variants[1].url)
        assertEquals("https://cdn.example.com/show/ep1/360/index.m3u8", p.variants[2].url)
        assertEquals("0.8 Mbps · avc1 + mp4a", p.variants[2].detail)
    }

    @Test fun mediaWithKeyRotationSequenceAndEndlist() {
        val p = PlaylistParser.parse(
            """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:40
            #EXTINF:6.0,
            a.ts
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x000102030405060708090a0b0c0d0e0f
            #EXTINF:5.5,
            b.ts?token=x
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:4,
            c.ts
            #EXT-X-ENDLIST
            """.trimIndent(),
            base,
        ) as Playlist.Media
        assertEquals(3, p.segments.size)
        assertFalse(p.isLive)
        assertEquals(15.5, p.duration, 1e-9)
        assertEquals(listOf(40L, 41L, 42L), p.segments.map { it.sequence })
        assertNull(p.segments[0].key)
        val k = p.segments[1].key!!
        assertEquals("AES-128", k.method)
        assertEquals("https://cdn.example.com/show/ep1/key.bin", k.url)
        assertArrayEquals(ByteArray(16) { it.toByte() }, k.iv)
        assertEquals("https://cdn.example.com/show/ep1/b.ts?token=x", p.segments[1].url)
        assertNull(p.segments[2].key)
    }

    @Test fun byteRangesAndInitSegment() {
        val p = PlaylistParser.parse(
            """
            #EXTM3U
            #EXT-X-MAP:URI="main.mp4",BYTERANGE="720@0"
            #EXTINF:4,
            #EXT-X-BYTERANGE:1000@720
            main.mp4
            #EXTINF:4,
            #EXT-X-BYTERANGE:500
            main.mp4
            """.trimIndent(),
            base,
        ) as Playlist.Media
        assertTrue(p.isLive)
        val init = assertNotNull(p.init).let { p.init!! }
        assertEquals(ByteRange(0, 720), init.range)
        assertEquals(ByteRange(720, 1000), p.segments[0].range)
        assertEquals(ByteRange(1720, 500), p.segments[1].range)
    }

    @Test fun plainM3uOfPlaylistsBecomesMaster() {
        val p = PlaylistParser.parse("http://a.tv/one.m3u8\nhttp://a.tv/two.m3u8?x=1\n", base)
        assertTrue(p is Playlist.Master)
        assertEquals(2, (p as Playlist.Master).variants.size)
    }

    @Test fun decryptAes128RoundTripAndDefaultIv() {
        val key = ByteArray(16) { (it * 7).toByte() }
        val iv = HlsClient.ivFor(41)
        assertEquals(41, iv[15].toInt())
        val plain = ByteArray(1000) { it.toByte() }
        val enc = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(plain)
        }
        assertArrayEquals(plain, HlsClient.decrypt(enc, key, iv))
    }

    @Test fun stripsFakePngHeaderFromTs() {
        val ts = ByteArray(188 * 4) { if (it % 188 == 0) 0x47 else 1 }
        val disguised = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ts
        assertArrayEquals(ts, HlsClient.stripFakeHeader(disguised))
        assertArrayEquals(ts, HlsClient.stripFakeHeader(ts))
    }
}
