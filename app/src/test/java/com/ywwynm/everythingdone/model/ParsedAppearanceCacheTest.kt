package com.ywwynm.everythingdone.model

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ParsedAppearanceCacheTest {
    @Test fun textEqualityAndHashCollisionsDoNotMixValues() {
        var calls = 0
        val cache = ParsedAppearanceCache { text -> calls++; text.reversed() }
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertEquals("aA", cache.parse("Aa"))
        assertEquals("BB", cache.parse("BB"))
        assertEquals("aA", cache.parse(String(charArrayOf('A', 'a'))))
        assertEquals(2, calls)
    }

    @Test fun leastRecentlyReadValueIsEvictedAtEntryLimit() {
        val calls = mutableListOf<String>()
        val cache = ParsedAppearanceCache(maxEntries = 2) { text -> calls += text; Any() }
        val a = cache.parse("a")
        cache.parse("b")
        assertSame(a, cache.parse("a"))
        cache.parse("c")
        cache.parse("b")
        assertEquals(listOf("a", "b", "c", "b"), calls)
    }

    @Test fun textBudgetAndOversizedBypassBoundRetention() {
        val calls = mutableListOf<String>()
        val cache = ParsedAppearanceCache(maxChars = 6, maxEntryChars = 4) { text -> calls += text; Any() }
        val hot = cache.parse("aa")
        repeat(2) { cache.parse("oversized") }
        assertSame(hot, cache.parse("aa"))
        cache.parse("bbb")
        cache.parse("ccc")
        cache.parse("aa")
        assertEquals(listOf("aa", "oversized", "oversized", "bbb", "ccc", "aa"), calls)
    }

    @Test fun invalidResultsAreReusableAndEmptyInputNeverDecodes() {
        var calls = 0
        val cache = ParsedAppearanceCache<String> { calls++; null }
        assertNull(cache.parse(null))
        assertNull(cache.parse(""))
        repeat(3) { assertNull(cache.parse("invalid")) }
        assertEquals(1, calls)
    }

    @Test fun slowBackgroundDecodeDoesNotBlockCachedUiRead() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val cache = ParsedAppearanceCache { text ->
            if (text == "slow") { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            Any()
        }
        val hot = cache.parse("hot")
        try {
            val slow = pool.submit(Callable { cache.parse("slow") })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertSame(hot, pool.submit(Callable { cache.parse("hot") }).get(2, TimeUnit.SECONDS))
            release.countDown()
            assertNotNull(slow.get(2, TimeUnit.SECONDS))
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun concurrentMissesPublishOneImmutableValue() {
        val entered = CountDownLatch(4)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        val cache = ParsedAppearanceCache {
            entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); Any()
        }
        try {
            val jobs = List(4) { pool.submit(Callable { cache.parse("shared") }) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            release.countDown()
            val first = jobs.first().get(2, TimeUnit.SECONDS)
            jobs.forEach { assertSame(first, it.get(2, TimeUnit.SECONDS)) }
        } finally { release.countDown(); pool.shutdownNow() }
    }
}
