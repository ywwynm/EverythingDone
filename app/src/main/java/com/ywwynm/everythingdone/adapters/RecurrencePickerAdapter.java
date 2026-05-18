package com.ywwynm.everythingdone.adapters;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import androidx.core.content.ContextCompat;
import androidx.cardview.widget.CardView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.model.ThingBackground;
import com.ywwynm.everythingdone.utils.BackgroundUtil;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.utils.LocaleUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by ywwynm on 2015/9/16.
 * Adapter for RecyclerView used to pick time for recurrence.
 */
public class RecurrencePickerAdapter extends MultiChoiceAdapter {

    public static final String TAG = "RecurrencePickerAdapter";

    private static final int NORMAL       = 0;
    private static final int END_OF_MONTH = 1;

    private Context mContext;

    private LayoutInflater mInflater;

    private int mType;

    private String mCdPicked;
    private String mCdUnpicked;

    private String[] mItems;
    private String[] mCds; // content descriptions

    private int mAccentColor;
    /** Phase 8: full accent so picked cells (both NORMAL fake-FAB and
     *  EndOfMonth CardView) render the real gradient. NORMAL is a fake-FAB —
     *  a clipped FrameLayout whose inner background View carries a
     *  GradientDrawable. The Ripple foreground colour is the only piece that
     *  still uses {@link #mAccentColor} representative (Android's
     *  RippleDrawable ColorStateList accepts a single int only). */
    private ThingBackground mAccentBackground;

    private float mScreenDensity;

    private View.OnClickListener mOnPickListener;

    public void setOnPickListener(View.OnClickListener onPickListener) {
        mOnPickListener = onPickListener;
    }

    /** Phase 8: upgrade the accent signal to a full {@link ThingBackground}.
     *  Rebinds so any currently-attached holder picks up the gradient on its
     *  picked CardView background. */
    public void setAccentBackground(ThingBackground bg) {
        mAccentBackground = bg;
        if (bg != null) {
            mAccentColor = bg.representativeColor();
            notifyDataSetChanged();
        }
    }

    public RecurrencePickerAdapter(Context context, int type, int accentColor) {
        mContext = context;
        mInflater = LayoutInflater.from(context);

        mType = type;

        mCdPicked = mContext.getString(R.string.cd_picked);
        mCdUnpicked = mContext.getString(R.string.cd_unpicked);

        if (type == Def.PickerType.DAY_OF_WEEK) {
            mItems = context.getResources().getStringArray(R.array.day_of_week); // 周日, Sunday
            mCds = context.getResources().getStringArray(R.array.day_of_week);
            if (LocaleUtil.isChinese(context)) {
                for (int i = 0; i < mItems.length; i++) {
                    mItems[i] = mItems[i].substring(1, 2);
                }
            } else {
                for (int i = 0; i < mItems.length; i++) {
                    mItems[i] = mItems[i].substring(0, 3);
                }
            }
        } else if (type == Def.PickerType.DAY_OF_MONTH) {
            mItems = new String[28];
            mCds = new String[28];
            for (int i = 0; i < 27; i++) {
                mItems[i] = String.valueOf(i + 1);
            }
            String day = mContext.getString(R.string.cd_day);
            if (LocaleUtil.isChinese(mContext)) {
                for (int i = 0; i < 27; i++) {
                    mCds[i] = String.valueOf(i + 1) + day;
                }
            } else {
                for (int i = 0; i < 27; i++) {
                    mCds[i] = day + String.valueOf(i + 1);
                }
            }
            mItems[27] = context.getString(R.string.end_of_month);
            mCds[27] = mItems[27];
        } else {
            mItems = context.getResources().getStringArray(R.array.month_of_year);
            mCds = context.getResources().getStringArray(R.array.month_of_year);
            if (!LocaleUtil.isChinese(context)) {
                for (int i = 0; i < mItems.length; i++) {
                    mItems[i] = mItems[i].substring(0, 3);
                }
            }
        }
        mPicked = new boolean[mItems.length];

        mAccentColor = accentColor;
        mScreenDensity = DisplayUtil.getScreenDensity(context);
    }

    @Override
    public BaseViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        if (viewType == NORMAL) {
            return new NormalViewHolder(mInflater.inflate(
                    R.layout.recurrence_picker_normal, parent, false));
        } else {
            return new EndOfMonthViewHolder(mInflater.inflate(
                    R.layout.recurrence_picker_end_of_month, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(BaseViewHolder viewHolder, int position) {
        int unPickerColor = ContextCompat.getColor(mContext, R.color.bg_unpicked);
        int black_54 = ContextCompat.getColor(mContext, R.color.black_54);
        if (getItemViewType(position) == END_OF_MONTH) {
            EndOfMonthViewHolder holder = (EndOfMonthViewHolder) viewHolder;
            // Phase 8: render the pill background through a self-rounded
            // GradientDrawable instead of CardView.setCardBackgroundColor,
            // so a GRADIENT accent shows as a real two-stop gradient. The
            // corner radius is read back from cv.getRadius() (= XML's
            // app:cardCornerRadius), so the pill shape (height/2) is
            // preserved across recycled binds. Outline + ripple foreground
            // clipping follow GradientDrawable.getOutline() automatically.
            GradientDrawable pill = new GradientDrawable();
            pill.setShape(GradientDrawable.RECTANGLE);
            pill.setCornerRadius(holder.cv.getRadius());
            if (mPicked[position]) {
                if (mAccentBackground != null
                        && mAccentBackground.mode == ThingBackground.Mode.GRADIENT) {
                    pill.setOrientation(toGdOrientation(mAccentBackground.orientation));
                    pill.setColors(new int[] {
                            mAccentBackground.color, mAccentBackground.endColor });
                } else {
                    pill.setColor(mAccentColor);
                }
                holder.cv.setBackground(pill);
                holder.cv.setContentDescription(mCdPicked + mCds[position] + ",");
                DisplayUtil.setRippleColorForCardView(holder.cv, unPickerColor);
                holder.tv.setTextColor(Color.WHITE);
            } else {
                pill.setColor(unPickerColor);
                holder.cv.setBackground(pill);
                holder.cv.setContentDescription(mCdUnpicked + mCds[position] + ",");
                DisplayUtil.setRippleColorForCardView(holder.cv, mAccentColor);
                holder.tv.setTextColor(black_54);
            }
        } else {
            NormalViewHolder holder = (NormalViewHolder) viewHolder;
            if (mType == Def.PickerType.DAY_OF_MONTH) {
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) holder.cell.getLayoutParams();
                params.width = (int) (mScreenDensity * 36);
                params.height = params.width;
                // OutlineProvider reads getWidth()/getHeight() at outline build
                // time; invalidate so the new size produces a smaller oval.
                holder.cell.invalidateOutline();
            }
            holder.tvDate.setText(mItems[position]);
            if (mPicked[position]) {
                // Phase 8: render the inner bg View with the full thing
                // gradient when available. The cell's clipToOutline=oval
                // crops the rectangle drawable to a circle, and the ripple
                // foreground draws tap feedback on top.
                if (mAccentBackground != null
                        && mAccentBackground.mode == ThingBackground.Mode.GRADIENT) {
                    GradientDrawable gd = new GradientDrawable(
                            toGdOrientation(mAccentBackground.orientation),
                            new int[] { mAccentBackground.color, mAccentBackground.endColor });
                    gd.setShape(GradientDrawable.RECTANGLE);
                    holder.bg.setBackground(gd);
                } else {
                    holder.bg.setBackground(null);
                    holder.bg.setBackgroundColor(mAccentColor);
                }
                // Picked: ripple in unPickerColor (light grey) on top of the
                // dark accent fill.
                setRippleColor(holder.cell, unPickerColor);
                holder.tvDate.setTextColor(Color.WHITE);
                holder.cell.setContentDescription(mCdPicked + mCds[position] + ",");
            } else {
                holder.bg.setBackground(null);
                holder.bg.setBackgroundColor(unPickerColor);
                // Unpicked: ripple uses the accent representative (single int —
                // RippleDrawable API limit; the ripple waveform itself can't
                // hold a gradient).
                setRippleColor(holder.cell, mAccentColor);
                holder.tvDate.setTextColor(black_54);
                holder.cell.setContentDescription(mCdUnpicked + mCds[position] + ",");
            }
        }
        viewHolder.itemView.setContentDescription(
                (mPicked[position] ? mCdPicked : mCdUnpicked) + mCds[position] + ",");
    }

    @Override
    public int getItemCount() {
        return mItems.length;
    }

    @Override
    public int getItemViewType(int position) {
        if (mType == Def.PickerType.DAY_OF_MONTH && position == 27) {
            return END_OF_MONTH;
        } else return NORMAL;
    }

    public List<Integer> getPickedIndexes() {
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < mPicked.length; i++) {
            if (mPicked[i]) {
                list.add(i);
            }
        }
        return list;
    }

    public int getPickedCount() {
        int count = 0;
        for (boolean b : mPicked) {
            if (b) count++;
        }
        return count;
    }

    private class NormalViewHolder extends BaseViewHolder {

        /** Outer 48dp (or 36dp for DAY_OF_MONTH) clipped-to-oval cell — owns
         *  the click + ripple foreground. */
        final FrameLayout cell;
        /** Inner View carrying the picked-state gradient or unpicked grey. */
        final View bg;
        final TextView tvDate;

        NormalViewHolder(View itemView) {
            super(itemView);
            cell   = f(R.id.fab_recurrence_picker);
            bg     = f(R.id.v_recurrence_picker_bg);
            tvDate = f(R.id.tv_recurrence_picker);

            // Phase 8: clip to oval so the inner background drawable (solid
            // or gradient rectangle) renders as a circle. Install a ripple
            // foreground so tap feedback draws over the bg + tvDate layers.
            // The ripple's mask is OVAL too — Android composites it against
            // clipToOutline cleanly.
            cell.setClipToOutline(true);
            cell.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline outline) {
                    outline.setOval(0, 0, v.getWidth(), v.getHeight());
                }
            });
            // Initial ripple — onBindViewHolder swaps colour per picked state.
            cell.setForeground(BackgroundUtil.circularRipple());

            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    togglePick(getAdapterPosition());
                    if (mOnPickListener != null) {
                        mOnPickListener.onClick(v);
                    }
                }
            });
        }
    }

    /** Update the ripple foreground's tint without re-allocating the drawable. */
    private static void setRippleColor(View cell, int color) {
        if (cell.getForeground() instanceof RippleDrawable) {
            ((RippleDrawable) cell.getForeground())
                    .setColor(ColorStateList.valueOf(color));
        }
    }

    /** Map {@link ThingBackground.Orientation} → platform GradientDrawable
     *  orientation. Mirrors {@code BackgroundUtil.toGdOrientation} (private
     *  there); duplicated here to avoid exposing the helper just for this
     *  one use site. */
    private static GradientDrawable.Orientation toGdOrientation(
            ThingBackground.Orientation o) {
        switch (o) {
            case L_R:   return GradientDrawable.Orientation.LEFT_RIGHT;
            case T_B:   return GradientDrawable.Orientation.TOP_BOTTOM;
            case LT_RB: return GradientDrawable.Orientation.TL_BR;
            case RT_LB: return GradientDrawable.Orientation.TR_BL;
            case LB_RT: return GradientDrawable.Orientation.BL_TR;
            case RB_LT: return GradientDrawable.Orientation.BR_TL;
            case R_L:   return GradientDrawable.Orientation.RIGHT_LEFT;
            case B_T:   return GradientDrawable.Orientation.BOTTOM_TOP;
        }
        return GradientDrawable.Orientation.LEFT_RIGHT;
    }

    private class EndOfMonthViewHolder extends BaseViewHolder {

        final CardView cv;
        final TextView tv;

        EndOfMonthViewHolder(View itemView) {
            super(itemView);
            cv = f(R.id.cv_end_of_month_rec);
            tv = f(R.id.tv_end_of_month_rec);

            cv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    togglePick(getAdapterPosition());
                    if (mOnPickListener != null) {
                        mOnPickListener.onClick(v);
                    }
                }
            });
        }
    }
}
