package org.ivanivorontsov.tiho.data

import org.junit.Assert.*
import org.junit.Test

class HistoryLogTest {
    @Test fun recordsOnlyHttp() {
        val h = HistoryLog()
        assertFalse(h.record("about:blank", "", 1)); assertFalse(h.record("file:///x", "", 1))
        assertTrue(h.record("https://a.ru/", "A", 1)); assertEquals(1, h.items.size)
    }
    @Test fun revisitMovesToTopKeepsTitle() {
        val h = HistoryLog()
        h.record("https://a.ru/", "A", 1); h.record("https://b.ru/", "B", 2); h.record("https://a.ru/", "", 3)
        assertEquals(listOf("https://a.ru/", "https://b.ru/"), h.items.map { it.url })
        assertEquals("A", h.items[0].title)
    }
    @Test fun capPruneSearchRemove() {
        val h = HistoryLog(max = 3)
        for (i in 1..5) h.record("https://s$i.ru/", "Сайт $i", i * 1000L)
        assertEquals(3, h.items.size)
        assertEquals(1, h.search("сайт 5").size); assertEquals(1, h.search("S4.RU").size)
        assertTrue(h.prune(1, 4000L + 24L * 3600 * 1000)); assertEquals(listOf("https://s5.ru/", "https://s4.ru/"), h.items.map { it.url })
        h.remove(h.items[0].id); assertEquals(1, h.items.size)
        assertFalse(h.prune(0, Long.MAX_VALUE))
        h.clear(); assertTrue(h.items.isEmpty())
    }
    @Test fun jsonRoundTrip() {
        val h = HistoryLog(); h.record("https://a.ru/", "А \"q\"", 5)
        assertEquals(h.items, HistoryLog.fromJson(HistoryLog.toJson(h.items)))
        assertTrue(HistoryLog.fromJson("garbage").isEmpty())
    }
}
