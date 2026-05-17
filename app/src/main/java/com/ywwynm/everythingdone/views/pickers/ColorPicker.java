package com.ywwynm.everythingdone.views.pickers;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.adapters.BaseViewHolder;
import com.ywwynm.everythingdone.adapters.SingleChoiceAdapter;
import com.ywwynm.everythingdone.model.ThingBackground;
import com.ywwynm.everythingdone.utils.DisplayUtil;

import java.util.Random;

/**
 * Created by ywwynm on 2015/8/18.
 * ColorPicker for searching in ThingsActivity and
 * changing background of thing in DetailActivity
 */
public class ColorPicker extends PopupPicker {

    public static final String TAG = "ColorPicker";

    private int[] mColors;
    private String[] mColorsNames;

    private int mType;
    private View.OnClickListener mOnClickListener;
    private ColorPickerAdapter mAdapter;
    private Rect mWindowRect;

    // Phase 6 — state for the two "random" FABs that only exist when
    // mType == COLOR_EDIT. Pre-rolled once at construction so the FABs show
    // something rather than transparent; re-rolled on each tap.
    private ThingBackground mRandomPureBg;
    private ThingBackground mRandomGradientBg;
    private final Random mRandom = new Random();

    /**
     * Representative colours for the 8 hue buckets used in
     * {@link Def.PickerType#HUE_BUCKET} mode. Index order matches
     * {@code BackgroundUtil.HUE_BUCKET_RED..GREY - 1} (i.e. RED=0, ORANGE=1, …
     * GREY=7).
     */
    private static final int[] HUE_BUCKET_COLORS = {
            0xFFE53935, // RED
            0xFFFB8C00, // ORANGE
            0xFFFDD835, // YELLOW
            0xFF43A047, // GREEN
            0xFF00ACC1, // CYAN
            0xFF1E88E5, // BLUE
            0xFF8E24AA, // PURPLE
            0xFF757575, // GREY
    };

    public ColorPicker(Activity activity, View parent, int type) {
        super(activity, parent, R.style.ColorPickerAnimation);
        if (type == Def.PickerType.HUE_BUCKET) {
            mColors      = HUE_BUCKET_COLORS;
            mColorsNames = activity.getResources()
                    .getStringArray(R.array.hue_bucket_names);
        } else {
            mColors      = activity.getResources().getIntArray(R.array.thing);
            mColorsNames = activity.getResources().getStringArray(R.array.thing_colors_names);
        }
        mType = type;
        ViewGroup.LayoutParams params = mRecyclerView.getLayoutParams();
        params.width = (int) (mScreenDensity * 128);
        if (mType == Def.PickerType.COLOR_HAVE_ALL) {
            params.height = (int) (mScreenDensity * 304);
        } else if (mType == Def.PickerType.COLOR_NO_ALL) {
            params.height = (int) (mScreenDensity * 264);
        } else if (mType == Def.PickerType.COLOR_EDIT) {
            // 10 palette FABs + 1dp divider (≈ 13 dp incl. margins) + 2 random FABs.
            params.height = (int) (mScreenDensity * 328);
        } else if (mType == Def.PickerType.HUE_BUCKET) {
            // "all" button + 8 bucket FABs in 4 rows.
            params.height = (int) (mScreenDensity * 256);
        }
        if (mType == Def.PickerType.COLOR_EDIT) {
            mRandomPureBg     = rollPureBackground();
            mRandomGradientBg = rollGradientBackground();
        }
        // For every 2 new colors you want to add, you should also add 48 dp to picker's height.
        mRecyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        mRecyclerView.setHasFixedSize(true);
        GridLayoutManager layoutManager = new GridLayoutManager(this.mActivity, 2);
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                if (mType == Def.PickerType.COLOR_HAVE_ALL
                        || mType == Def.PickerType.HUE_BUCKET) {
                    return position == 0 ? 2 : 1;
                } else if (mType == Def.PickerType.COLOR_EDIT) {
                    // The divider row spans both columns.
                    return position == mColors.length ? 2 : 1;
                } else if (mType == Def.PickerType.COLOR_NO_ALL) {
                    return 1;
                }
                return 0;
            }
        });
        mRecyclerView.setLayoutManager(layoutManager);
        mAdapter = new ColorPickerAdapter();
        mAdapter.setHasStableIds(true);
        mRecyclerView.setAdapter(mAdapter);
        mWindowRect = new Rect();
    }

    @Override
    public void show() {
        int xOffset = 0;
        int[] location = new int[2];
        mParent.getLocationOnScreen(location);
        // if we are in multi-window mode, we should detect which part we are in, left or right.
        boolean isRightWindow = location[0] != 0;

        mParent.getWindowVisibleDisplayFrame(mWindowRect);
        if (mType == Def.PickerType.COLOR_NO_ALL) {
            xOffset += (int) (mScreenDensity * 36);
            if (DisplayUtil.isTablet(this.mActivity)) {
                xOffset += (int) (mScreenDensity * 12);
            }
        }
        if (mWindowRect.right != DisplayUtil.getDisplaySize(this.mActivity).x) {
            if (isRightWindow) {
                xOffset += (int) (mScreenDensity * 40);
            }
        }

        mPopupWindow.showAtLocation(mParent, Gravity.TOP | Gravity.END,
                xOffset, DisplayUtil.getStatusbarHeight(this.mActivity));
    }

    public void setPickedListener(View.OnClickListener listener) {
        mOnClickListener = listener;
    }

    public int getPickedIndex() {
        return mAdapter.getPickedPosition();
    }

    public int getPickedColor() {
        if (mType == Def.PickerType.HUE_BUCKET) {
            // The DAO consumes the int as a hue-bucket hint via BackgroundUtil
            // .hueBucket(); returning the bucket's representative is correct.
            int picked = mAdapter.getPickedPosition();
            if (picked <= 0) return -1979711488;
            return mColors[picked - 1];
        }
        ThingBackground bg = getPickedBackground();
        return bg != null ? bg.representativeColor() : -1979711488;
    }

    /**
     * Phase 6: the picked background as a full {@link ThingBackground}.
     * For palette FABs this is {@code pure(palette color)}; for the random
     * FABs in {@link Def.PickerType#COLOR_EDIT} it returns whichever random
     * was last rolled (PURE or GRADIENT). For the "all colors" sentinel in
     * COLOR_HAVE_ALL mode this returns {@code null}, matching the legacy
     * sentinel int -1979711488.
     */
    public ThingBackground getPickedBackground() {
        int picked = mAdapter.getPickedPosition();
        if (mType == Def.PickerType.COLOR_HAVE_ALL) {
            if (picked <= 0) return null;
            return ThingBackground.pure(mColors[picked - 1]);
        }
        if (mType == Def.PickerType.COLOR_EDIT) {
            // 0..N-1 palette, N divider, N+1 random pure, N+2 random gradient
            int n = mColors.length;
            if (picked >= 0 && picked < n) return ThingBackground.pure(mColors[picked]);
            if (picked == n + 1)           return mRandomPureBg;
            if (picked == n + 2)           return mRandomGradientBg;
            return null;
        }
        if (picked < 0 || picked >= mColors.length) return null;
        return ThingBackground.pure(mColors[picked]);
    }

    /**
     * Highlight the palette / random FAB that corresponds to {@code background}.
     * COLOR_EDIT mode only:
     *   PURE in palette  → highlight that palette FAB
     *   PURE outside palette → set random-pure to this background, highlight that FAB
     *   GRADIENT          → set random-gradient to this background, highlight that FAB
     */
    public void pickForBackground(ThingBackground background) {
        if (background == null) {
            pickForUI(0);
            return;
        }
        if (mType == Def.PickerType.COLOR_EDIT) {
            if (background.mode == ThingBackground.Mode.PURE) {
                for (int i = 0; i < mColors.length; i++) {
                    if (mColors[i] == background.color) {
                        pickForUI(i);
                        return;
                    }
                }
                // Not a palette color — slot it into the random-pure FAB so the
                // user sees their current color highlighted.
                mRandomPureBg = background;
                pickForUI(mColors.length + 1);
                return;
            }
            // GRADIENT — always lives in the random-gradient FAB.
            mRandomGradientBg = background;
            pickForUI(mColors.length + 2);
            return;
        }
        // Older types — best effort by exact int match.
        int rep = background.representativeColor();
        for (int i = 0; i < mColors.length; i++) {
            if (mColors[i] == rep) {
                pickForUI(mType == Def.PickerType.COLOR_HAVE_ALL ? i + 1 : i);
                return;
            }
        }
        pickForUI(mType == Def.PickerType.COLOR_HAVE_ALL ? 0 : -1);
    }

    private ThingBackground rollPureBackground() {
        return ThingBackground.pure(randomColor());
    }

    private ThingBackground rollGradientBackground() {
        int s = randomColor();
        int e = randomColor();
        ThingBackground.Orientation[] os = ThingBackground.Orientation.values();
        return ThingBackground.gradient(s, e, os[mRandom.nextInt(os.length)]);
    }

    private int randomColor() {
        return android.graphics.Color.rgb(
                mRandom.nextInt(256), mRandom.nextInt(256), mRandom.nextInt(256));
    }

    @Override
    public void pickForUI(int index) {
        mAdapter.pick(index);
        if (mAnchor != null) {
            updateAnchor();
        }
    }

    @Override
    public void updateAnchor() {
        if (mAnchor == null) return;
        if (mType == Def.PickerType.COLOR_HAVE_ALL
                || mType == Def.PickerType.HUE_BUCKET) {
            int filterColor = getPickedColor();
            // -1979711488 ≈ 0x8A000000 is the "all colours" sentinel; for HUE_BUCKET
            // the anchor should appear dark/neutral when nothing is filtering, then
            // tint to the picked bucket's representative when a bucket is chosen.
            ((Drawable) mAnchor).mutate().setColorFilter(filterColor, PorterDuff.Mode.SRC_ATOP);
        }
    }

    private class ColorPickerAdapter extends SingleChoiceAdapter {

        static final int ALL_COLOR = 0;
        static final int NORMAL    = 1;
        static final int DIVIDER   = 2;  // COLOR_EDIT only

        private LayoutInflater mInflater;

        ColorPickerAdapter() {
            mInflater = LayoutInflater.from(ColorPicker.this.mActivity);
        }

        @Override
        public BaseViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            if (viewType == ALL_COLOR) {
                return new AllColorViewHolder(
                        mInflater.inflate(R.layout.color_picker_bt, parent, false));
            } else if (viewType == DIVIDER) {
                return new BaseViewHolder(
                        mInflater.inflate(R.layout.color_picker_divider, parent, false));
            } else {
                return new FabViewHolder(
                        mInflater.inflate(R.layout.color_picker_fab, parent, false));
            }
        }

        @Override
        public void onBindViewHolder(BaseViewHolder viewHolder, int position) {
            if (mType == Def.PickerType.HUE_BUCKET) {
                if (position == 0) {
                    bindAllColor(viewHolder);
                } else {
                    setFab(viewHolder, position);
                }
                return;
            }
            if (mType == Def.PickerType.COLOR_HAVE_ALL) {
                if (position == 0) {
                    bindAllColor(viewHolder);
                } else {
                    setFab(viewHolder, position);
                }
            } else if (mType == Def.PickerType.COLOR_NO_ALL) {
                setFab(viewHolder, position);
            } else if (mType == Def.PickerType.COLOR_EDIT) {
                if (position < mColors.length) {
                    setFab(viewHolder, position);
                } else if (position == mColors.length) {
                    // Divider — no bind work; the layout is purely visual.
                } else {
                    // position == N+1 → random pure
                    // position == N+2 → random gradient
                    setRandomFab((FabViewHolder) viewHolder, position);
                }
            }
        }

        private void bindAllColor(BaseViewHolder viewHolder) {
            AllColorViewHolder holder = (AllColorViewHolder) viewHolder;
            if (mPickedPosition == 0) {
                holder.bt.setCompoundDrawablesWithIntrinsicBounds(
                        R.drawable.ic_checkbox_checked, 0, 0, 0);
                holder.bt.setContentDescription(
                        mActivity.getString(R.string.cd_picked) + holder.bt.getText() + ",");
            } else {
                holder.bt.setCompoundDrawablesWithIntrinsicBounds(
                        R.drawable.ic_checkbox_unchecked, 0, 0, 0);
                holder.bt.setContentDescription(
                        mActivity.getString(R.string.cd_unpicked) + holder.bt.getText() + ",");
            }
            holder.bt.setClickable(mPickedPosition != 0);
        }

        private void setFab(RecyclerView.ViewHolder viewHolder, int position) {
            FabViewHolder holder = (FabViewHolder) viewHolder;
            int index = (mType == Def.PickerType.COLOR_HAVE_ALL
                    || mType == Def.PickerType.HUE_BUCKET) ? position - 1 : position;
            int color = mColors[index];
            holder.fab.setBackgroundTintList(ColorStateList.valueOf(color));
            // Hide any gradient overlay leftover from a previous ViewHolder bind.
            holder.overlay.setVisibility(View.GONE);
            holder.overlay.setBackground(null);
            holder.overlay.setImageDrawable(null);
            setFabMargin(holder.itemView, index);
            if (mPickedPosition == position) {
                holder.fab.setImageDrawable(tintedCheckmark(color));
                holder.fab.setContentDescription(
                        mActivity.getString(R.string.cd_picked) + mColorsNames[index] + ",");
            } else {
                holder.fab.setImageDrawable(null);
                holder.fab.setContentDescription(
                        mActivity.getString(R.string.cd_unpicked) + mColorsNames[index] + ",");
            }
            holder.fab.setClickable(mPickedPosition != position);
        }

        /**
         * Return {@code ic_color_picked} tinted black on light FABs (so the
         * checkmark stays visible on pale colours like yellow) and white otherwise.
         * Phase 6 fix #5.
         */
        private android.graphics.drawable.Drawable tintedCheckmark(int fabColor) {
            android.graphics.drawable.Drawable d = ContextCompat.getDrawable(
                    mActivity, R.drawable.ic_color_picked);
            if (d == null) return null;
            d = d.mutate();
            int tint = com.ywwynm.everythingdone.utils.BackgroundUtil.isLight(fabColor)
                    ? android.graphics.Color.BLACK
                    : android.graphics.Color.WHITE;
            d.setColorFilter(tint, android.graphics.PorterDuff.Mode.SRC_IN);
            return d;
        }

        /**
         * Render one of the two trailing "random" FABs in COLOR_EDIT mode.
         * Random-pure FAB looks like an ordinary coloured palette FAB (no icon).
         * Random-gradient FAB shows a real two-colour circular GradientDrawable
         * so the user can see what the rolled gradient looks like before picking.
         * Tapping either re-rolls and re-selects.
         */
        private void setRandomFab(FabViewHolder holder, int position) {
            boolean isGradient = position == mColors.length + 2;
            ThingBackground bg = isGradient ? mRandomGradientBg : mRandomPureBg;
            setFabMargin(holder.itemView, position);

            if (isGradient && bg != null) {
                // Material FAB.setBackground is a no-op (logs a warning), so we
                // render the gradient via the sibling overlay ImageView: make the
                // FAB itself transparent and let the overlay carry the drawable.
                holder.fab.setBackgroundTintList(
                        ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
                holder.fab.setImageDrawable(null);

                android.graphics.drawable.GradientDrawable gd =
                        new android.graphics.drawable.GradientDrawable(
                                toGdOrientation(bg.orientation),
                                new int[] { bg.color, bg.endColor });
                gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                holder.overlay.setVisibility(View.VISIBLE);
                holder.overlay.setBackground(gd);
                holder.overlay.setImageDrawable(mPickedPosition == position
                        ? tintedCheckmark(bg.representativeColor())
                        : null);
            } else {
                int representative = bg != null ? bg.representativeColor() : 0;
                // Pure random — back to the regular FAB tint approach, hide overlay.
                holder.fab.setBackgroundTintList(ColorStateList.valueOf(representative));
                holder.overlay.setVisibility(View.GONE);
                holder.overlay.setBackground(null);
                holder.overlay.setImageDrawable(null);
                // Random-pure FAB: no icon when unpicked (so it visually distinguishes
                // from palette FABs by colour alone, not by an icon overlay).
                if (mPickedPosition == position) {
                    holder.fab.setImageDrawable(tintedCheckmark(representative));
                } else {
                    holder.fab.setImageDrawable(null);
                }
            }
            holder.fab.setContentDescription(mActivity.getString(
                    isGradient ? R.string.cd_random_gradient_background
                               : R.string.cd_random_pure_background));
            holder.fab.setClickable(true); // always re-rollable on tap
        }

        // Inlined copy of BackgroundUtil's enum mapping to avoid pulling
        // BackgroundUtil into this UI class. Identical mapping.
        private static android.graphics.drawable.GradientDrawable.Orientation
                toGdOrientation(ThingBackground.Orientation o) {
            switch (o) {
                case L_R:   return android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT;
                case T_B:   return android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM;
                case LT_RB: return android.graphics.drawable.GradientDrawable.Orientation.TL_BR;
                case RT_LB: return android.graphics.drawable.GradientDrawable.Orientation.TR_BL;
                case LB_RT: return android.graphics.drawable.GradientDrawable.Orientation.BL_TR;
                case RB_LT: return android.graphics.drawable.GradientDrawable.Orientation.BR_TL;
                case R_L:   return android.graphics.drawable.GradientDrawable.Orientation.RIGHT_LEFT;
                case B_T:   return android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP;
            }
            return android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT;
        }

        /**
         * Apply the per-position grid margins. After Phase 6 fix round 3 the item
         * root view is the wrapper FrameLayout (the FAB has been demoted to a
         * child), so its layout params are the GridLayoutManager.LayoutParams —
         * not the FAB's own (which are FrameLayout.LayoutParams).
         */
        private void setFabMargin(View itemRoot, int index) {
            int m8 = (int) (8 * mScreenDensity);
            int m4 = m8 >> 1;
            int m16 = m8 << 1;
            GridLayoutManager.LayoutParams params =
                    (GridLayoutManager.LayoutParams) itemRoot.getLayoutParams();
            switch (index) {
                case 0:
                    if (mType == Def.PickerType.COLOR_HAVE_ALL) {
                        params.setMargins(m16, m8, m8, m4);
                    } else {
                        params.setMargins(m16, m16, m8, m4);
                    }
                    break;
                case 1:
                    if (mType == Def.PickerType.COLOR_HAVE_ALL) {
                        params.setMargins(m8, m8, m16, m4);
                    } else {
                        params.setMargins(m8, m16, m16, m4);
                    }
                    break;
                case 2:
                    params.setMargins(m16, m4, m8, m4);
                    break;
                case 3:
                    params.setMargins(m8, m4, m16, m4);
                    break;
                case 4:
                    params.setMargins(m16, m4, m8, m4);
                    break;
                case 5:
                    params.setMargins(m8, m4, m16, m4);
                    break;
                case 6:
                    // HUE_BUCKET's last row is at FAB indices 6/7 (8 buckets).
                    if (mType == Def.PickerType.HUE_BUCKET) {
                        params.setMargins(m16, m4, m8, m16);
                    } else {
                        params.setMargins(m16, m4, m8, m4);
                    }
                    break;
                case 7:
                    if (mType == Def.PickerType.HUE_BUCKET) {
                        params.setMargins(m8, m4, m16, m16);
                    } else {
                        params.setMargins(m8, m4, m16, m4);
                    }
                    break;
                case 8:
                    // Aein_red's row sits above the random row in COLOR_EDIT, so it
                    // mustn't carry the larger bottom margin (which would otherwise
                    // stretch the cell taller than its siblings).
                    if (mType == Def.PickerType.COLOR_EDIT) {
                        params.setMargins(m16, m4, m8, m4);
                    } else {
                        params.setMargins(m16, m4, m8, m16);
                    }
                    break;
                case 9:
                    if (mType == Def.PickerType.COLOR_EDIT) {
                        params.setMargins(m8, m4, m16, m4);
                    } else {
                        params.setMargins(m8, m4, m16, m16);
                    }
                    break;
                // Adapter positions 11/12 are the trailing random FABs in
                // COLOR_EDIT mode (after the divider at position 10). They get
                // the m16 bottom margin since they're the last row.
                case 11:  // random pure
                    params.setMargins(m16, m4, m8, m16);
                    break;
                case 12:  // random gradient
                    params.setMargins(m8, m4, m16, m16);
                    break;
                default:break;
            }
            params.setMarginStart(params.leftMargin);
            params.setMarginEnd(params.rightMargin);
        }

        @Override
        public int getItemCount() {
            if (mType == Def.PickerType.COLOR_HAVE_ALL
                    || mType == Def.PickerType.HUE_BUCKET) {
                return mColors.length + 1;
            } else if (mType == Def.PickerType.COLOR_NO_ALL) {
                return mColors.length;
            } else if (mType == Def.PickerType.COLOR_EDIT) {
                return mColors.length + 3;  // + divider + random-pure + random-gradient
            }
            return 0;
        }

        @Override
        public int getItemViewType(int position) {
            if (mType == Def.PickerType.COLOR_HAVE_ALL
                    || mType == Def.PickerType.HUE_BUCKET) {
                return position == 0 ? ALL_COLOR : NORMAL;
            } else if (mType == Def.PickerType.COLOR_NO_ALL) {
                return NORMAL;
            } else if (mType == Def.PickerType.COLOR_EDIT) {
                if (position == mColors.length) return DIVIDER;
                return NORMAL;
            }
            return super.getItemViewType(position);
        }

        class AllColorViewHolder extends BaseViewHolder {

            final Button bt;

            AllColorViewHolder(View itemView) {
                super(itemView);
                bt = f(R.id.bt_all_color);
                bt.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        mPopupWindow.dismiss();
                        pickForUI(0);
                        if (mOnClickListener != null) {
                            mOnClickListener.onClick(v);
                        }
                    }
                });
            }
        }

        class FabViewHolder extends BaseViewHolder {

            final FloatingActionButton fab;
            /** Overlay ImageView used to render gradient backgrounds — sibling of the FAB. */
            final android.widget.ImageView overlay;

            FabViewHolder(View itemView) {
                super(itemView);
                fab     = f(R.id.fab_pick_color);
                overlay = f(R.id.iv_pick_color_overlay);
                fab.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        int pos = getAdapterPosition();
                        // Phase 6 fix round 4: only re-roll when the user taps an
                        // already-picked random FAB. First tap commits the colour
                        // the user can SEE in the FAB, so display == result. Tap
                        // a second time to shuffle to a new random.
                        if (mType == Def.PickerType.COLOR_EDIT && mPickedPosition == pos) {
                            if (pos == mColors.length + 1) {
                                mRandomPureBg     = rollPureBackground();
                            } else if (pos == mColors.length + 2) {
                                mRandomGradientBg = rollGradientBackground();
                            }
                        }
                        mPopupWindow.dismiss();
                        pickForUI(pos);
                        if (mOnClickListener != null) {
                            mOnClickListener.onClick(v);
                        }
                    }
                });
            }
        }
    }
}
