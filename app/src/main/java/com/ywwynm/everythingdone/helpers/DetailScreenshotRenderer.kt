package com.ywwynm.everythingdone.helpers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.adapters.AudioAttachmentAdapter
import com.ywwynm.everythingdone.adapters.CheckListAdapter
import com.ywwynm.everythingdone.adapters.ImageAttachmentAdapter
import com.ywwynm.everythingdone.model.DetailAttachmentMediaAppearance
import com.ywwynm.everythingdone.model.ThingBackground
import kotlin.math.max

/** 长图使用独立、只读的离屏副本，避免导出时改动正在编辑的模型、焦点和回收状态。 */
class DetailScreenshotRenderer(
    private val activity: FragmentActivity,
    content: DetailContentLayout,
    private val background: ThingBackground,
    editable: Boolean,
    checklist: CheckListAdapter?,
    images: List<String?>?,
    appearance: DetailAttachmentMediaAppearance,
    imageColumns: Int,
    audios: List<String?>?,
    audioColumns: Int,
    autoLink: Boolean
) {
    val root = LayoutInflater.from(activity).inflate(R.layout.detail_screenshot, null) as NestedScrollView
    val typeInfo: View = root.findViewById(R.id.ll_type_info_screenshot)
    private val imageList: RecyclerView = root.findViewById(R.id.rv_image_attachment)
    private val audioList: RecyclerView = root.findViewById(R.id.rv_audio_attachment)
    private val checkList: RecyclerView = root.findViewById(R.id.rv_check_list)
    private val width = content.recyclerView.width
    private val handler = Handler(Looper.getMainLooper())

    init {
        for (id in listOf(R.id.et_title, R.id.et_content, R.id.ll_move_checklist,
            R.id.ll_type_info_screenshot, R.id.tv_update_time, R.id.tv_finish_time, R.id.tv_thing_folder_path)) {
            copyAppearance(content.view(id), root.findViewById(id))
        }
        root.isVerticalScrollBarEnabled = false
        root.isHorizontalScrollBarEnabled = false
        val checkAdapter = checklist?.let {
            CheckListAdapter(activity, CheckListAdapter.EDITTEXT_UNEDITABLE, ArrayList(it.getItems()!!)).apply {
                setThingColor(background.representativeColor())
                setShouldAutoLink(autoLink)
                setExpanded(it.isExpanded())
            }
        }
        if (checkAdapter != null) {
            checkList.visibility = View.VISIBLE
            checkList.layoutManager = object : LinearLayoutManager(activity) {
                override fun canScrollVertically() = false
            }
            checkList.adapter = checkAdapter
        }
        val imageAdapter = images?.takeIf { it.isNotEmpty() }?.let {
            ImageAttachmentAdapter(activity, false, ArrayList(it), null, null, null, imageColumns, appearance).apply {
                setAccentBackground(background)
                setTakingScreenshot(true)
            }
        }
        if (imageAdapter != null) {
            imageList.visibility = View.VISIBLE
            val columns = imageAdapter.gridSpanCount()
            imageList.layoutManager = GridLayoutManager(activity, columns).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int) = if (imageAdapter.isFullSpanPosition(position)) columns else 1
                }
            }
            imageList.adapter = imageAdapter
            var height = 0
            var first = 0
            if (imageAdapter.isFullSpanPosition(0)) {
                height = imageAdapter.getItemTargetSize(0)[1]
                first = 1
            }
            if (imageAdapter.itemCount > first) {
                height += ((imageAdapter.itemCount - first + columns - 1) / columns) * imageAdapter.getItemTargetSize(first)[1]
            }
            imageList.layoutParams.height = max(1, height)
        }
        val audioAdapter = audios?.takeIf { it.isNotEmpty() }?.let {
            AudioAttachmentAdapter(activity, background.representativeColor(), false, ArrayList(it), null).apply {
                setAccentBackground(background)
                setTakingScreenshot(true)
            }
        }
        if (audioAdapter != null) {
            audioList.visibility = View.VISIBLE
            audioList.layoutManager = GridLayoutManager(activity, audioColumns)
            audioList.adapter = audioAdapter
            AttachmentHelper.setAudioRecyclerViewHeight(audioList, audioAdapter.itemCount, audioColumns)
        }
        // 复用原截图的隐藏控制项、空标题和边距规则；只修改副本，无需恢复页面。
        ScreenshotHelper.updateThingUiBeforeScreenshot(editable,
            root.findViewById(R.id.et_title), root.findViewById(R.id.et_content),
            checkList, checkAdapter, root.findViewById(R.id.ll_move_checklist),
            imageList, imageAdapter, audioList, audioAdapter)
    }

    fun render(callback: (Bitmap?) -> Unit) {
        val started = SystemClock.uptimeMillis()
        fun finish(bitmap: Bitmap?) {
            // 清理离屏 Glide target，避免导出副本继续持有媒体解码结果。
            fun clear(view: View) {
                if (view is ImageView) Glide.with(activity.applicationContext).clear(view)
                if (view is ViewGroup) for (i in 0 until view.childCount) clear(view.getChildAt(i))
            }
            clear(root)
            imageList.adapter = null
            audioList.adapter = null
            checkList.adapter = null
            callback(bitmap)
        }
        fun renderWhenReady() {
            if (activity.isFinishing || activity.isDestroyed) { finish(null); return }
            try {
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                root.layout(0, 0, width, root.measuredHeight)
                val pending = (0 until imageList.childCount).any {
                    imageList.getChildAt(it).findViewById<ProgressBar>(R.id.pb_image_attachment)?.visibility == View.VISIBLE
                }
                if (pending) {
                    if (SystemClock.uptimeMillis() - started < 8000) {
                        handler.postDelayed({ renderWhenReady() }, 50)
                        return
                    }
                    finish(null)
                    return
                }
                if ((0 until imageList.childCount).any {
                    imageList.getChildAt(it).findViewById<ImageView>(R.id.iv_image_attachment)?.drawable == null
                }) {
                    finish(null)
                    return
                }
                val body = root.getChildAt(0)
                // Bitmap 的分配字节数使用 Int；先以 Long 校验，避免超大长图触发溢出或无意义的分配。
                if (width <= 0 || width.toLong() * max(1, body.height) * 4 > Int.MAX_VALUE) {
                    finish(null)
                    return
                }
                val bitmap = Bitmap.createBitmap(width, max(1, body.height), Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(background.representativeColor())
                body.draw(canvas)
                finish(bitmap)
            } catch (error: Exception) {
                android.util.Log.e("DetailScreenshot", "长图渲染失败", error)
                finish(null)
            } catch (error: OutOfMemoryError) {
                android.util.Log.e("DetailScreenshot", "长图内存不足", error)
                finish(null)
            }
        }
        handler.post { renderWhenReady() }
    }

    private fun copyAppearance(source: View, target: View) {
        target.visibility = source.visibility
        target.alpha = source.alpha
        target.setPadding(source.paddingLeft, source.paddingTop, source.paddingRight, source.paddingBottom)
        if (source is TextView && target is TextView) {
            target.text = source.text
            target.hint = source.hint
            target.setTextColor(source.textColors)
            target.setHintTextColor(source.hintTextColors)
            target.setLinkTextColor(source.linkTextColors)
            target.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, source.textSize)
            target.typeface = source.typeface
            target.paintFlags = source.paintFlags
            target.paint.textSkewX = source.paint.textSkewX
            target.setLineSpacing(source.lineSpacingExtra, source.lineSpacingMultiplier)
            target.compoundDrawableTintList = source.compoundDrawableTintList
            target.compoundDrawableTintMode = source.compoundDrawableTintMode
            val drawables = source.compoundDrawablesRelative
            val copies = drawables.map { copyDrawable(it) }
            target.setCompoundDrawablesRelative(copies[0], copies[1], copies[2], copies[3])
            if (target is EditText) {
                target.isCursorVisible = false
                target.isFocusable = false
            }
        }
        if (source is ImageView && target is ImageView) {
            target.imageTintList = source.imageTintList
            target.imageTintMode = source.imageTintMode
        }
        if (source is ViewGroup && target is ViewGroup) {
            for (i in 0 until minOf(source.childCount, target.childCount)) {
                copyAppearance(source.getChildAt(i), target.getChildAt(i))
            }
        }
    }

    private fun copyDrawable(source: Drawable?): Drawable? {
        source ?: return null
        val bounds = Rect(source.bounds)
        val copy = source.constantState?.newDrawable(activity.resources)?.mutate()
        if (copy != null) {
            copy.state = source.state
            copy.level = source.level
            copy.alpha = source.alpha
            copy.colorFilter = source.colorFilter
            copy.bounds = bounds
            return copy
        }
        // 文件夹等自绘图标没有 ConstantState；按当前实际尺寸保存独立像素，保留颜色和锁标记。
        if (bounds.isEmpty) return null
        val bitmap = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
        source.draw(canvas)
        return BitmapDrawable(activity.resources, bitmap).apply { this.bounds = bounds }
    }
}
