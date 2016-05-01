package com.ywwynm.everythingdone.adapters;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;

import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.activities.DetailActivity;
import com.ywwynm.everythingdone.helpers.AttachmentHelper;
import com.ywwynm.everythingdone.utils.ImageLoader;

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

    private LruCache<String, Bitmap> mBitmapCache;

    public interface ClickCallback {
        void onClick(View v, int pos);
        boolean onLongClick(View v, int pos);
    }
    private ClickCallback mClickCallback;

    public interface RemoveCallback {
        void onRemove(int pos);
    }
    private RemoveCallback mRemoveCallback;

    public ImageAttachmentAdapter(Context context, boolean editable, List<String> items,
                                  ClickCallback clickCallback, RemoveCallback removeCallback) {
        mContext = context;
        mEditable = editable;
        mInflater = LayoutInflater.from(context);

        mItems = items;

        mBitmapCache = ((DetailActivity) mContext).getBitmapLruCache();

        mClickCallback  = clickCallback;
        mRemoveCallback = removeCallback;
    }

    public void setItems(List<String> items) {
        mItems = items;
    }

    public List<String> getItems() {
        return mItems;
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

        String key = AttachmentHelper.generateKeyForCache(pathName, size[0], size[1]);
        int type = typePathName.charAt(0) == '0' ? IMAGE : VIDEO;
        Bitmap bitmap = mBitmapCache.get(key);
        if (bitmap == null) {
            if (type == IMAGE) {
                holder.ivVideoSignal.setVisibility(View.GONE);
            }
            ImageLoader loader = new ImageLoader(type, mBitmapCache,
                    holder.ivImage, holder.ivVideoSignal, holder.ivDelete, holder.pbLoading);
            loader.execute(key, size[0], size[1]);
        } else {
            holder.ivImage.setImageBitmap(bitmap);
            if (type == VIDEO) {
                holder.ivVideoSignal.setVisibility(View.VISIBLE);
            } else {
                holder.ivVideoSignal.setVisibility(View.GONE);
            }
            holder.ivDelete.setVisibility(View.VISIBLE);
            holder.pbLoading.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    class ImageViewHolder extends RecyclerView.ViewHolder {

        final FrameLayout fl;
        final ImageView ivImage;
        final ImageView ivVideoSignal;
        final ImageView ivDelete;
        final ProgressBar pbLoading;

        public ImageViewHolder(View itemView) {
            super(itemView);

            fl            = (FrameLayout) itemView.findViewById(R.id.fl_image_attachment);
            ivImage       = (ImageView)   itemView.findViewById(R.id.iv_image_attachment);
            ivVideoSignal = (ImageView)   itemView.findViewById(R.id.iv_video_signal);
            ivDelete      = (ImageView)   itemView.findViewById(R.id.iv_delete_image_attachment);
            pbLoading     = (ProgressBar) itemView.findViewById(R.id.pb_image_attachment);

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
            fl.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (mClickCallback != null) {
                        return mClickCallback.onLongClick(v, getAdapterPosition());
                    }
                    return false;
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
