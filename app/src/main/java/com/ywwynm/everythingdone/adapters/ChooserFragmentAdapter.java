package com.ywwynm.everythingdone.adapters;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.support.v4.content.ContextCompat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.ywwynm.everythingdone.R;

import java.util.List;

/**
 * Created by ywwynm on 2016/3/12.
 * RecyclerView adapter for chooser fragment.
 */
public class ChooserFragmentAdapter extends SingleChoiceAdapter {

    public static final String TAG = "ChooserFragmentAdapter";

    private LayoutInflater mInflater;
    private List<String> mItems;
    private int mAccentColor;

    private View.OnClickListener mOnItemClickListener;

    public ChooserFragmentAdapter(Context context, List<String> items, int accentColor) {
        mInflater = LayoutInflater.from(context);
        mItems = items;
        mAccentColor = accentColor;
    }

    public void setOnItemClickListener(View.OnClickListener onItemClickListener) {
        mOnItemClickListener = onItemClickListener;
    }

    @Override
    public void pick(int position) {
        notifyItemChanged(mPickedPosition);
        mPickedPosition = position;
    }

    @Override
    public BaseViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        return new ChoiceHolder(mInflater.inflate(R.layout.rv_fragment_chooser, parent, false));
    }

    @Override
    public void onBindViewHolder(BaseViewHolder viewHolder, int position) {
        ChoiceHolder holder = (ChoiceHolder) viewHolder;
        holder.tv.setText(mItems.get(position));
        Context context = holder.tv.getContext();
        int uncheckedColor = ContextCompat.getColor(context, R.color.black_54);
        Drawable d;
        if (mPickedPosition == position) {
            d = ContextCompat.getDrawable(context, R.drawable.ic_radiobutton_checked);
            d.mutate().setColorFilter(mAccentColor, PorterDuff.Mode.SRC_ATOP);
        } else {
            d = ContextCompat.getDrawable(context, R.drawable.ic_radiobutton_unchecked);
            d.mutate().setColorFilter(uncheckedColor, PorterDuff.Mode.SRC_ATOP);
        }
        holder.tv.setCompoundDrawablesWithIntrinsicBounds(d, null, null, null);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    class ChoiceHolder extends BaseViewHolder {

        final TextView tv;

        public ChoiceHolder(View itemView) {
            super(itemView);

            tv = f(R.id.tv_rv_chooser_fragment);

            tv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pick(getAdapterPosition());
                    notifyItemChanged(mPickedPosition);
                    if (mOnItemClickListener != null) {
                        mOnItemClickListener.onClick(v);
                    }
                }
            });
        }
    }
}
