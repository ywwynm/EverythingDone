package com.ywwynm.everythingdone.adapters;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.utils.DateTimeUtil;

import java.io.File;
import java.util.List;

/**
 * Created by ywwynm on 2015/10/4.
 * adapter for audio attachment
 */
public class AudioAttachmentAdapter extends RecyclerView.Adapter<AudioAttachmentAdapter.AudioCardViewHolder> {

    public static final String TAG = "AudioAttachmentAdapter";

    private Context mContext;

    private boolean mEditable;

    private LayoutInflater mInflater;

    private List<String> mItems;

    private int mPlayingIndex = -1;
    private MediaPlayer mPlayer;

    public interface RemoveCallback {
        void onRemoved(int pos);
    }
    private RemoveCallback mRemoveCallback;

    public AudioAttachmentAdapter(Context context, boolean editable, List<String> items, RemoveCallback callback) {
        mContext = context;
        mEditable = editable;
        mInflater = LayoutInflater.from(context);
        mItems = items;

        mRemoveCallback = callback;
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
        File file = new File(typePathName.substring(1, typePathName.length()));

        holder.tvName.setText(file.getName());

        MediaPlayer mp = MediaPlayer.create(mContext, Uri.fromFile(file));
        holder.tvSize.setText(DateTimeUtil.getTimeLengthBriefStr(mp.getDuration()));
        mp.reset();
        mp.release();

        if (mPlayingIndex == position) {
            holder.ivFirst.setVisibility(View.VISIBLE);
            if (mPlayer.isPlaying()) {
                holder.ivFirst.setImageResource(R.mipmap.act_pause);
            } else {
                holder.ivFirst.setImageResource(R.mipmap.act_play);
            }
            holder.ivSecond.setImageResource(R.mipmap.act_stop_playing_audio);
        } else {
            if (mEditable) {
                holder.ivFirst.setVisibility(View.VISIBLE);
                holder.ivFirst.setImageResource(R.mipmap.act_play);
                holder.ivSecond.setImageResource(R.mipmap.delete_audio);
            } else {
                holder.ivFirst.setVisibility(View.GONE);
                holder.ivSecond.setImageResource(R.mipmap.act_play);
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

        mPlayer = MediaPlayer.create(mContext, Uri.fromFile(file));
        mPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                final int index = mPlayingIndex;
                stopPlaying();
                notifyItemChanged(index);
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

    class AudioCardViewHolder extends RecyclerView.ViewHolder {

        final TextView  tvName;
        final TextView  tvSize;
        final ImageView ivFirst;
        final ImageView ivSecond;

        public AudioCardViewHolder(View itemView) {
            super(itemView);

            tvName   = (TextView)  itemView.findViewById(R.id.tv_audio_file_name);
            tvSize   = (TextView)  itemView.findViewById(R.id.tv_audio_size);
            ivFirst  = (ImageView) itemView.findViewById(R.id.iv_card_audio_first);
            ivSecond = (ImageView) itemView.findViewById(R.id.iv_card_audio_second);

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
