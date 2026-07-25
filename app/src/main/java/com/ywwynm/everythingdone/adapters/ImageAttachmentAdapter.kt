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

import androidx.vectordrawable.graphics.drawable.Animatable2Compat
import com.bumptech.glide.Glide
import android.graphics.drawable.Drawable
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.DrawableImageViewTarget
import com.bumptech.glide.request.target.Target
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.helpers.DetailAttachmentPlaybackController
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

    private var mRecyclerView: RecyclerView? = null

    /** Detail Autoplay 的调度器；为空时（如未接入的界面）一律停在静态代表帧。 */
    private var mPlaybackController: DetailAttachmentPlaybackController? = null

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

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        mRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        if (mRecyclerView === recyclerView) {
            mRecyclerView = null
        }
    }

    open fun setPlaybackController(controller: DetailAttachmentPlaybackController?) {
        mPlaybackController = controller
    }

    /**
     * 由 [DetailAttachmentPlaybackController] 在某项的播放状态变化时调用：只重跑图片加载，
     * 不动布局参数、ripple 与徽标。找不到 ViewHolder（该项尚未布局）时静默忽略。
     */
    open fun refreshPlayback(position: Int) {
        val rv = mRecyclerView ?: return
        val holder = rv.findViewHolderForAdapterPosition(position) as? ImageViewHolder ?: return
        bindAttachmentImage(holder, position)
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

        bindAttachmentImage(holder, position)

        updateOverlayVisibility(holder)
    }

    /**
     * 详情附件的图片加载：按 **Detail Autoplay** 的调度结果在"静态代表帧"与"逐帧播放"
     * 两条路之间分流。见 ADR-0017。
     *
     * 静态代表帧（不播时显示的那一帧）按内容分别是：Animated Image 的首帧、视频的
     * Thing Card Video Frame、Motion Photo 的**高画质静态主图**——最后一条是 Motion Photo
     * 独有的，它的派生 GIF 首帧来自内嵌视频起点，不是主图，所以停播时必须真的切回主图。
     */
    private fun bindAttachmentImage(holder: ImageViewHolder, position: Int) {
        val typePathName = mItems?.getOrNull(position) ?: return
        val pathName = typePathName.substring(1, typePathName.length)
        val type = if (typePathName[0] == '0') AttachmentHelper.IMAGE else AttachmentHelper.VIDEO
        val imageView = holder.ivImage ?: return
        val size = getItemTargetSize(position)

        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        imageView.imageMatrix = null

        val animated = type == AttachmentHelper.IMAGE &&
                AttachmentHelper.isAnimatedImageCandidate(pathName)
        val motionCandidate = type == AttachmentHelper.IMAGE && !animated &&
                AttachmentHelper.isMotionPhotoCandidate(pathName)
        val videoCandidate = type == AttachmentHelper.VIDEO
        val sourceAppearance = mDetailAttachmentMediaAppearance.source(typePathName)
        val customized = isCustomizedMode()
        val presentation = if (customized) getPresentationForPosition(position) else null
        val videoFrameMs = sourceAppearance?.videoFrameMs

        val controller = mPlaybackController
        // 分享截图期间一律冻结到静态代表帧，否则截图会抓到任意一帧。
        val play = !mTakingScreenshot && controller?.shouldPlay(position) == true
        val loop = play && controller?.shouldLoop(position) == true

        // 视频 / Motion Photo 播的是派生 GIF：就绪才播，未就绪先显示静态代表帧并后台请求。
        var derivedGifPath: String? = null
        if ((videoCandidate || motionCandidate) && !mTakingScreenshot) {
            val context = imageView.context
            val ready = if (videoCandidate) {
                VideoCoverPreviewManager.getReadyPreview(context, pathName, videoFrameMs)
            } else {
                MotionPhotoCoverHelper.getReadyGif(context, pathName)
            }
            if (play && ready != null) {
                derivedGifPath = ready.absolutePath
            } else if (ready == null && controller?.wantsDerivedPreview(position) == true) {
                requestDerivedGif(
                    holder, imageView, typePathName, pathName, videoCandidate, videoFrameMs
                )
            }
        }

        val animatedPlayback = derivedGifPath != null || (play && animated)
        val loadSource = derivedGifPath ?: pathName

        // 把播放态折进 loadKey：静/动切换时 key 随之变化，绕过同 key 短路与 Glide 缓存复用。
        val loadKey = if (customized) {
            getDetailAttachmentImageLoadKey(
                pathName, size[0], size[1], videoFrameMs, presentation?.crop
            ) + playbackKeySuffix(animatedPlayback, derivedGifPath)
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
                applyGifPlaybackState(
                    imageView, imageView.drawable, position, animatedPlayback, loop
                )
                updateOverlayVisibility(holder)
                return
            }
            imageView.setTag(R.id.tag_detail_attachment_image_load_key, loadKey)
        } else {
            imageView.setTag(R.id.tag_detail_attachment_image_load_key, null)
            imageView.setTag(R.id.tag_detail_attachment_image_render_request, null)
        }

        stopExistingGif(imageView)

        var request = Glide.with(imageView.context)
            .load(loadSource)
        if (videoCandidate && derivedGifPath == null && videoFrameMs != null) {
            request = request.apply(
                RequestOptions.frameOf(videoFrameMs * 1000L)
            )
        }
        if (customized) {
            request = request.override(size[0], size[1])
            request = if (animatedPlayback && presentation != null) {
                // 逐帧套用用户的裁切，使其在保持裁切的同时真的动起来，而不是烘焙成单帧。见 ADR-0007。
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
            if (!animatedPlayback) {
                // 默认模式下 GIF 此前是无条件动的；不播时必须显式停在首帧。
                request = request.dontAnimate()
            }
        }
        val boundDerivedGifPath = derivedGifPath
        request
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?, model: Any?, target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    holder.pbLoading!!.visibility = View.GONE
                    if (boundDerivedGifPath != null) {
                        // 坏派生 GIF 自愈（每 key 只删一次，不会死循环）。
                        VideoCoverPreviewManager.onPreviewLoadFailed(File(boundDerivedGifPath))
                    }
                    // 加载失败同样等不到 onAnimationEnd，放行队列。
                    if (animatedPlayback) notifyPlaybackUnavailable(imageView, position)
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable, model: Any, target: Target<Drawable>?,
                    dataSource: DataSource, isFirstResource: Boolean
                ): Boolean {
                    updateOverlayVisibility(holder)
                    holder.pbLoading!!.visibility = View.GONE
                    if (customized && loadKey != null && !animatedPlayback) {
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
                    applyGifPlaybackState(imageView, resource, position, animatedPlayback, loop)
                    return false
                }
            })
            .into(KeepCurrentImageTarget(imageView))
    }

    /**
     * 静/动两条加载路径之间切换时不清空当前画面。
     *
     * Glide 起新请求时会先 `onLoadCleared` / `onLoadStarted`，两者都把 ImageView 置成
     * placeholder（这里为 null），于是「逐一播放」里上一个刚停、下一个开播的那一瞬间会闪
     * 一下空白。这里把这两个回调改成不动画面，旧图一直留到新资源就绪才被替换。
     */
    private class KeepCurrentImageTarget(view: ImageView) : DrawableImageViewTarget(view) {

        override fun onLoadStarted(placeholder: Drawable?) {
            // 刻意不调用 super：保留当前画面。
        }

        override fun onLoadCleared(placeholder: Drawable?) {
            // 同上。ViewHolder 回收时由 onViewRecycled 停掉动图，重新绑定会覆盖画面。
        }
    }

    /**
     * 该位置**此刻**是否真的能播起来。逐一播放的队列必须靠它筛选：静态图片被排进队列后
     * 永远等不到"播完"的回调，队列会就此卡死（其后的动图再也轮不到）。
     *
     * 视频与 Motion Photo 只有在派生 GIF 已就绪时才算能播；未就绪的先跳过，等
     * [DetailAttachmentPlaybackController.onDerivedPreviewReady] 重新入队。注意
     * Motion Photo 的候选判定只看扩展名，普通 JPEG 也是候选，所以必须以派生 GIF
     * 是否真的存在为准，不能只看候选。
     */
    open fun isPlayableNow(position: Int): Boolean {
        val typePathName = mItems?.getOrNull(position) ?: return false
        val pathName = typePathName.substring(1, typePathName.length)
        val context = mContext ?: return false
        if (typePathName[0] == '1') {
            return VideoCoverPreviewManager.getReadyPreview(
                context, pathName, videoFrameMsOf(position)
            ) != null
        }
        if (AttachmentHelper.isAnimatedImageCandidate(pathName)) return true
        if (!AttachmentHelper.isMotionPhotoCandidate(pathName)) return false
        return MotionPhotoCoverHelper.getReadyGif(context, pathName) != null
    }

    private fun videoFrameMsOf(position: Int): Long? =
        mDetailAttachmentMediaAppearance.source(mItems?.getOrNull(position))?.videoFrameMs

    /** 静态与动图两条路走不同的 Glide 缓存条目，否则切换时会拿回上一次的产物。 */
    private fun playbackKeySuffix(animatedPlayback: Boolean, derivedGifPath: String?): String {
        if (!animatedPlayback) return "|static"
        val name = derivedGifPath?.let { File(it).name } ?: "self"
        return "|play|$name"
    }

    /**
     * 后台生成派生 GIF；就绪后若该 ViewHolder 仍绑着同一附件，就整段重跑绑定（此时
     * [VideoCoverPreviewManager.getReadyPreview] / [MotionPhotoCoverHelper.getReadyGif]
     * 会命中，自然分流到动图分支）。
     */
    private fun requestDerivedGif(
        holder: ImageViewHolder,
        imageView: ImageView,
        boundTypePathName: String,
        pathName: String,
        video: Boolean,
        videoFrameMs: Long?
    ) {
        val context = imageView.context
        val onReady: (File) -> Unit = {
            val pos = holder.bindingAdapterPosition
            // 回调可能在 Activity 已销毁后才到（生成耗时）；此时不能再起 Glide 加载，否则崩溃。
            if (isImageViewUsable(imageView) &&
                pos != RecyclerView.NO_POSITION && mItems?.getOrNull(pos) == boundTypePathName
            ) {
                bindAttachmentImage(holder, pos)
                // 生成期间它被判为"此刻不能播"而落选（逐一档甚至已被移出队列），
                // 现在能播了，交给调度器决定是否补播。
                mPlaybackController?.onDerivedPreviewReady(pos)
            }
        }
        if (video) {
            VideoCoverPreviewManager.requestPreview(context, pathName, videoFrameMs, onReady)
        } else {
            MotionPhotoCoverHelper.requestGif(context, pathName, onReady)
        }
    }

    /**
     * 按调度结果设置 GifDrawable 的循环次数并起播。播一轮的档位靠
     * [Animatable2Compat.AnimationCallback.onAnimationEnd] 回到静态代表帧。
     */
    private fun applyGifPlaybackState(
        imageView: ImageView, drawable: Drawable?, position: Int,
        animatedPlayback: Boolean, loop: Boolean
    ) {
        val gif = drawable as? GifDrawable
        if (gif == null) {
            // 说好要播、拿回来的却不是动图（解码退化成单帧、或 isPlayableNow 与实际不符）。
            // 此时永远等不到 onAnimationEnd，必须主动放行，否则逐一播放的队列会卡在这一项。
            if (animatedPlayback) notifyPlaybackUnavailable(imageView, position)
            return
        }
        gif.clearAnimationCallbacks()
        if (!animatedPlayback) {
            gif.stop()
            return
        }
        if (loop) {
            gif.setLoopCount(GifDrawable.LOOP_FOREVER)
            gif.start()
            return
        }
        gif.setLoopCount(1)
        val boundTypePathName = mItems?.getOrNull(position)
        gif.registerAnimationCallback(object : Animatable2Compat.AnimationCallback() {
            override fun onAnimationEnd(drawable: Drawable?) {
                if (mItems?.getOrNull(position) != boundTypePathName) return
                mPlaybackController?.onPlaybackFinished(position)
            }
        })
        // 必须 startFromFirstFrame：内存缓存里回来的 GifDrawable 可能停在上一次的中间帧。
        gif.startFromFirstFrame()
    }

    /**
     * 告诉调度器"这一项其实播不起来"，让它当作已播完放行。必须 post 出去：这些调用点都在
     * Glide 的回调里，同步回调会触发对同一个 ImageView 的重新绑定，而外层回调返回后
     * Glide 还要把**旧**资源塞进这个 View，把刚绑好的画面顶掉。
     */
    private fun notifyPlaybackUnavailable(imageView: ImageView, position: Int) {
        val controller = mPlaybackController ?: return
        val boundTypePathName = mItems?.getOrNull(position)
        imageView.post {
            if (mItems?.getOrNull(position) == boundTypePathName) {
                controller.onPlaybackFinished(position)
            }
        }
    }

    private fun stopExistingGif(imageView: ImageView) {
        val current = imageView.drawable
        if (current is GifDrawable) {
            current.clearAnimationCallbacks()
            current.stop()
        }
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
        holder.ivImage?.let { stopExistingGif(it) }
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
