package com.ywwynm.everythingdone.adapters

import android.app.Application
import android.graphics.drawable.Drawable
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.load.engine.executor.GlideExecutor
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.request.FutureTarget
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.signature.ObjectKey
import com.ywwynm.everythingdone.helpers.DetailGifPlaybackOwner
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** 使用真实 Glide File 请求，覆盖两个显示位置命中同一个活动资源的情况。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class DetailGifPlaybackIsolationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val targets = mutableListOf<FutureTarget<Drawable>>()
    private val drawables = mutableListOf<GifDrawable>()
    private lateinit var sourceExecutor: GlideExecutor
    private val gifFile by lazy {
        File(context.cacheDir, "two-frame.gif").apply {
            writeBytes(Base64.getDecoder().decode(GIF_DATA))
        }
    }

    @Before
    fun setup() {
        sourceExecutor = GlideExecutor.newSourceBuilder().setThreadCount(1).build()
        Glide.init(context, GlideBuilder().setSourceExecutor(sourceExecutor))
    }

    @After
    fun cleanup() {
        drawables.forEach { it.stop() }
        targets.forEach { Glide.with(context).clear(it) }
        Glide.get(context).clearMemory()
        Glide.tearDown()
    }

    @Test
    fun `另一个显示位置仍在播放时当前 GIF 可以从首帧开始`() {
        val first = loadGif(DetailGifPlaybackOwner().requestOptions)
        first.start()
        val second = loadGif(DetailGifPlaybackOwner().requestOptions)
        assertNotSame(first, second)
        assertNotSame(first.constantState, second.constantState)
        assertTrue(first.isRunning)
        assertFalse(second.isRunning)

        // 与生产调用相同：当前实例未运行，仍可能因共享帧加载器而抛出异常。
        if (!second.isRunning) second.startFromFirstFrame()

        assertTrue(first.isRunning)
        assertTrue(second.isRunning)
    }

    @Test
    fun `未隔离的活动资源复用会重现日志中的底层异常`() {
        val first = loadGif()
        first.start()
        val second = loadGif()
        assertNotSame(first, second)
        assertSame(first.constantState, second.constantState)
        assertFalse(second.isRunning)

        // stop 也只退订当前 drawable，不能停止另一个显示位置的帧加载器。
        second.stop()
        val error = assertThrows(IllegalArgumentException::class.java) {
            if (!second.isRunning) second.startFromFirstFrame()
        }
        assertEquals("Can't restart a running animation", error.message)
        assertTrue(first.isRunning)
    }

    @Test
    fun `播放一次不会接入共享循环资源也不会停止其它位置`() {
        val looping = loadGif()
        looping.start()
        val once = loadGif(DetailGifPlaybackOwner().requestOptions)
        once.setLoopCount(1)
        once.startFromFirstFrame()
        once.stop()

        assertNotSame(looping.constantState, once.constantState)
        assertTrue(looping.isRunning)
        assertFalse(once.isRunning)
        once.startFromFirstFrame()
        assertTrue(once.isRunning)
    }

    @Test
    fun `不同所有者的并发请求不合并为同一个解码任务`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        sourceExecutor.execute {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val firstTarget: FutureTarget<Drawable>
        val secondTarget: FutureTarget<Drawable>
        try {
            firstTarget = requestGif(DetailGifPlaybackOwner().requestOptions)
            secondTarget = requestGif(DetailGifPlaybackOwner().requestOptions)
            assertFalse(firstTarget.isDone)
            assertFalse(secondTarget.isDone)
        } finally {
            release.countDown()
        }
        val first = awaitGif(firstTarget)
        val second = awaitGif(secondTarget)
        assertNotSame(first.constantState, second.constantState)
        first.start()
        second.startFromFirstFrame()
        assertTrue(first.isRunning)
        assertTrue(second.isRunning)
    }

    @Test
    fun `同一所有者停止并释放请求后仍可复用自己的内存资源`() {
        val owner = DetailGifPlaybackOwner()
        val first = loadGif(owner.requestOptions)
        first.start()
        first.stop()
        Glide.with(context).clear(targets.single())
        val second = loadGif(owner.requestOptions)
        assertSame(first.constantState, second.constantState)
        second.startFromFirstFrame()
        assertTrue(second.isRunning)
    }

    @Test
    fun `所有者不改变磁盘缓存身份`() {
        fun digest(options: RequestOptions): ByteArray =
            MessageDigest.getInstance("SHA-256").let {
                options.options.updateDiskCacheKey(it)
                it.digest()
            }

        val original = digest(RequestOptions())
        assertArrayEquals(original, digest(DetailGifPlaybackOwner().requestOptions))
        assertArrayEquals(original, digest(DetailGifPlaybackOwner().requestOptions))
    }

    private fun loadGif(options: RequestOptions = RequestOptions()): GifDrawable =
        awaitGif(requestGif(options))

    private fun requestGif(options: RequestOptions): FutureTarget<Drawable> =
        Glide.with(context).load(gifFile).centerCrop()
            .signature(ObjectKey("same-detail-load-key"))
            .apply(options)
            .submit(2, 2)
            .also { targets += it }

    private fun awaitGif(target: FutureTarget<Drawable>): GifDrawable =
        (CompletableFuture.supplyAsync {
            target.get(10, TimeUnit.SECONDS)
        }.get(12, TimeUnit.SECONDS) as GifDrawable).also { drawables += it }

    private companion object {
        const val GIF_DATA = "R0lGODlhAgACAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh" +
            "+QQAZAAAACwAAAAAAgACAAAIBgABCAQQEAAh+QQBZAABACwAAAAAAgACAIEAAP8AAAAAAAAAAAAIBgABCAQQEAA7"
    }
}
