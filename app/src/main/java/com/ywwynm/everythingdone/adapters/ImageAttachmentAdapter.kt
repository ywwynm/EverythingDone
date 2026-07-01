@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.adapters

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.PorterDuff
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

import com.bumptech.glide.Glide
import android.graphics.drawable.Drawable
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.helpers.MediaCropBitmapRenderer
import com.ywwynm.everythingdone.helpers.MediaCropTransformation
import com.ywwynm.everythingdone.helpers.HdrImageDetector
import com.ywwynm.everythingdone.helpers.MotionPhotoCoverHelper
import com.ywwynm.everythingdone.helpers.MotionPhotoDetector
import com.ywwynm.everythingdone.helpers.VideoCoverPreviewManager
import com.ywwynm.everythingdone.model.DetailAttachmentMediaAppearance
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.views.GradientRippleDrawable
import com.bumptech.glide.signature.ObjectKey
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.max

/**
 * Created by ywwynm on 2015/9/23.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Adapter for image attachments(including image, video and doodle) of a thing.
 */
open class ImageAttachmentAdapter(
    context: Context?,
    editable: Boolean,
    items: List<String?>?,
    clickCallback: ClickCallback?,
    removeCallback: RemoveCallback?,
    appearanceCallback: AppearanceCallback? = null,
    maxSpan: Int = 1,
    detailAttachmentMediaAppearance: DetailAttachmentMediaAppearance =
        DetailAttachmentMediaAppearance.default()
) : RecyclerView.Adapter<ImageAttachmentAdapter.ImageViewHolder>() {

    private var mEditable: Boolean = editable

    private var mContext: Context? = context

    private var mInflater: LayoutInflater? = LayoutInflater.from(context)

    private var mItems: List<String?>? = items

    private var mMaxSpan: Int = max(1, maxSpan)

    private var mDetailAttachmentMediaAppearance: DetailAttachmentMediaAppearance =
        detailAttachmentMediaAppearance

    interface ClickCallback {
        fun onClick(v: View?, pos: Int)
    }
    private var mClickCallback: ClickCallback? = clickCallback

    interface RemoveCallback {
        fun onRemove(pos: Int)
    }
    private var mRemoveCallback: RemoveCallback? = removeCallback

    interface AppearanceCallback {
        fun onEditAppearance(pos: Int)
    }
    private var mAppearanceCallback: AppearanceCallback? = appearanceCallback

    private var mTakingScreenshot: Boolean = false

    /** 当前记事颜色：图片/视频附件容器与编辑/删除按钮的触摸 ripple 用它。 */
    private var mAccentBackground: ThingBackground = App.defaultAccentBackground

    open fun setAccentBackground(bg: ThingBackground?) {
        if (bg == null || bg == mAccentBackground) return
        mAccentBackground = bg
        notifyDataSetChanged()
    }

    open fun setItems(items: List<String?>?) {
        mItems = items
    }

    open fun getItems(): List<String?>? = mItems

    open fun setMaxSpan(maxSpan: Int) {
        mMaxSpan = max(1, maxSpan)
    }

    open fun setDetailAttachmentMediaAppearance(
        appearance: DetailAttachmentMediaAppearance
    ) {
        mDetailAttachmentMediaAppearance = appearance
    }

    open fun setTakingScreenshot(takingScreenshot: Boolean) {
        mTakingScreenshot = takingScreenshot
        notifyDataSetChanged()
    }

    open fun isCustomizedMode(): Boolean {
        val items = mItems ?: return false
        for (item in items) {
            if (mDetailAttachmentMediaAppearance.source(item) != null) return true
        }
        return false
    }

    open fun gridSpanCount(): Int {
        val count = itemCount
        if (count <= 0) return 1
        return min(count, mMaxSpan)
    }

    open fun isFullSpanPosition(position: Int): Boolean {
        if (!isCustomizedMode() || position != 0 || itemCount <= 0) return false
        if (itemCount == 1) return true
        val key = mItems?.getOrNull(position)
        return mDetailAttachmentMediaAppearance.source(key)?.fullSpanEnabled == true
    }

    open fun getItemTargetSize(position: Int): IntArray {
        if (!isCustomizedMode()) {
            return AttachmentHelper.calculateImageSize(mContext, itemCount)
        }
        val displayWidth = mContext!!.resources.displayMetrics.widthPixels
        val span = gridSpanCount()
        if (isFullSpanPosition(position)) {
            val ratio = getPresentationForPosition(position).targetAspectRatio
            return intArrayOf(displayWidth, max(1, (displayWidth / ratio).roundToInt()))
        }
        val itemWidth = max(1, displayWidth / span)
        return intArrayOf(itemWidth, itemWidth)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        return ImageViewHolder(
            mInflater!!.inflate(R.layout.attachment_image, parent, false)
        )
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        val typePathName = mItems!![position]
        val pathName = typePathName!!.substring(1, typePathName.length)
        holder.pbLoading!!.visibility = View.VISIBLE

        val size = getItemTargetSize(position)
        val params = holder.itemView.layoutParams as GridLayoutManager.LayoutParams
        params.width  = size[0]
        params.height = size[1]
        holder.itemView.layoutParams = params

        applyAttachmentRipples(holder)

        val type = if (typePathName[0] == '0') AttachmentHelper.IMAGE else AttachmentHelper.VIDEO
        if (type == AttachmentHelper.IMAGE) {
            holder.ivImage!!.contentDescription =
                mContext!!.getString(R.string.cd_image_attachment)
            holder.ivDelete!!.contentDescription =
                mContext!!.getString(R.string.cd_delete_image_attachment)
            holder.ivEditAppearance!!.contentDescription =
                mContext!!.getString(R.string.cd_edit_image_attachment_appearance)
            holder.ivVideoSignal!!.visibility = View.GONE
        } else {
            holder.ivImage!!.contentDescription =
                mContext!!.getString(R.string.cd_video_attachment)
            holder.ivDelete!!.contentDescription =
                mContext!!.getString(R.string.cd_delete_video_attachment)
            holder.ivEditAppearance!!.contentDescription =
                mContext!!.getString(R.string.cd_edit_video_attachment_appearance)
            holder.ivVideoSignal!!.visibility = View.VISIBLE
        }

        updateMediaBadges(holder, typePathName, pathName, type)

        val imageView = holder.ivImage!!
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        imageView.imageMatrix = null
        val animated = type == AttachmentHelper.IMAGE &&
                AttachmentHelper.isAnimatedImageCandidate(pathName)
        val motionCandidate = type == AttachmentHelper.IMAGE && !animated &&
                AttachmentHelper.isMotionPhotoCandidate(pathName)
        val sourceAppearance = mDetailAttachmentMediaAppearance.source(typePathName)
        val customized = isCustomizedMode()
        val presentation = if (customized) getPresentationForPosition(position) else null
        val videoFrameMs = sourceAppearance?.videoFrameMs
        val loadKey = if (customized) {
            getDetailAttachmentImageLoadKey(
                pathName,
                size[0],
                size[1],
                videoFrameMs,
                presentation?.crop
            )
        } else {
            null
        }
        if (customized && loadKey != null && presentation != null) {
            imageView.setTag(
                R.id.tag_detail_attachment_image_render_request,
                DetailAttachmentRenderRequest(loadKey, size[0], size[1], presentation.crop)
            )
            if (imageView.getTag(R.id.tag_detail_attachment_image_load_key) == loadKey &&
                imageView.drawable != null
            ) {
                holder.pbLoading!!.visibility = View.GONE
                imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                imageView.imageMatrix = null
                updateOverlayVisibility(holder)
                return
            }
            imageView.setTag(R.id.tag_detail_attachment_image_load_key, loadKey)
        } else {
            imageView.setTag(R.id.tag_detail_attachment_image_load_key, null)
            imageView.setTag(R.id.tag_detail_attachment_image_render_request, null)
        }
        var request = Glide.with(imageView.context)
            .load(pathName)
        if (type == AttachmentHelper.VIDEO && videoFrameMs != null) {
            request = request.apply(
                RequestOptions.frameOf(videoFrameMs * 1000L)
            )
        }
        if (customized) {
            request = request.override(size[0], size[1])
            request = if (animated && presentation != null) {
                // Animated Image: crop each frame so it animates while keeping the
                // user's crop, instead of baking a single static frame. See ADR-0007.
                request.transform(
                    MediaCropTransformation(
                        size[0], size[1], getDetailAttachmentBitmapCrop(presentation.crop)
                    )
                )
            } else {
                request.dontTransform().disallowHardwareConfig().dontAnimate()
            }
            if (loadKey != null) {
                request = request.signature(ObjectKey(loadKey))
            }
        } else {
            request = request.centerCrop()
        }
        request
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?, model: Any?, target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    holder.pbLoading!!.visibility = View.GONE
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable, model: Any, target: Target<Drawable>?,
                    dataSource: DataSource, isFirstResource: Boolean
                ): Boolean {
                    updateOverlayVisibility(holder)
                    holder.pbLoading!!.visibility = View.GONE
                    if (customized && loadKey != null && !animated) {
                        val renderRequest = imageView.getTag(
                            R.id.tag_detail_attachment_image_render_request
                        ) as? DetailAttachmentRenderRequest
                        if (renderRequest?.loadKey != loadKey) {
                            return true
                        }
                        val bakedBitmap = MediaCropBitmapRenderer.renderCrop(
                            resource,
                            renderRequest.targetW,
                            renderRequest.targetH,
                            getDetailAttachmentBitmapCrop(renderRequest.crop)
                        )
                        if (bakedBitmap != null) {
                            imageView.setImageBitmap(bakedBitmap)
                            imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                            imageView.imageMatrix = null
                            return true
                        }
                        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                        imageView.imageMatrix = null
                    }
                    return false
                }
            })
            .into(imageView)

        if (motionCandidate) {
            // 详情列表无条件播放：动态照片先显示静态主图，后台派生 GIF 就绪后换成动图（逐帧套用裁切）。
            val boundTypePathName = typePathName
            MotionPhotoCoverHelper.requestGif(imageView.context, pathName) { gif ->
                val pos = holder.bindingAdapterPosition
                // 回调可能在 Activity 已销毁后才到（生成耗时）；此时不能再起 Glide 加载，否则崩溃。
                if (isImageViewUsable(imageView) &&
                    pos != RecyclerView.NO_POSITION && mItems?.getOrNull(pos) == boundTypePathName
                ) {
                    loadDetailMotionGif(
                        holder, imageView, gif.absolutePath, size, customized, presentation, loadKey
                    )
                }
            }
        }

        updateOverlayVisibility(holder)
    }

    /** 把 Motion Photo 的派生 GIF 加载为动图（定制模式逐帧套用裁切，否则 centerCrop）；失败则自愈并保留静态主图。 */
    private fun loadDetailMotionGif(
        holder: ImageViewHolder,
        imageView: ImageView,
        gifPath: String,
        size: IntArray,
        customized: Boolean,
        presentation: DetailAttachmentMediaAppearance.MediaPresentationAppearance?,
        loadKey: String?
    ) {
        if (!isImageViewUsable(imageView)) return
        var request = Glide.with(imageView.context).load(gifPath)
        request = if (customized && presentation != null) {
            val r = request.override(size[0], size[1])
                .transform(
                    MediaCropTransformation(
                        size[0], size[1], getDetailAttachmentBitmapCrop(presentation.crop)
                    )
                )
            if (loadKey != null) r.signature(ObjectKey("$loadKey:mp")) else r
        } else {
            request.centerCrop()
        }
        request
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?, model: Any?, target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    VideoCoverPreviewManager.onPreviewLoadFailed(File(gifPath))
                    return true
                }

                override fun onResourceReady(
                    resource: Drawable, model: Any, target: Target<Drawable>?,
                    dataSource: DataSource, isFirstResource: Boolean
                ): Boolean {
                    holder.pbLoading!!.visibility = View.GONE
                    imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                    imageView.imageMatrix = null
                    return false
                }
            })
            .into(imageView)
    }

    /** 异步回调可能在 Activity 已销毁 / View 已脱离窗口后才到；此时不能再起 Glide 加载。 */
    private fun isImageViewUsable(imageView: ImageView): Boolean {
        if (!imageView.isAttachedToWindow) return false
        var ctx: Context? = imageView.context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return !ctx.isDestroyed && !ctx.isFinishing
            ctx = ctx.baseContext
        }
        return true
    }

    // 左下角媒体标识（实况 / HDR）的后台检测：单线程、低优先级，结果按文件签名缓存。
    private val mBadgeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MediaBadgeDetect").apply { priority = Thread.MIN_PRIORITY }
    }

    /** 左下角媒体标识：GIF（同步扩展名）、实况（异步检测）、HDR（异步解码查增益图）；均可叠加显示。 */
    private fun updateMediaBadges(
        holder: ImageViewHolder, typePathName: String, pathName: String, type: Int
    ) {
        holder.ivBadgeLive?.visibility = View.GONE
        holder.tvBadgeHdr?.visibility = View.GONE
        holder.tvBadgeGif?.visibility = View.GONE

        val isImage = type == AttachmentHelper.IMAGE

        // GIF（同步）
        holder.tvBadgeGif?.visibility =
            if (isImage && AttachmentHelper.isAnimatedImageCandidate(pathName)) View.VISIBLE else View.GONE

        // 实况（命中缓存则同步，否则后台检测）
        if (isImage && AttachmentHelper.isMotionPhotoCandidate(pathName)) {
            val cached = MotionPhotoDetector.peekCached(pathName)
            if (cached != null) {
                holder.ivBadgeLive?.visibility = if (cached.isMotionPhoto) View.VISIBLE else View.GONE
            } else {
                mBadgeExecutor.submit {
                    val isMotion = MotionPhotoDetector.detect(pathName).isMotionPhoto
                    postBadgeUpdate(holder, typePathName) {
                        holder.ivBadgeLive?.visibility = if (isMotion) View.VISIBLE else View.GONE
                    }
                }
            }
        }

        // HDR（命中缓存则同步，否则后台解码判断；仅 API 34+ 候选）
        if (isImage && HdrImageDetector.isCandidate(pathName)) {
            val cachedHdr = HdrImageDetector.peekCached(pathName)
            if (cachedHdr != null) {
                holder.tvBadgeHdr?.visibility = if (cachedHdr) View.VISIBLE else View.GONE
            } else {
                mBadgeExecutor.submit {
                    val hdr = HdrImageDetector.detect(pathName)
                    postBadgeUpdate(holder, typePathName) {
                        holder.tvBadgeHdr?.visibility = if (hdr) View.VISIBLE else View.GONE
                    }
                }
            }
        }

        updateBadgeContainerVisibility(holder)
    }

    /** 异步徽标结果回到主线程：守卫回收（位置 + item 匹配），应用后刷新容器可见性。 */
    private fun postBadgeUpdate(holder: ImageViewHolder, boundTypePathName: String, apply: () -> Unit) {
        val iv = holder.ivImage ?: return
        iv.post {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION && mItems?.getOrNull(pos) == boundTypePathName) {
                apply()
                updateBadgeContainerVisibility(holder)
            }
        }
    }

    private fun updateBadgeContainerVisibility(holder: ImageViewHolder) {
        val any = holder.ivBadgeLive?.visibility == View.VISIBLE ||
                holder.tvBadgeHdr?.visibility == View.VISIBLE ||
                holder.tvBadgeGif?.visibility == View.VISIBLE
        holder.llMediaBadges?.visibility = if (any) View.VISIBLE else View.GONE
    }

    private fun getPresentationForPosition(
        position: Int
    ): DetailAttachmentMediaAppearance.MediaPresentationAppearance {
        val key = mItems?.getOrNull(position)
        val source = mDetailAttachmentMediaAppearance.source(key)
        val presentationKey = if (isFullSpanPosition(position)) {
            DetailAttachmentMediaAppearance.PRESENTATION_FULL_SPAN
        } else {
            DetailAttachmentMediaAppearance.PRESENTATION_GRID
        }
        return source?.presentationOrDefault(presentationKey)
            ?: DetailAttachmentMediaAppearance.MediaPresentationAppearance.defaultFor(
                presentationKey
            )
    }

    private data class DetailAttachmentRenderRequest(
        val loadKey: String,
        val targetW: Int,
        val targetH: Int,
        val crop: DetailAttachmentMediaAppearance.DetailMediaCrop
    )

    private fun getDetailAttachmentImageLoadKey(
        pathName: String,
        targetW: Int,
        targetH: Int,
        videoFrameMs: Long?,
        crop: DetailAttachmentMediaAppearance.DetailMediaCrop?
    ): String {
        val cropKey = crop?.let { getDetailAttachmentBitmapCrop(it).fingerprint() } ?: "none"
        return "$pathName|$targetW|$targetH|${videoFrameMs ?: -1L}|crop|$cropKey"
    }

    private fun updateOverlayVisibility(holder: ImageViewHolder) {
        val visible = !mTakingScreenshot && mEditable
        holder.ivDelete!!.visibility = if (visible) View.VISIBLE else View.GONE
        holder.ivEditAppearance!!.visibility =
            if (visible && mAppearanceCallback != null) View.VISIBLE else View.GONE
    }

    private fun getDetailAttachmentBitmapCrop(
        crop: DetailAttachmentMediaAppearance.DetailMediaCrop
    ): MediaCropBitmapRenderer.Crop {
        return MediaCropBitmapRenderer.Crop(
            centerX = crop.centerX,
            centerY = crop.centerY,
            userScale = crop.scale
        )
    }

    override fun getItemCount(): Int = mItems!!.size

    override fun onViewRecycled(holder: ImageViewHolder) {
        super.onViewRecycled(holder)
        (holder.fl?.foreground as? GradientRippleDrawable)?.stopAnimations()
        (holder.ivEditAppearance?.background as? GradientRippleDrawable)?.stopAnimations()
        (holder.ivDelete?.background as? GradientRippleDrawable)?.stopAnimations()
    }

    /** 图片/视频附件容器（矩形）与编辑外观/删除按钮（圆形）的触摸 ripple 改为当前记事颜色。 */
    private fun applyAttachmentRipples(holder: ImageViewHolder) {
        holder.fl!!.foreground =
            GradientRippleDrawable(mAccentBackground, shapeOval = false, cornerRadiusPx = 0f)
        holder.ivEditAppearance!!.background =
            GradientRippleDrawable(mAccentBackground, shapeOval = true)
        holder.ivDelete!!.background =
            GradientRippleDrawable(mAccentBackground, shapeOval = true)
    }

    inner class ImageViewHolder internal constructor(itemView: View?) : BaseViewHolder(itemView) {

        val fl: FrameLayout? = f(R.id.fl_image_attachment)
        val ivImage: ImageView? = f(R.id.iv_image_attachment)
        val ivVideoSignal: ImageView? = f(R.id.iv_video_signal)
        val ivEditAppearance: ImageView? = f(R.id.iv_edit_image_attachment_appearance)
        val ivDelete: ImageView? = f(R.id.iv_delete_image_attachment)
        val pbLoading: ProgressBar? = f(R.id.pb_image_attachment)
        val llMediaBadges: LinearLayout? = f(R.id.ll_media_badges)
        val ivBadgeLive: ImageView? = f(R.id.iv_badge_live)
        val tvBadgeHdr: View? = f(R.id.tv_badge_hdr)
        val tvBadgeGif: View? = f(R.id.tv_badge_gif)

        init {
            pbLoading!!.post {
                BackgroundUtil.applyProgressBarGradient(
                    pbLoading!!, App.defaultAccentBackground
                )
            }

            fl!!.setOnClickListener { v ->
                if (mClickCallback != null) {
                    mClickCallback!!.onClick(v, adapterPosition)
                }
            }

            if (mEditable) {
                ivDelete!!.visibility = View.VISIBLE
                ivDelete.setOnClickListener {
                    if (mRemoveCallback != null) {
                        mRemoveCallback!!.onRemove(adapterPosition)
                    }
                }
                ivEditAppearance!!.visibility =
                    if (mAppearanceCallback != null) View.VISIBLE else View.GONE
                ivEditAppearance.setOnClickListener {
                    if (mAppearanceCallback != null) {
                        mAppearanceCallback!!.onEditAppearance(adapterPosition)
                    }
                }
            } else {
                ivDelete!!.visibility = View.GONE
                ivEditAppearance!!.visibility = View.GONE
            }
        }
    }
}
