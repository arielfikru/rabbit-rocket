package io.github.arielfikru.rabbitrocket.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class BrowserHistoryTest {
    private fun at(y: Int, m: Int, d: Int, h: Int = 12) =
        Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, 0) }.timeInMillis

    private val now = at(2026, 10, 27, 9)

    @Test
    fun dayLabels() {
        assertEquals("Hari ini", dayLabel(at(2026, 10, 27, 0), now))
        assertEquals("Kemarin", dayLabel(at(2026, 10, 26, 23), now))
        assertEquals("Minggu, 25 Okt", dayLabel(at(2026, 10, 25), now))
        assertEquals("31 Des 2025", dayLabel(at(2025, 12, 31), now))
        // Yesterday across a year boundary.
        assertEquals("Kemarin", dayLabel(at(2025, 12, 31), at(2026, 1, 1, 8)))
    }

    @Test
    fun groupsByDayAndFilters() {
        val visits = listOf(
            Visit("https://a.com/watch", "Video A", at(2026, 10, 27, 8)),
            Visit("https://b.com/", "Berita", at(2026, 10, 27, 7)),
            Visit("https://c.com/clip", "", at(2026, 10, 26)),
        )
        val all = groupHistory(visits, "", now)
        assertEquals(listOf("Hari ini", "Kemarin"), all.map { it.first })
        assertEquals(2, all[0].second.size)

        assertEquals(listOf("https://a.com/watch"), groupHistory(visits, "video", now).flatMap { it.second }.map { it.url })
        assertEquals(listOf("https://c.com/clip"), groupHistory(visits, " CLIP ", now).flatMap { it.second }.map { it.url })
        assertEquals(emptyList<Pair<String, List<Visit>>>(), groupHistory(visits, "zzz", now))
    }
}
