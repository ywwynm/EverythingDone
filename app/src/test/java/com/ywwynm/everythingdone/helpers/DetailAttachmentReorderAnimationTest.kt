package com.ywwynm.everythingdone.helpers

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DefaultItemAnimator
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.adapters.ImageAttachmentAdapter
import com.ywwynm.everythingdone.model.ThingBackground
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadows.ShadowUiAutomation

/** 实际统一列表和 ConcatAdapter 执行重排，只替换媒体解码及资源布局。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class,
    qualifiers = "w400dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class DetailAttachmentReorderAnimationTest {
    private lateinit var owner: ActivityController<Activity>
    private lateinit var activity: Activity
    private lateinit var list: RecyclerView
    private lateinit var content: DetailContentLayout
    private lateinit var images: MediaAdapter
    private val items = mutableListOf<String?>("0image-a", "1video-b", "0image-c", "1video-d")

    @Before fun setup() {
        ShadowUiAutomation.setAnimationScaleCompat(1f)
        val accent = ReflectionHelpers.getStaticField<Any>(App::class.java, "defaultAccentBackground\$delegate")
        ReflectionHelpers.setField(accent, "_value", ThingBackground.pure(Color.BLUE))
        owner = Robolectric.buildActivity(Activity::class.java)
        activity = owner.get()
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        owner.setup().visible()
        list = RecyclerView(activity)
        val sections = LinearLayout(activity)
        for (id in intArrayOf(R.id.et_title, R.id.et_content, R.id.ll_move_checklist,
            R.id.ll_type_info_screenshot, R.id.tv_update_time, R.id.tv_finish_time,
            R.id.tv_thing_folder_path)) {
            sections.addView(View(activity).apply { this.id = id }, LinearLayout.LayoutParams(-1, 20))
        }
        content = DetailContentLayout(list, sections)
        images = MediaAdapter()
        content.update(images, null, false, null, 1)
        activity.setContentView(list)
        layout()
        idle(400)
    }

    @After fun cleanup() {
        if (::list.isInitialized) list.itemAnimator?.endAnimations()
        if (::owner.isInitialized) owner.pause().stop().destroy()
    }

    @Test fun `图片视频换位在原位置开始并连续移动到新位置`() {
        verifyReorder()
    }

    @Test fun `对照仅恢复动画器且保持预测布局关闭即可连续换位`() {
        list.itemAnimator = DefaultItemAnimator().apply { supportsChangeAnimations = false }
        assertFalse(content.layoutManager.supportsPredictiveItemAnimations())
        verifyReorder()
    }

    @Test fun `临时动画器退出后恢复附件换位动画`() {
        list.itemAnimator = checklistAnimator()
        content.restoreAttachmentAnimator()
        verifyReorder()
    }

    @Test fun `清单移动模式的实际动画器仍允许附件换位`() {
        list.itemAnimator = checklistAnimator()
        verifyReorder()
    }

    private fun checklistAnimator(): RecyclerView.ItemAnimator {
        // 仅创建页面实例承载原有内部动画器，不启动页面或数据库生命周期。
        val detail = Robolectric.buildActivity(DetailActivity::class.java).get()
        val type = DetailActivity::class.java.declaredClasses.single { it.simpleName == "ChecklistDragItemAnimator" }
        return type.getDeclaredConstructor(DetailActivity::class.java).apply { isAccessible = true }
            .newInstance(detail) as RecyclerView.ItemAnimator
    }

    @Test fun `连续反向换位不会遗留平移或更换媒体视图`() {
        val image = content.holder(images, 0)!!.itemView
        move(0, 3)
        assertSame(image, content.holder(images, 3)!!.itemView)
        assertEquals(0f, image.x, .5f)
        assertEquals(0f, image.y, .5f)
        move(3, 0)
        idle(400)
        assertSame(image, content.holder(images, 0)!!.itemView)
        assertEquals(0f, image.translationX, 0f)
        assertEquals(0f, image.translationY, 0f)
        assertEquals(1f, image.alpha, 0f)
        assertEquals(listOf("0image-a", "1video-b", "0image-c", "1video-d"), items)
    }

    @Test fun `中断换位动画后位置与透明度正确恢复`() {
        val image = content.holder(images, 0)!!.itemView
        move(0, 3)
        assertTrue(list.itemAnimator!!.isRunning)
        list.itemAnimator!!.endAnimations()
        idle(400)
        assertEquals(image.left.toFloat(), image.x, 0f)
        assertEquals(image.top.toFloat(), image.y, 0f)
        assertEquals(1f, image.alpha, 0f)
        assertFalse(list.itemAnimator!!.isRunning)
    }

    @Test fun `普通编辑区域重排增删与内容刷新不产生动画`() {
        val rows = RowsAdapter()
        content.adapter.addAdapter(1, rows)
        layout(); idle(400)
        val first = content.holder(rows, 0)!!.itemView
        val second = content.holder(rows, 1)!!.itemView
        val firstY = first.y
        val secondY = second.y
        rows.values.add(1, rows.values.removeAt(0))
        rows.notifyItemMoved(0, 1)
        layout()
        assertEquals(secondY, first.y, .5f)
        assertEquals(firstY, second.y, .5f)
        assertFalse(list.itemAnimator!!.isRunning)
        rows.values.add(1, "新增行")
        rows.notifyItemInserted(1)
        layout()
        assertEquals(1f, content.holder(rows, 1)!!.itemView.alpha, 0f)
        assertFalse(list.itemAnimator!!.isRunning)
        rows.values.removeAt(1)
        rows.notifyItemRemoved(1)
        layout()
        assertFalse(list.itemAnimator!!.isRunning)
        rows.notifyItemChanged(0)
        layout()
        assertSame(second, content.holder(rows, 0)!!.itemView)
        assertEquals(1f, second.alpha, 0f)
        assertFalse(list.itemAnimator!!.isRunning)
    }

    private fun move(from: Int, to: Int) {
        items.add(to, items.removeAt(from))
        content.update(images, null, false, null, 1)
        images.notifyItemMoved(from, to)
        layout()
    }

    private fun verifyReorder() {
        val image = content.holder(images, 0)!!.itemView
        val video = content.holder(images, 1)!!.itemView
        val imageX = image.x
        val videoX = video.x
        val samples = mutableListOf<Float>()
        val videoSamples = mutableListOf<Float>()
        image.animate().setUpdateListener { samples += image.x }
        video.animate().setUpdateListener { videoSamples += video.x }
        move(0, 1)

        assertSame(image, content.holder(images, 1)!!.itemView)
        assertSame(video, content.holder(images, 0)!!.itemView)
        assertEquals("换位首帧应保留原可见位置", imageX, image.x, .5f)
        assertEquals("让位视频首帧应保留原可见位置", videoX, video.x, .5f)
        idle(96)
        idle(400)
        assertTrue("图片应有中间帧：$samples", samples.any { it > imageX && it < videoX })
        assertTrue("视频应有中间帧：$videoSamples", videoSamples.any { it < videoX && it > imageX })
        assertEquals(videoX, image.x, .5f)
        assertEquals(imageX, video.x, .5f)
        assertEquals(0f, image.translationX, 0f)
        assertEquals(0f, video.translationX, 0f)
        assertEquals(1f, image.alpha, 0f)
        assertEquals(1f, video.alpha, 0f)
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun layout() {
        list.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        list.layout(0, 0, 400, 800)
    }

    private inner class RowsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        val values = mutableListOf("第一行", "第二行")
        override fun getItemCount() = values.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(View(activity).apply {
                layoutParams = RecyclerView.LayoutParams(-1, 32)
            }) { }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            holder.itemView.contentDescription = values[position]
        }
    }

    private inner class MediaAdapter : ImageAttachmentAdapter(activity, false, items, null, null, maxSpan = 2) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
            val tile = FrameLayout(activity).apply {
                id = R.id.fl_image_attachment
                layoutParams = GridLayoutManager.LayoutParams(200, 200)
            }
            for (id in intArrayOf(R.id.iv_image_attachment, R.id.iv_video_signal,
                R.id.iv_edit_image_attachment_appearance, R.id.iv_delete_image_attachment,
                R.id.iv_badge_live, R.id.iv_badge_spatial)) {
                tile.addView(ImageView(activity).apply { this.id = id })
            }
            for (id in intArrayOf(R.id.tv_badge_hdr, R.id.tv_badge_gif)) {
                tile.addView(View(activity).apply { this.id = id })
            }
            tile.addView(LinearLayout(activity).apply { id = R.id.ll_media_badges })
            tile.addView(object : ProgressBar(activity) {
                // 本测试不加载应用配色资源，跳过进度条的异步着色。
                override fun post(action: Runnable): Boolean = true
            }.apply { id = R.id.pb_image_attachment })
            return ImageViewHolder(tile)
        }
        override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
            holder.itemView.setBackgroundColor(if (items[position]!!.startsWith("0")) Color.RED else Color.BLUE)
        }
        override fun onViewAttachedToWindow(holder: ImageViewHolder) = Unit
        override fun onViewDetachedFromWindow(holder: ImageViewHolder) = Unit
        override fun onViewRecycled(holder: ImageViewHolder) = Unit
    }
}
