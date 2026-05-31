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
import com.bumptech.glide.request.target.Target
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.helpers.AttachmentHelper

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
    placementCallback: PlacementCallback?
) : RecyclerView.Adapter<ImageAttachmentAdapter.ImageViewHolder>() {

    private var mEditable: Boolean = editable

    private var mContext: Context? = context

    private var mInflater: LayoutInflater? = LayoutInflater.from(context)

    private var mItems: List<String?>? = items

    interface ClickCallback {
        fun onClick(v: View?, pos: Int)
    }
    private var mClickCallback: ClickCallback? = clickCallback

    interface RemoveCallback {
        fun onRemove(pos: Int)
    }
    private var mRemoveCallback: RemoveCallback? = removeCallback

    interface PlacementCallback {
        fun onEditPlacement()
    }
    private var mPlacementCallback: PlacementCallback? = placementCallback

    private var mTakingScreenshot: Boolean = false

    open fun setItems(items: List<String?>?) {
        mItems = items
    }

    open fun getItems(): List<String?>? = mItems

    open fun setTakingScreenshot(takingScreenshot: Boolean) {
        mTakingScreenshot = takingScreenshot
        notifyDataSetChanged()
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

        val size = AttachmentHelper.calculateImageSize(mContext, itemCount)
        val params = holder.itemView.layoutParams as GridLayoutManager.LayoutParams
        params.width  = size[0]
        params.height = size[1]

        val type = if (typePathName[0] == '0') AttachmentHelper.IMAGE else AttachmentHelper.VIDEO
        if (type == AttachmentHelper.IMAGE) {
            holder.ivImage!!.contentDescription =
                mContext!!.getString(R.string.cd_image_attachment)
            holder.ivDelete!!.contentDescription =
                mContext!!.getString(R.string.cd_delete_image_attachment)
            holder.ivPlacement!!.contentDescription =
                mContext!!.getString(R.string.cd_set_home_card_image_placement)
            holder.ivVideoSignal!!.visibility = View.GONE
        } else {
            holder.ivImage!!.contentDescription =
                mContext!!.getString(R.string.cd_video_attachment)
            holder.ivDelete!!.contentDescription =
                mContext!!.getString(R.string.cd_delete_video_attachment)
            holder.ivPlacement!!.contentDescription =
                mContext!!.getString(R.string.cd_set_home_card_video_placement)
            holder.ivVideoSignal!!.visibility = View.VISIBLE
        }

        Glide.with(holder.ivImage.context)
            .load(pathName)
            .centerCrop()
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?, model: Any?, target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean = false

                override fun onResourceReady(
                    resource: Drawable, model: Any, target: Target<Drawable>?,
                    dataSource: DataSource, isFirstResource: Boolean
                ): Boolean {
                    val currentPosition = holder.adapterPosition
                    holder.ivDelete.visibility =
                        if (!mTakingScreenshot && mEditable) View.VISIBLE else View.GONE
                    holder.ivPlacement.visibility =
                        if (!mTakingScreenshot && mEditable && currentPosition == 0)
                            View.VISIBLE
                        else
                            View.GONE
                    holder.pbLoading!!.visibility = View.GONE
                    return false
                }
            })
            .into(holder.ivImage)

        if (!mTakingScreenshot && mEditable) {
            holder.ivDelete.visibility = View.VISIBLE
            holder.ivPlacement.visibility = if (position == 0) View.VISIBLE else View.GONE
        } else {
            holder.ivDelete.visibility = View.GONE
            holder.ivPlacement.visibility = View.GONE
        }
    }

    override fun getItemCount(): Int = mItems!!.size

    inner class ImageViewHolder internal constructor(itemView: View?) : BaseViewHolder(itemView) {

        val fl: FrameLayout? = f(R.id.fl_image_attachment)
        val ivImage: ImageView? = f(R.id.iv_image_attachment)
        val ivVideoSignal: ImageView? = f(R.id.iv_video_signal)
        val ivPlacement: ImageView? = f(R.id.iv_home_card_image_placement)
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
                ivPlacement!!.visibility = View.VISIBLE
                ivPlacement.setOnClickListener {
                    if (mPlacementCallback != null) {
                        mPlacementCallback!!.onEditPlacement()
                    }
                }
                ivDelete.setOnClickListener {
                    if (mRemoveCallback != null) {
                        mRemoveCallback!!.onRemove(adapterPosition)
                    }
                }
            } else {
                ivDelete!!.visibility = View.GONE
                ivPlacement!!.visibility = View.GONE
            }
        }
    }
}
