package com.ywwynm.everythingdone.fragments;

import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.model.ThingBackground;
import com.ywwynm.everythingdone.utils.BackgroundUtil;

/**
 * Phase 8: pick a new gradient direction for a GRADIENT thing. Shows a
 * gradient-tinted title and 8 mini-gradient cells laid out 2×4 (row 0 =
 * 4 cardinal directions L_R/T_B/R_L/B_T; row 1 = 4 diagonals
 * LT_RB/RT_LB/LB_RT/RB_LT). Each cell is the same fake-FAB pattern
 * ({@code color_picker_fab.xml}): a 40dp FrameLayout clipped to an oval
 * outline, with a {@link android.graphics.drawable.RippleDrawable} foreground
 * — so we get a true circular ripple drawn on top of the gradient.
 */
public class GradientOrientationDialogFragment extends BaseDialogFragment {

    public static final String TAG = "GradientOrientationDialogFragment";

    public interface OnPickListener {
        void onPicked(ThingBackground.Orientation orientation);
    }

    private ThingBackground mAccent;
    private OnPickListener mListener;

    /** Layout order: 4 cardinal directions first row, 4 diagonals second row. */
    private static final ThingBackground.Orientation[] ORDER = {
            ThingBackground.Orientation.L_R,
            ThingBackground.Orientation.T_B,
            ThingBackground.Orientation.R_L,
            ThingBackground.Orientation.B_T,
            ThingBackground.Orientation.LT_RB,
            ThingBackground.Orientation.RT_LB,
            ThingBackground.Orientation.LB_RT,
            ThingBackground.Orientation.RB_LT,
    };

    public void setAccent(ThingBackground accent) {
        mAccent = accent;
    }

    public void setOnPickListener(OnPickListener listener) {
        mListener = listener;
    }

    @Override
    protected int getLayoutResource() {
        return R.layout.fragment_gradient_orientation;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);

        if (mAccent == null || mAccent.mode != ThingBackground.Mode.GRADIENT) {
            dismiss();
            return mContentView;
        }

        TextView tvTitle = f(R.id.tv_title_gradient_orientation);
        BackgroundUtil.applyTextBackground(tvTitle, mAccent);

        GridLayout grid = f(R.id.gl_gradient_orientations);
        int columns = 4;
        int m = (int) (8 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < ORDER.length; i++) {
            ThingBackground.Orientation orientation = ORDER[i];
            View cell = inflater.inflate(R.layout.color_picker_fab, grid, false);

            // Reuse the LayoutParams that {@code inflate(parent, false)} already
            // built from color_picker_fab.xml — the cell's 40dp width/height
            // are encoded there. Creating a fresh GridLayout.LayoutParams()
            // would overwrite them with WRAP_CONTENT and the cells would blow
            // up to fill the grid.
            GridLayout.LayoutParams glp = (GridLayout.LayoutParams) cell.getLayoutParams();
            glp.rowSpec    = GridLayout.spec(i / columns);
            glp.columnSpec = GridLayout.spec(i % columns);
            glp.setMargins(m, m, m, m);

            bind(cell, orientation);
            grid.addView(cell);
        }

        return mContentView;
    }

    private void bind(View cell, final ThingBackground.Orientation orientation) {
        FrameLayout root = (FrameLayout) cell;  // cell is the FrameLayout itself
        View bg = cell.findViewById(R.id.v_color_cell_bg);
        ImageView check = cell.findViewById(R.id.iv_color_cell_check);

        // Clip to an oval so the inner gradient is round; ripple foreground
        // gives a circular press effect, mask-clipped by the same outline.
        root.setClipToOutline(true);
        root.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setOval(0, 0, v.getWidth(), v.getHeight());
            }
        });
        root.setForeground(BackgroundUtil.circularRipple());

        // Inner bg View carries the OVAL gradient with the accent's two stops
        // and the direction this cell represents. RECTANGLE shape is fine
        // because the parent clips to a circle.
        GradientDrawable gd = new GradientDrawable(
                toGdOrientation(orientation),
                new int[] { mAccent.color, mAccent.endColor });
        gd.setShape(GradientDrawable.RECTANGLE);
        bg.setBackground(gd);

        if (orientation == mAccent.orientation) {
            check.setVisibility(View.VISIBLE);
            check.setImageDrawable(tintedCheckmark());
            root.setContentDescription(getString(R.string.cd_picked)
                    + orientationContentDescription(orientation));
        } else {
            check.setVisibility(View.GONE);
            check.setImageDrawable(null);
            root.setContentDescription(orientationContentDescription(orientation));
        }

        root.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mListener != null) mListener.onPicked(orientation);
                dismiss();
            }
        });
    }

    private Drawable tintedCheckmark() {
        Drawable d = ContextCompat.getDrawable(getActivity(), R.drawable.ic_color_picked);
        if (d == null) return null;
        d = d.mutate();
        int tint = BackgroundUtil.isLight(mAccent.representativeColor())
                ? Color.BLACK : Color.WHITE;
        d.setColorFilter(tint, PorterDuff.Mode.SRC_IN);
        return d;
    }

    private String orientationContentDescription(ThingBackground.Orientation o) {
        switch (o) {
            case L_R:   return getString(R.string.cd_gradient_orientation_l_r);
            case T_B:   return getString(R.string.cd_gradient_orientation_t_b);
            case R_L:   return getString(R.string.cd_gradient_orientation_r_l);
            case B_T:   return getString(R.string.cd_gradient_orientation_b_t);
            case LT_RB: return getString(R.string.cd_gradient_orientation_lt_rb);
            case RT_LB: return getString(R.string.cd_gradient_orientation_rt_lb);
            case LB_RT: return getString(R.string.cd_gradient_orientation_lb_rt);
            case RB_LT: return getString(R.string.cd_gradient_orientation_rb_lt);
        }
        return "";
    }

    private static GradientDrawable.Orientation toGdOrientation(ThingBackground.Orientation o) {
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
}
