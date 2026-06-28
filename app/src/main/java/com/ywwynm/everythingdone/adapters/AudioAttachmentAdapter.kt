@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.adapters

import android.app.Activity
import android.media.MediaPlayer
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.DateTimeUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.FileUtil
import com.ywwynm.everythingdone.views.GradientRippleDrawable

import java.io.File

/**
 * Created by ywwynm on 2015/10/4.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * adapter for audio attachment
 */
open class AudioAttachmentAdapter(
    activity: Activity?,
    accentColor: Int,
    editable: Boolean,
    items: List<String?>?,
    callback: RemoveCallback?
) : RecyclerView.Adapter<AudioAttachmentAdapter.AudioCardViewHolder>() {

    private var mActivity: Activity? = activity

    private var mAccentColor: Int = accentColor
    /** Phase 8: full accent so the attachment-info dialog launched from a tap
     *  can render gradient. Always wraps [mAccentColor] as PURE when
     *  unset, so the helper never receives null. */
    private var mAccentBackground: ThingBackground? = ThingBackground.pure(accentColor)

    private var mEditable: Boolean = editable

    private var mInflater: LayoutInflater? = LayoutInflater.from(activity)

    private var mItems: List<String?>? = items

    private var mPlayingIndex: Int = -1
    private var mPlayer: MediaPlayer? = null

    private var mTakingScreenshot: Boolean = false

    interface RemoveCallback {
        fun onRemoved(pos: Int)
    }
    private var mRemoveCallback: RemoveCallback? = callback

    /** Phase 8: upgrade the accent to a full [ThingBackground] so the
     *  attachment-info dialog renders gradient when the thing has one. */
    open fun setAccentBackground(bg: ThingBackground?) {
        if (bg == null) return
        mAccentBackground = bg
        mAccentColor = bg.representativeColor()
    }

    open fun setTakingScreenshot(takingScreenshot: Boolean) {
        mTakingScreenshot = takingScreenshot
        if (mPlayer != null && mPlayer!!.isPlaying) {
            stopPlaying()
        }
        notifyDataSetChanged()
    }

    open fun getItems(): List<String?>? = mItems

    open fun getPlayingIndex(): Int = mPlayingIndex

    open fun setPlayingIndex(playingIndex: Int) {
        mPlayingIndex = playingIndex
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AudioCardViewHolder {
        return AudioCardViewHolder(mInflater!!.inflate(R.layout.attachment_audio, parent, false))
    }

    override fun onBindViewHolder(holder: AudioCardViewHolder, position: Int) {
        val typePathName = mItems!![position]
        val pathName = typePathName!!.substring(1, typePathName.length)
        val file = File(pathName)

        holder.tvName!!.text = file.name
        val duration = FileUtil.getMediaDuration(pathName)
        holder.tvSize!!.text = DateTimeUtil.getDurationBriefStr(duration)
        holder.cv!!.setCardBackgroundColor(
            ContextCompat.getColor(mActivity!!, R.color.app_chrome_surface_elevated)
        )
        holder.tvName!!.setTextColor(
            ContextCompat.getColor(mActivity!!, R.color.app_chrome_on_surface_secondary)
        )
        holder.tvSize!!.setTextColor(
            ContextCompat.getColor(mActivity!!, R.color.app_chrome_on_surface_hint)
        )
        applyAudioRipples(holder)
        setAudioIcon(holder.ivThird, R.drawable.act_show_attachment_info)

        if (mTakingScreenshot) {
            holder.ivFirst !!.visibility = View.VISIBLE
            holder.ivSecond!!.visibility = View.GONE
            holder.ivThird !!.visibility = View.GONE
            setAudioIcon(holder.ivFirst, R.drawable.act_play)
        } else {
            val context = holder.itemView.context
            holder.ivSecond!!.visibility = View.VISIBLE
            holder.ivThird !!.visibility = View.VISIBLE
            if (mPlayingIndex == position) {
                holder.ivFirst!!.visibility = View.VISIBLE
                if (mPlayer!!.isPlaying) {
                    setAudioIcon(holder.ivFirst, R.drawable.act_pause)
                    holder.ivFirst!!.contentDescription =
                        context.getString(R.string.cd_pause_play_audio_attachment)
                } else {
                    setAudioIcon(holder.ivFirst, R.drawable.act_play)
                    holder.ivFirst!!.contentDescription =
                        context.getString(R.string.cd_play_audio_attachment)
                }
                setAudioIcon(holder.ivSecond, R.drawable.act_stop_playing_audio)
                holder.ivSecond!!.contentDescription =
                    context.getString(R.string.cd_stop_play_audio_attachment)
            } else {
                if (mEditable) {
                    holder.ivFirst!!.visibility = View.VISIBLE
                    setAudioIcon(holder.ivFirst, R.drawable.act_play)
                    holder.ivFirst!!.contentDescription =
                        context.getString(R.string.cd_play_audio_attachment)
                    setAudioIcon(holder.ivSecond, R.drawable.delete_audio)
                    holder.ivSecond!!.contentDescription =
                        context.getString(R.string.cd_delete_audio_attachment)
                } else {
                    holder.ivFirst!!.visibility = View.GONE
                    setAudioIcon(holder.ivSecond, R.drawable.act_play)
                    holder.ivSecond!!.contentDescription =
                        context.getString(R.string.cd_play_audio_attachment)
                }
            }
        }
    }

    override fun getItemCount(): Int = mItems!!.size

    override fun onViewRecycled(holder: AudioCardViewHolder) {
        super.onViewRecycled(holder)
        (holder.cv?.foreground as? GradientRippleDrawable)?.stopAnimations()
        (holder.ivFirst?.background as? GradientRippleDrawable)?.stopAnimations()
        (holder.ivSecond?.background as? GradientRippleDrawable)?.stopAnimations()
        (holder.ivThird?.background as? GradientRippleDrawable)?.stopAnimations()
    }

    private fun setAudioIcon(imageView: ImageView?, iconRes: Int) {
        imageView!!.setImageDrawable(
            DisplayUtil.opaqueTintDrawable(
                mActivity!!,
                ContextCompat.getDrawable(mActivity!!, iconRes),
                ContextCompat.getColor(mActivity!!, R.color.app_chrome_control_unchecked)
            )
        )
    }

    /** 音频卡片 + 播放/暂停、删除、信息三按钮的触摸 ripple 改为当前记事颜色（卡片随圆角、按钮圆形）。 */
    private fun applyAudioRipples(holder: AudioCardViewHolder) {
        val bg = mAccentBackground ?: ThingBackground.pure(mAccentColor)
        holder.cv!!.foreground =
            GradientRippleDrawable(bg, shapeOval = false, cornerRadiusPx = holder.cv!!.radius)
        // 三个按钮的 ripple 铺满按钮矩形区域（不是圆形）。
        holder.ivFirst!!.background = GradientRippleDrawable(bg, shapeOval = false, cornerRadiusPx = 0f)
        holder.ivSecond!!.background = GradientRippleDrawable(bg, shapeOval = false, cornerRadiusPx = 0f)
        holder.ivThird!!.background = GradientRippleDrawable(bg, shapeOval = false, cornerRadiusPx = 0f)
    }

    private fun startPlaying(index: Int) {
        mPlayingIndex = index
        val typePathName = mItems!![index]
        val file = File(typePathName!!.substring(1, typePathName.length))

        mPlayer = MediaPlayer.create(
            mActivity,
            FileProvider.getUriForFile(mActivity!!, "com.ywwynm.everythingdone", file)
        )
        mPlayer!!.setOnCompletionListener {
            val index1 = mPlayingIndex
            stopPlaying()
            notifyItemChanged(index1)
        }
        mPlayer!!.start()
    }

    open fun stopPlaying() {
        mPlayingIndex = -1
        mPlayer!!.stop()
        mPlayer!!.reset()
        mPlayer!!.release()
        mPlayer = null
    }

    inner class AudioCardViewHolder internal constructor(itemView: View?) : BaseViewHolder(itemView) {

        val cv: CardView?  = f(R.id.cv_audio_attachment)
        val tvName: TextView?   = f(R.id.tv_audio_file_name)
        val tvSize: TextView?   = f(R.id.tv_audio_size)
        val ivFirst: ImageView?  = f(R.id.iv_card_audio_first)
        val ivSecond: ImageView? = f(R.id.iv_card_audio_second)
        val ivThird: ImageView?  = f(R.id.iv_card_audio_third)

        init {
            setAudioIcon(ivThird, R.drawable.act_show_attachment_info)

            cv!!.setOnClickListener {
                togglePlay()
            }

            ivThird!!.setOnClickListener {
                val item = mItems!![this@AudioCardViewHolder.adapterPosition]
                val pathName = item!!.substring(1, item.length)
                AttachmentHelper.showAttachmentInfoDialog(mActivity, mAccentBackground, pathName)
            }

            if (mEditable) {
                setEventsEditable()
            } else {
                setEventsUneditable()
            }
        }

        private fun setEventsEditable() {
            ivFirst!!.setOnClickListener {
                togglePlay()
            }

            ivSecond!!.setOnClickListener {
                val pos = adapterPosition
                if (mPlayingIndex == pos) {
                    stopPlaying()
                    notifyItemChanged(pos)
                } else {
                    if (mRemoveCallback != null) {
                        mRemoveCallback!!.onRemoved(pos)
                    }
                }
            }
        }

        private fun togglePlay() {
            val pos = adapterPosition
            if (mPlayingIndex == pos) {
                if (mPlayer!!.isPlaying) {
                    mPlayer!!.pause()
                } else {
                    mPlayer!!.start()
                }
            } else {
                if (mPlayingIndex != -1) {
                    val index = mPlayingIndex
                    stopPlaying()
                    notifyItemChanged(index)
                }
                startPlaying(pos)
            }
            notifyItemChanged(pos)
        }

        private fun setEventsUneditable() {
            ivFirst!!.setOnClickListener {
                if (mPlayer!!.isPlaying) {
                    mPlayer!!.pause()
                } else {
                    mPlayer!!.start()
                }
                notifyItemChanged(adapterPosition)
            }

            ivSecond!!.setOnClickListener {
                val pos = adapterPosition
                if (mPlayingIndex == pos) {
                    stopPlaying()
                } else {
                    if (mPlayingIndex != -1) {
                        val index = mPlayingIndex
                        stopPlaying()
                        notifyItemChanged(index)
                    }
                    startPlaying(pos)
                }
                notifyItemChanged(pos)
            }
        }
    }

    companion object {
        const val TAG: String = "AudioAttachmentAdapter"
    }
}
