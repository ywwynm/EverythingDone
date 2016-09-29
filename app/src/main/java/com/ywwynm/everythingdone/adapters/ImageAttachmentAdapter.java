package com.ywwynm.everythingdone.adapters;

import android.content.Context;
import android.graphics.PorterDuff;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.GlideDrawable;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.helpers.AttachmentHelper;

import java.util.List;

import static com.ywwynm.everythingdone.helpers.AttachmentHelper.IMAGE;
import static com.ywwynm.everythingdone.helpers.AttachmentHelper.VIDEO;

/**
 * Created by ywwynm on 2015/9/23.
 * Adapter for image attachments(including image, video and doodle) of a thing.
 */
public class ImageAttachmentAdapter extends RecyclerView.Adapter<ImageAttachmentAdapter.ImageViewHolder> {

    private boolean mEditable;

    private Context mContext;

    private LayoutInflater mInflater;

    private List<String> mItems;

    public interface ClickCallback {
        void onClick(View v, int pos);
    }
    private ClickCallback mClickCallback;

    public interface RemoveCallback {
        void onRemove(int pos);
    }
    private RemoveCallback mRemoveCallback;

    private boolean mTakingScreenshot = false;

    public ImageAttachmentAdapter(Context context, boolean editable, List<String> items,
                                  ClickCallback clickCallback, RemoveCallback removeCallback) {
        mContext = context;
        mEditable = editable;
        mInflater = LayoutInflater.from(context);

        mItems = items;

        mClickCallback  = clickCallback;
        mRemoveCallback = removeCallback;
    }

    public void setItems(List<String> items) {
        mItems = items;
    }

    public List<String> getItems() {
        return mItems;
    }

    public void setTakingScreenshot(boolean takingScreenshot) {
        mTakingScreenshot = takingScreenshot;
        notifyDataSetChanged();
    }

    @Override
    public ImageViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        return new ImageViewHolder(
                mInflater.inflate(R.layout.attachment_image, parent, false));
    }

    @Override
    public void onBindViewHolder(final ImageViewHolder holder, int position) {
        String typePathName = mItems.get(position);
        final String pathName = typePathName.substring(1, typePathName.length());

        int[] size = AttachmentHelper.calculateImageSize(mContext, getItemCount());
        GridLayoutManager.LayoutParams params = (GridLayoutManager.LayoutParams)
                holder.itemView.getLayoutParams();
        params.width  = size[0];
        params.height = size[1];

        int type = typePathName.charAt(0) == '0' ? IMAGE : VIDEO;
        if (type == IMAGE) {
            holder.ivImage.setContentDescription(
                    mContext.getString(R.string.cd_image_attachment));
            holder.ivDelete.setContentDescription(
                    mContext.getString(R.string.cd_delete_image_attachment));
            holder.ivVideoSignal.setVisibility(View.GONE);
        } else {
            holder.ivImage.setContentDescription(
                    mContext.getString(R.string.cd_video_attachment));
            holder.ivDelete.setContentDescription(
                    mContext.getString(R.string.cd_delete_video_attachment));
            holder.ivVideoSignal.setVisibility(View.VISIBLE);
        }

        Glide.with(holder.ivImage.getContext())
                .load(pathName)
                .centerCrop()
                .listener(new RequestListener<String, GlideDrawable>() {
                    @Override
                    public boolean onException(
                            Exception e, String model, Target<GlideDrawable> target,
                            boolean isFirstResource) {
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(
                            GlideDrawable resource, String model, Target<GlideDrawable> target,
                            boolean isFromMemoryCache, boolean isFirstResource) {
                        holder.ivDelete.setVisibility(View.VISIBLE);
                        holder.pbLoading.setVisibility(View.GONE);
                        return false;
                    }
                })
                .into(holder.ivImage);

        if (!mTakingScreenshot && mEditable) {
            holder.ivDelete.setVisibility(View.VISIBLE);
        } else {
            holder.ivDelete.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    class ImageViewHolder extends BaseViewHolder {

        final FrameLayout fl;
        final ImageView ivImage;
        final ImageView ivVideoSignal;
        final ImageView ivDelete;
        final ProgressBar pbLoading;

        ImageViewHolder(View itemView) {
            super(itemView);

            fl            = f(R.id.fl_image_attachment);
            ivImage       = f(R.id.iv_image_attachment);
            ivVideoSignal = f(R.id.iv_video_signal);
            ivDelete      = f(R.id.iv_delete_image_attachment);
            pbLoading     = f(R.id.pb_image_attachment);

            int pbColor = ContextCompat.getColor(mContext, R.color.app_accent);
            pbLoading.getIndeterminateDrawable().setColorFilter(pbColor, PorterDuff.Mode.SRC_IN);

            fl.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mClickCallback != null) {
                        mClickCallback.onClick(v, getAdapterPosition());
                    }
                }
            });

            if (mEditable) {
                ivDelete.setVisibility(View.VISIBLE);
                ivDelete.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (mRemoveCallback != null) {
                            mRemoveCallback.onRemove(getAdapterPosition());
                        }
                    }
                });
            } else {
                ivDelete.setVisibility(View.GONE);
            }
        }
    }
}
