package com.ywwynm.everythingdone.adapters

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定 2026-07-31 两次闪退暴露的 Glide 生命周期契约。
 *
 * 这里检查真实调用点，而不是重新实现 Glide：问题正是适配器用私有 Target 吞掉
 * onLoadCleared，导致已回收 GifDrawable 仍在 Activity.onStart 时被重新启动。
 */
class ImageAttachmentGifLifecycleSourceTest {

    private val source by lazy {
        readSource(
            "app/src/main/java/com/ywwynm/everythingdone/adapters/" +
                "ImageAttachmentAdapter.kt"
        )
    }

    @Test
    fun `附件图片不得通过吞掉 onLoadCleared 保留旧画面`() {
        assertFalse(source.contains("class KeepCurrentImageTarget"))
        assertFalse(source.contains("override fun onLoadCleared(placeholder: Drawable?)"))
        assertTrue(source.contains(".into(imageView)"))
    }

    @Test
    fun `holder 回收必须清理 Glide target 而不是只停止当前 drawable`() {
        val recycleBody = source.substringAfter("override fun onViewRecycled")
            .substringBefore("/** 图片/视频附件容器")

        assertTrue(recycleBody.contains("Glide.with(imageView.context).clear(imageView)"))
    }

    @Test
    fun `同一附件切换静动请求使用独立安全快照避免闪空白`() {
        assertTrue(source.contains("createTransitionPlaceholder(imageView"))
        assertTrue(source.contains(".placeholder(transitionPlaceholder)"))
    }

    @Test
    fun `加载中请求与已就绪资源使用不同 key 避免占位图阻断重试`() {
        assertTrue(source.contains("tag_detail_attachment_image_request_key"))
        assertTrue(
            source.contains(
                "imageView.setTag(R.id.tag_detail_attachment_image_load_key, null)"
            )
        )
        val failureBody = source.substringAfter("override fun onLoadFailed")
            .substringBefore("override fun onResourceReady")
        assertTrue(failureBody.contains("tag_detail_attachment_image_request_key, null"))
    }

    @Test
    fun `运行中的 GIF 不得再次从首帧启动`() {
        val playbackBody = source.substringAfter("private fun applyGifPlaybackState")
            .substringBefore("/**\n     * 告诉调度器")

        assertTrue(
            Regex(
                """if\s*\(\s*!gif\.isRunning\s*\)\s*\{\s*gif\.startFromFirstFrame\(\)"""
            ).containsMatchIn(playbackBody)
        )
    }

    private fun readSource(relativePath: String): String {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(7) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile ?: return@repeat
        }
        error("找不到源码：$relativePath")
    }
}
