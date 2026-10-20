package com.ywwynm.everythingdone.helpers

import android.content.res.Resources
import android.graphics.drawable.Drawable
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.request.FutureTarget
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.R
import java.io.File
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/** 实际 Glide 解码回归：小夹具验证 loader 路径，大文件 OOM 由真实首页隔离测试覆盖。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = LocalAnimatedImageModelTest.TestApp::class)
@LooperMode(LooperMode.Mode.PAUSED)
class LocalAnimatedImageModelTest {
    class TestApp : App() {
        override fun onCreate() { ReflectionHelpers.setStaticField(App::class.java, "app", this) }
        override fun getResources(): Resources = TestResources(super.getResources())
    }
    @Suppress("DEPRECATION")
    class TestResources(base: Resources) : Resources(base.assets, base.displayMetrics, base.configuration) {
        override fun getString(id: Int): String = if (id == R.string.base_signal) "`启q琼" else "resource-$id"
    }
    private val context get() = RuntimeEnvironment.getApplication()
    private val targets = mutableListOf<FutureTarget<Drawable>>()
    private lateinit var file: File
    @Before fun setup() {
        Glide.init(context, GlideBuilder())
        file = File(context.cacheDir, "local-motion.GIF").apply {
            writeBytes(Base64.getDecoder().decode("R0lGODlhAgACAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh" +
                "+QQAZAAAACwAAAAAAgACAAAIBgABCAQQEAAh+QQBZAABACwAAAAAAgACAIEAAP8AAAAAAAAAAAAIBgABCAQQEAA7"))
        }
    }
    @After fun cleanup() {
        targets.forEach { Glide.with(context).clear(it) }
        Glide.get(context).clearMemory(); Glide.tearDown()
    }
    private fun load(model: Any): GifDrawable {
        // 模型选择与 Glide 解码使用 JVM；产品 MediaCropTransformation 由三机真实首页覆盖。
        val target = Glide.with(context).load(model).centerCrop().submit(2, 2).also { targets += it }
        return CompletableFuture.supplyAsync { target.get(10, TimeUnit.SECONDS) }
            .get(12, TimeUnit.SECONDS) as GifDrawable
    }
    @Test fun `本地 GIF 通过实际 Glide 请求使用直接映射且保留帧及裁切像素`() {
        val mapped = load(localAnimatedImageModel(file.path))
        assertTrue("首页本地 GIF 应走文件映射", mapped.buffer.isDirect)
        assertEquals(file.length().toInt(), mapped.buffer.limit())
        assertEquals(2, mapped.frameCount)
        assertEquals(2, mapped.intrinsicWidth)
        assertEquals(2, mapped.intrinsicHeight)
        val after = IntArray(4)
        mapped.firstFrame!!.getPixels(after, 0, 2, 0, 0, 2, 2)
        assertArrayEquals(IntArray(4) { android.graphics.Color.RED }, after)
    }
    @Test fun `首页同一来源仍共享活动资源`() {
        val first = load(localAnimatedImageModel(file.path))
        val second = load(localAnimatedImageModel(file.path))
        assertSame(first.constantState, second.constantState)
    }
    @Test fun `URI 和缺失文件保持原请求模型`() {
        for (path in listOf("content://media/external/17.gif", "https://example.invalid/a.gif", file.toURI().toString(), file.path + ".missing.gif")) {
            assertEquals(path, localAnimatedImageModel(path))
        }
    }
    @Test fun `普通照片与视频不改变加载器`() {
        for (suffix in listOf("jpg", "png", "mp4")) {
            val ordinary = File(context.cacheDir, "ordinary.$suffix").apply { writeBytes(byteArrayOf(1)) }
            assertEquals(ordinary.path, localAnimatedImageModel(ordinary.path))
        }
    }
}
