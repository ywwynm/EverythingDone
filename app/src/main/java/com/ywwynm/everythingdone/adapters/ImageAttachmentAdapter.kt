@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.adapters

import android.content.Context
import android.graphics.PorterDuff
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar

import com.bumptech.glide.Glide
import android.graphics.drawable.Drawable
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.helpers.MediaCropBitmapRenderer
import com.ywwynm.everythingdone.model.DetailAttachmentMediaAppearance
import com.bumptech.glide.signature.ObjectKey
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

        val imageView = holder.ivImage!!
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        imageView.imageMatrix = null
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
            request = request
                .override(size[0], size[1])
                .dontTransform()
                .disallowHardwareConfig()
                .dontAnimate()
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
                    if (customized && loadKey != null) {
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

        updateOverlayVisibility(holder)
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

    inner class ImageViewHolder internal constructor(itemView: View?) : BaseViewHolder(itemView) {

        val fl: FrameLayout? = f(R.id.fl_image_attachment)
        val ivImage: ImageView? = f(R.id.iv_image_attachment)
        val ivVideoSignal: ImageView? = f(R.id.iv_video_signal)
        val ivEditAppearance: ImageView? = f(R.id.iv_edit_image_attachment_appearance)
        val ivDelete: ImageView? = f(R.id.iv_delete_image_attachment)
        val pbLoading: ProgressBar? = f(R.id.pb_image_attachment)

        init {
            val pbColor = ContextCompat.getColor(mContext!!, R.color.app_accent)
            pbLoading!!.indeterminateDrawable.setColorFilter(pbColor, PorterDuff.Mode.SRC_IN)

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
