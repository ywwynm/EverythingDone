package com.ywwynm.everythingdone.adapters;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.CardView;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.helpers.AttachmentHelper;
import com.ywwynm.everythingdone.utils.DateTimeUtil;
import com.ywwynm.everythingdone.utils.FileUtil;

import java.io.File;
import java.util.List;

/**
 * Created by ywwynm on 2015/10/4.
 * adapter for audio attachment
 */
public class AudioAttachmentAdapter extends RecyclerView.Adapter<AudioAttachmentAdapter.AudioCardViewHolder> {

    public static final String TAG = "AudioAttachmentAdapter";

    private Activity mActivity;

    private int mAccentColor;

    private boolean mEditable;

    private LayoutInflater mInflater;

    private List<String> mItems;

    private int mPlayingIndex = -1;
    private MediaPlayer mPlayer;

    private boolean mTakingScreenshot = false;

    public interface RemoveCallback {
        void onRemoved(int pos);
    }
    private RemoveCallback mRemoveCallback;

    public AudioAttachmentAdapter(
            Activity activity, int accentColor, boolean editable, List<String> items,
            RemoveCallback callback) {
        mActivity = activity;
        mAccentColor = accentColor;
        mEditable = editable;
        mInflater = LayoutInflater.from(activity);
        mItems = items;

        mRemoveCallback = callback;
    }

    public void setTakingScreenshot(boolean takingScreenshot) {
        mTakingScreenshot = takingScreenshot;
        if (mPlayer != null && mPlayer.isPlaying()) {
            stopPlaying();
        }
        notifyDataSetChanged();
    }

    public List<String> getItems() {
        return mItems;
    }

    public int getPlayingIndex() {
        return mPlayingIndex;
    }

    public void setPlayingIndex(int playingIndex) {
        mPlayingIndex = playingIndex;
    }

    @Override
    public AudioCardViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        return new AudioCardViewHolder(mInflater.inflate(R.layout.attachment_audio, parent, false));
    }

    @Override
    public void onBindViewHolder(AudioCardViewHolder holder, int position) {
        String typePathName = mItems.get(position);
        String pathName = typePathName.substring(1, typePathName.length());
        File file = new File(pathName);

        holder.tvName.setText(file.getName());
        long duration = FileUtil.getMediaDuration(pathName);
        holder.tvSize.setText(DateTimeUtil.getDurationBriefStr(duration));

        if (mTakingScreenshot) {
            holder.ivFirst .setVisibility(View.VISIBLE);
            holder.ivSecond.setVisibility(View.GONE);
            holder.ivThird .setVisibility(View.GONE);
            holder.ivFirst.setImageResource(R.drawable.act_play);
        } else {
            Context context = holder.itemView.getContext();
            holder.ivSecond.setVisibility(View.VISIBLE);
            holder.ivThird. setVisibility(View.VISIBLE);
            if (mPlayingIndex == position) {
                holder.ivFirst.setVisibility(View.VISIBLE);
                if (mPlayer.isPlaying()) {
                    holder.ivFirst.setImageResource(R.drawable.act_pause);
                    holder.ivFirst.setContentDescription(
                            context.getString(R.string.cd_pause_play_audio_attachment));
                } else {
                    holder.ivFirst.setImageResource(R.drawable.act_play);
                    holder.ivFirst.setContentDescription(
                            context.getString(R.string.cd_play_audio_attachment));
                }
                holder.ivSecond.setImageResource(R.drawable.act_stop_playing_audio);
                holder.ivSecond.setContentDescription(
                        context.getString(R.string.cd_stop_play_audio_attachment));
            } else {
                if (mEditable) {
                    holder.ivFirst.setVisibility(View.VISIBLE);
                    holder.ivFirst.setImageResource(R.drawable.act_play);
                    holder.ivFirst.setContentDescription(
                            context.getString(R.string.cd_play_audio_attachment));
                    holder.ivSecond.setImageResource(R.drawable.delete_audio);
                    holder.ivSecond.setContentDescription(
                            context.getString(R.string.cd_delete_audio_attachment));
                } else {
                    holder.ivFirst.setVisibility(View.GONE);
                    holder.ivSecond.setImageResource(R.drawable.act_play);
                    holder.ivSecond.setContentDescription(
                            context.getString(R.string.cd_play_audio_attachment));
                }
            }
        }
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    private void startPlaying(int index) {
        mPlayingIndex = index;
        String typePathName = mItems.get(index);
        File file = new File(typePathName.substring(1, typePathName.length()));

        mPlayer = MediaPlayer.create(mActivity, Uri.fromFile(file));
        mPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                final int index1 = mPlayingIndex;
                stopPlaying();
                notifyItemChanged(index1);
            }
        });
        mPlayer.start();
    }

    public void stopPlaying() {
        mPlayingIndex = -1;
        mPlayer.stop();
        mPlayer.reset();
        mPlayer.release();
        mPlayer = null;
    }

    class AudioCardViewHolder extends BaseViewHolder {

        final CardView  cv;
        final TextView  tvName;
        final TextView  tvSize;
        final ImageView ivFirst;
        final ImageView ivSecond;
        final ImageView ivThird;

        AudioCardViewHolder(View itemView) {
            super(itemView);

            cv       = f(R.id.cv_audio_attachment);
            tvName   = f(R.id.tv_audio_file_name);
            tvSize   = f(R.id.tv_audio_size);
            ivFirst  = f(R.id.iv_card_audio_first);
            ivSecond = f(R.id.iv_card_audio_second);
            ivThird  = f(R.id.iv_card_audio_third);

            Drawable d = ContextCompat.getDrawable(
                    mActivity, R.drawable.act_show_attachment_info);
            Drawable d1 = d.mutate();
            d1.setColorFilter(Color.parseColor("#8A000000"), PorterDuff.Mode.SRC_ATOP);
            ivThird.setImageDrawable(d1);

            cv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    togglePlay();
                }
            });

            ivThird.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String item = mItems.get(AudioCardViewHolder.this.getAdapterPosition());
                    String pathName = item.substring(1, item.length());
                    AttachmentHelper.showAttachmentInfoDialog(mActivity, mAccentColor, pathName);
                }
            });

            if (mEditable) {
                setEventsEditable();
            } else {
                setEventsUneditable();
            }
        }

        private void setEventsEditable() {
            ivFirst.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    togglePlay();
                }
            });

            ivSecond.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int pos = getAdapterPosition();
                    if (mPlayingIndex == pos) {
                        stopPlaying();
                        notifyItemChanged(pos);
                    } else {
                        if (mRemoveCallback != null) {
                            mRemoveCallback.onRemoved(pos);
                        }
                    }
                }
            });
        }

        private void togglePlay() {
            int pos = getAdapterPosition();
            if (mPlayingIndex == pos) {
                if (mPlayer.isPlaying()) {
                    mPlayer.pause();
                } else {
                    mPlayer.start();
                }
            } else {
                if (mPlayingIndex != -1) {
                    final int index = mPlayingIndex;
                    stopPlaying();
                    notifyItemChanged(index);
                }
                startPlaying(pos);
            }
            notifyItemChanged(pos);
        }

        private void setEventsUneditable() {
            ivFirst.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mPlayer.isPlaying()) {
                        mPlayer.pause();
                    } else {
                        mPlayer.start();
                    }
                    notifyItemChanged(getAdapterPosition());
                }
            });

            ivSecond.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int pos = getAdapterPosition();
                    if (mPlayingIndex == pos) {
                         stopPlaying();
                    } else {
                        if (mPlayingIndex != -1) {
                            final int index = mPlayingIndex;
                            stopPlaying();
                            notifyItemChanged(index);
                        }
                        startPlaying(pos);
                    }
                    notifyItemChanged(pos);
                }
            });
        }
    }

}
