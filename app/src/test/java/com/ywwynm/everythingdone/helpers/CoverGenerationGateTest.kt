package com.ywwynm.everythingdone.helpers

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CoverGenerationGateTest {
    @Test fun `停止的原生调用尚未退出时同源新轮次等待且旧轮次不能发布`() {
        val gate = CoverGenerationGate()
        val pool = Executors.newFixedThreadPool(2)
        val nativeEntered = CountDownLatch(1)
        val releaseNative = CountDownLatch(1)
        val nextEntered = CountDownLatch(1)
        val nextStarted = CountDownLatch(1)
        val stopped = AtomicBoolean()
        val published = AtomicInteger()
        try {
            val old = pool.submit<Boolean> {
                try {
                    gate.run("same.gif", stopped::get) { checkActive ->
                        nativeEntered.countDown()
                        check(releaseNative.await(5, TimeUnit.SECONDS))
                        checkActive()
                        published.incrementAndGet()
                    }
                    false
                } catch (_: CancellationException) { true }
            }
            assertTrue(nativeEntered.await(5, TimeUnit.SECONDS))
            stopped.set(true)
            val next = pool.submit {
                nextStarted.countDown()
                gate.run("same.gif", { false }) { nextEntered.countDown(); published.incrementAndGet() }
            }
            assertTrue(nextStarted.await(5, TimeUnit.SECONDS))
            assertFalse(nextEntered.await(250, TimeUnit.MILLISECONDS))
            releaseNative.countDown()
            assertTrue(old.get(5, TimeUnit.SECONDS))
            next.get(5, TimeUnit.SECONDS)
            assertEquals(1, published.get())
        } finally { releaseNative.countDown(); pool.shutdownNow() }
    }

    @Test fun `等待旧轮次时再次停止不会进入生成也不阻塞后续重试`() {
        val gate = CoverGenerationGate()
        val pool = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        val stopped = AtomicBoolean()
        try {
            val old = pool.submit { gate.run("same.gif", { false }) {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val next = pool.submit<Boolean> {
                var checks = 0
                try { gate.run("same.gif", {
                    if (++checks > 1) waiting.countDown()
                    stopped.get()
                }) { fail("停止的等待者不能生成") }; false }
                catch (_: CancellationException) { true }
            }
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            stopped.set(true)
            assertTrue(next.get(5, TimeUnit.SECONDS))
            release.countDown(); old.get(5, TimeUnit.SECONDS)
            assertEquals("ready", gate.run("same.gif", { false }) { "ready" })
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun `生成异常后释放互斥不同来源无需等待`() {
        val gate = CoverGenerationGate()
        try { gate.run("same.gif", { false }) { throw IllegalStateException("编码失败") }; fail() }
        catch (_: IllegalStateException) { }
        val pool = Executors.newSingleThreadExecutor()
        try {
            gate.run("same.gif", { false }) {
                val other = pool.submit<String> { gate.run("other.gif", { false }) { "other" } }
                assertEquals("other", other.get(5, TimeUnit.SECONDS))
            }
        } finally { pool.shutdownNow() }
    }
}
