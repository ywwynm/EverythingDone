package com.ywwynm.everythingdone.adapters;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import androidx.core.content.ContextCompat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.ywwynm.everythingdone.R;

import java.util.List;

/**
 * Created by qiizhang on 2016/11/21.
 * Copied from ChooserDialogFragment.ChooserFragmentAdapter
 */
public class RadioChooserAdapter extends SingleChoiceAdapter {

    public static final String TAG = "ChooserFragmentAdapter";

    private LayoutInflater mInflater;
    private List<String> mItems;
    private int mAccentColor;
    /** Phase 8: full accent so the picked item's text can render gradient.
     *  When null, the row falls back to plain {@link #mAccentColor}. */
    private com.ywwynm.everythingdone.model.ThingBackground mAccentBackground;

    private View.OnClickListener mOnItemClickListener;

    public RadioChooserAdapter(Context context, List<String> items, int accentColor) {
        mInflater = LayoutInflater.from(context);
        mItems = items;
        mAccentColor = accentColor;
    }

    /** Phase 8: accept a full {@link com.ywwynm.everythingdone.model.ThingBackground}
     *  for gradient text on the picked row. */
    public void setAccentBackground(com.ywwynm.everythingdone.model.ThingBackground bg) {
        mAccentBackground = bg;
        if (bg != null) mAccentColor = bg.representativeColor();
        notifyDataSetChanged();
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
        String item = mItems.get(position);
        holder.tv.setText(item);
        Context context = holder.tv.getContext();
        int uncheckedColor = ContextCompat.getColor(context, R.color.black_54);
        Drawable d;
        if (mPickedPosition == position) { // -15310698
            // Phase 8: tint the check icon with the gradient too when the
            // accent is GRADIENT — uses the alpha-mask + SRC_IN technique so
            // the radio bullet itself adopts the gradient instead of just its
            // representative int. PURE branch is the original SRC_ATOP filter.
            Drawable srcChecked = ContextCompat.getDrawable(
                    context, R.drawable.ic_radiobutton_checked);
            if (mAccentBackground != null) {
                d = com.ywwynm.everythingdone.utils.BackgroundUtil.tintDrawable(
                        context.getResources(), srcChecked, mAccentBackground);
            } else {
                d = srcChecked.mutate();
                d.setColorFilter(mAccentColor, PorterDuff.Mode.SRC_ATOP);
            }
            holder.tv.setContentDescription(context.getString(R.string.cd_chosen_item) + item);
            // Phase 8: gradient text on the picked row when an accent
            // ThingBackground was supplied. Clears any stale shader otherwise.
            if (mAccentBackground != null) {
                com.ywwynm.everythingdone.utils.BackgroundUtil.applyTextBackground(
                        holder.tv, mAccentBackground);
            } else {
                if (holder.tv.getPaint().getShader() != null) {
                    holder.tv.getPaint().setShader(null);
                }
                holder.tv.setTextColor(mAccentColor);
            }
        } else {
            d = ContextCompat.getDrawable(context, R.drawable.ic_radiobutton_unchecked);
            d.mutate().setColorFilter(uncheckedColor, PorterDuff.Mode.SRC_ATOP);
            holder.tv.setContentDescription(context.getString(R.string.cd_not_chosen_item) + item);
            // Clear any shader left from a previous bind to this view holder
            // so an unselected row paints in its plain unchecked colour.
            if (holder.tv.getPaint().getShader() != null) {
                holder.tv.getPaint().setShader(null);
            }
            holder.tv.setTextColor(uncheckedColor);
        }
        holder.tv.setCompoundDrawablesWithIntrinsicBounds(d, null, null, null);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    private class ChoiceHolder extends BaseViewHolder {

        final TextView tv;

        ChoiceHolder(View itemView) {
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
