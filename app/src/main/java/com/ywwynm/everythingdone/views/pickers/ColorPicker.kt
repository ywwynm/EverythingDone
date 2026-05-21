@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.views.pickers

import android.app.Activity
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button

import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.adapters.BaseViewHolder
import com.ywwynm.everythingdone.adapters.SingleChoiceAdapter
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.DisplayUtil

import java.util.Random

/**
 * Created by ywwynm on 2015/8/18.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * ColorPicker for searching in ThingsActivity and
 * changing background of thing in DetailActivity
 */
open class ColorPicker(
        activity: Activity, parent: View, type: Int
) : PopupPicker(activity, parent, R.style.ColorPickerAnimation) {

    private val mColors: IntArray
    private val mColorsNames: Array<String>

    private val mType: Int = type
    private var mOnClickListener: View.OnClickListener? = null
    private val mAdapter: ColorPickerAdapter
    private val mWindowRect: Rect

    // Phase 6 — state for the two "random" FABs that only exist when
    // mType == COLOR_EDIT. Pre-rolled once at construction so the FABs show
    // something rather than transparent; re-rolled on each tap.
    private var mRandomPureBg: ThingBackground? = null
    private var mRandomGradientBg: ThingBackground? = null
    private val mRandom: Random = Random()

    /** Phase 8: bottom button to open the gradient-orientation dialog.
     *  Only visible in COLOR_EDIT mode when the current pick is a GRADIENT. */
    private val mOrientationBt: android.widget.TextView?
    private var mOnChangeOrientationListener: Runnable? = null

    /**
     * Optional [Drawable] that gets re-tinted to track the picked colour.
     * Currently used by ThingsActivity's search-mode picker, where the
     * toolbar icon's drawable should reflect the active hue bucket.
     *
     * Distinct from [mAnchor]: mAnchor controls *where* the
     * popup appears; mTintTarget controls *what gets re-coloured* on
     * each pick. The two used to overload a single Object field, which is why
     * older callers passed a Drawable to `setAnchor` — that wiring is
     * being unwound.
     */
    private var mTintTarget: Drawable? = null

    /** See [mTintTarget]. */
    fun setTintTarget(target: Drawable) {
        mTintTarget = target
    }

    init {
        if (type == Def.PickerType.HUE_BUCKET) {
            mColors = HUE_BUCKET_COLORS
            mColorsNames = activity.resources!!
                    .getStringArray(R.array.hue_bucket_names)!!
        } else {
            mColors = activity.resources!!.getIntArray(R.array.thing)!!
            mColorsNames = activity.resources!!.getStringArray(R.array.thing_colors_names)!!
        }
        val params: ViewGroup.LayoutParams = mRecyclerView.layoutParams!!
        params.width = (mScreenDensity * 128).toInt()
        when (mType) {
            Def.PickerType.COLOR_HAVE_ALL -> {
                params.height = (mScreenDensity * 304).toInt()
            }
            Def.PickerType.COLOR_NO_ALL -> {
                params.height = (mScreenDensity * 264).toInt()
            }
            Def.PickerType.COLOR_EDIT -> {
                // 10 palette FABs + 1dp divider (≈ 13 dp incl. margins) + 2 random FABs.
                params.height = (mScreenDensity * 328).toInt()
            }
            Def.PickerType.HUE_BUCKET -> {
                // "all" button + 8 bucket FABs in 4 rows.
                params.height = (mScreenDensity * 256).toInt()
            }
        }
        if (mType == Def.PickerType.COLOR_EDIT) {
            mRandomPureBg = rollPureBackground()
            mRandomGradientBg = rollGradientBackground()
        }
        // For every 2 new colors you want to add, you should also add 48 dp to picker's height.
        mRecyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER)
        mRecyclerView.setHasFixedSize(true)
        val layoutManager = GridLayoutManager(this.mActivity, 2)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                when (mType) {
                    Def.PickerType.COLOR_HAVE_ALL, Def.PickerType.HUE_BUCKET -> {
                        return if (position == 0) 2 else 1
                    }
                    Def.PickerType.COLOR_EDIT -> {
                        // The divider row spans both columns.
                        return if (position == mColors.size) 2 else 1
                    }
                    Def.PickerType.COLOR_NO_ALL -> {
                        return 1
                    }
                    else -> return 0
                }
            }
        }
        mRecyclerView.setLayoutManager(layoutManager)
        mAdapter = ColorPickerAdapter()
        mAdapter.setHasStableIds(true)
        mRecyclerView.setAdapter(mAdapter)
        mWindowRect = Rect()

        // Phase 8: the COLOR_EDIT popup hosts a "change gradient direction"
        // button beneath the colour grid. Hidden by default; only shown when
        // the current pick is a GRADIENT background.
        mOrientationBt = mContentView.findViewById(R.id.bt_change_gradient_orientation)
        if (mOrientationBt != null && mType == Def.PickerType.COLOR_EDIT) {
            mOrientationBt.setText(R.string.act_change_gradient_orientation)
            mOrientationBt.setOnClickListener(object : View.OnClickListener {
                override fun onClick(v: View) {
                    // Phase 8: dismiss the popup first so the orientation
                    // dialog opens against a clean window — otherwise the
                    // dialog ends up below the picker and the user has to
                    // tap-outside the popup before reaching it.
                    dismiss()
                    mOnChangeOrientationListener?.run()
                }
            })
        }
    }

    /**
     * Phase 8: register the callback fired when the user taps "change gradient
     * direction" at the bottom of the COLOR_EDIT picker. DetailActivity uses
     * this to launch [com.ywwynm.everythingdone.fragments.GradientOrientationDialogFragment].
     */
    fun setOnChangeOrientationListener(listener: Runnable) {
        mOnChangeOrientationListener = listener
    }

    /**
     * Phase 8: refresh the bottom orientation button — show + tint when current
     * pick is a GRADIENT, hide otherwise. Called by the picker itself after each
     * `pickForBackground`, and by DetailActivity after externally driven
     * colour changes (undo/redo, orientation dialog commit).
     */
    fun refreshOrientationBt() {
        if (mOrientationBt == null || mType != Def.PickerType.COLOR_EDIT) return
        val bg: ThingBackground? = getPickedBackground()
        if (bg != null && bg.mode == ThingBackground.Mode.GRADIENT) {
            mOrientationBt.visibility = View.VISIBLE
            // Tint the button text to match the gradient (uses applyTextBackground
            // for a shader on the rendered glyphs).
            com.ywwynm.everythingdone.utils.BackgroundUtil.applyTextBackground(
                    mOrientationBt, bg)
        } else {
            mOrientationBt.visibility = View.GONE
        }
    }

    override fun show() {
        var xOffset = 0
        val location = IntArray(2)
        mParent.getLocationOnScreen(location)
        // if we are in multi-window mode, we should detect which part we are in, left or right.
        val isRightWindow: Boolean = location[0] != 0

        mParent.getWindowVisibleDisplayFrame(mWindowRect)
        if (mType == Def.PickerType.COLOR_NO_ALL) {
            xOffset += (mScreenDensity * 36).toInt()
            if (DisplayUtil.isTablet(this.mActivity)) {
                xOffset += (mScreenDensity * 12).toInt()
            }
        }
        if (mWindowRect.right != DisplayUtil.getDisplaySize(this.mActivity)!!.x) {
            if (isRightWindow) {
                xOffset += (mScreenDensity * 40).toInt()
            }
        }

        // When an anchor view is registered, align the popup's end edge to the
        // anchor's end edge (in window coordinates) instead of the window's
        // own end. Needed wherever the picker's trigger icon isn't the
        // rightmost action — e.g. DetailActivity's actionbar where it sits
        // to the left of undo / redo / overflow. On screens where the icon
        // is already the last item (ThingsActivity search), this lands at
        // xOffset = 0, same as the legacy "pin to window end" path.
        //
        // Uses getLocationInWindow + mParent.getWidth() rather than
        // getLocationOnScreen + display.x so multi-window splits compute
        // correctly: the popup is positioned relative to its parent window,
        // not the whole display.
        val anchor: View? = mAnchor
        if (anchor != null && anchor.isAttachedToWindow) {
            val anchorLoc = IntArray(2)
            anchor.getLocationInWindow(anchorLoc)
            val anchorRightInWindow: Int = anchorLoc[0] + anchor.width
            xOffset = mParent.width - anchorRightInWindow
        }

        mPopupWindow.showAtLocation(mParent, Gravity.TOP or Gravity.END,
                xOffset, DisplayUtil.getStatusbarHeight(this.mActivity))
    }

    fun setPickedListener(listener: View.OnClickListener) {
        mOnClickListener = listener
    }

    override fun getPickedIndex(): Int {
        return mAdapter.getPickedPosition()
    }

    fun getPickedColor(): Int {
        if (mType == Def.PickerType.HUE_BUCKET) {
            // The DAO consumes the int as a hue-bucket hint via BackgroundUtil
            // .hueBucket(); returning the bucket's representative is correct.
            val picked: Int = mAdapter.getPickedPosition()
            if (picked <= 0) return -1979711488
            return mColors[picked - 1]
        }
        val bg: ThingBackground? = getPickedBackground()
        return bg?.representativeColor() ?: -1979711488
    }

    /**
     * Phase 6: the picked background as a full [ThingBackground].
     * For palette FABs this is `pure(palette color)`; for the random
     * FABs in [Def.PickerType.COLOR_EDIT] it returns whichever random
     * was last rolled (PURE or GRADIENT). For the "all colors" sentinel in
     * COLOR_HAVE_ALL mode this returns `null`, matching the legacy
     * sentinel int -1979711488.
     */
    fun getPickedBackground(): ThingBackground? {
        val picked: Int = mAdapter.getPickedPosition()
        if (mType == Def.PickerType.COLOR_HAVE_ALL) {
            if (picked <= 0) return null
            return ThingBackground.pure(mColors[picked - 1])
        }
        if (mType == Def.PickerType.COLOR_EDIT) {
            // 0..N-1 palette, N divider, N+1 random pure, N+2 random gradient
            val n: Int = mColors.size
            if (picked in 0 until n) return ThingBackground.pure(mColors[picked])
            if (picked == n + 1) return mRandomPureBg
            if (picked == n + 2) return mRandomGradientBg
            return null
        }
        if (picked < 0 || picked >= mColors.size) return null
        return ThingBackground.pure(mColors[picked])
    }

    /**
     * Highlight the palette / random FAB that corresponds to `background`.
     * COLOR_EDIT mode only:
     *   PURE in palette  → highlight that palette FAB
     *   PURE outside palette → set random-pure to this background, highlight that FAB
     *   GRADIENT          → set random-gradient to this background, highlight that FAB
     */
    fun pickForBackground(background: ThingBackground?) {
        if (background == null) {
            pickForUI(0)
            return
        }
        if (mType == Def.PickerType.COLOR_EDIT) {
            if (background.mode == ThingBackground.Mode.PURE) {
                for (i in mColors.indices) {
                    if (mColors[i] == background.color) {
                        pickForUI(i)
                        return
                    }
                }
                // Not a palette color — slot it into the random-pure FAB so the
                // user sees their current color highlighted.
                mRandomPureBg = background
                pickForUI(mColors.size + 1)
                return
            }
            // GRADIENT — always lives in the random-gradient FAB.
            mRandomGradientBg = background
            pickForUI(mColors.size + 2)
            return
        }
        // Older types — best effort by exact int match.
        val rep: Int = background.representativeColor()
        for (i in mColors.indices) {
            if (mColors[i] == rep) {
                pickForUI(if (mType == Def.PickerType.COLOR_HAVE_ALL) i + 1 else i)
                return
            }
        }
        pickForUI(if (mType == Def.PickerType.COLOR_HAVE_ALL) 0 else -1)
    }

    private fun rollPureBackground(): ThingBackground {
        return ThingBackground.pure(randomColor())!!
    }

    private fun rollGradientBackground(): ThingBackground {
        val s: Int = randomColor()
        val e: Int = randomColor()
        val os: Array<ThingBackground.Orientation> =
            ThingBackground.Orientation.entries.toTypedArray()
        return ThingBackground.gradient(s, e, os[mRandom.nextInt(os.size)])!!
    }

    private fun randomColor(): Int {
        return android.graphics.Color.rgb(
                mRandom.nextInt(256), mRandom.nextInt(256), mRandom.nextInt(256))
    }

    override fun pickForUI(index: Int) {
        mAdapter.pick(index)
        // Phase 8: pick may have changed mode (PURE↔GRADIENT) — refresh the
        // bottom orientation button visibility accordingly.
        refreshOrientationBt()
        if (mTintTarget != null) {
            updateAnchor()
        }
    }

    override fun updateAnchor() {
        if (mTintTarget == null) return
        if (mType == Def.PickerType.COLOR_HAVE_ALL
                || mType == Def.PickerType.HUE_BUCKET) {
            val filterColor: Int = getPickedColor()
            // -1979711488 ≈ 0x8A000000 is the "all colours" sentinel; for HUE_BUCKET
            // the target icon should appear dark/neutral when nothing is filtering,
            // then tint to the picked bucket's representative when a bucket is chosen.
            mTintTarget!!.mutate()!!.setColorFilter(filterColor, PorterDuff.Mode.SRC_ATOP)
        }
    }

    private inner class ColorPickerAdapter : SingleChoiceAdapter() {

        private val mInflater: LayoutInflater = LayoutInflater.from(this@ColorPicker.mActivity)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
            return when (viewType) {
                ALL_COLOR -> AllColorViewHolder(
                    mInflater.inflate(R.layout.color_picker_bt, parent, false))
                DIVIDER -> BaseViewHolder(
                    mInflater.inflate(R.layout.color_picker_divider, parent, false))
                else -> FabViewHolder(
                    mInflater.inflate(R.layout.color_picker_fab, parent, false))
            }
        }

        override fun onBindViewHolder(viewHolder: BaseViewHolder, position: Int) {
            if (mType == Def.PickerType.HUE_BUCKET) {
                if (position == 0) {
                    bindAllColor(viewHolder)
                } else {
                    setFab(viewHolder, position)
                }
                return
            }
            if (mType == Def.PickerType.COLOR_HAVE_ALL) {
                if (position == 0) {
                    bindAllColor(viewHolder)
                } else {
                    setFab(viewHolder, position)
                }
            } else if (mType == Def.PickerType.COLOR_NO_ALL) {
                setFab(viewHolder, position)
            } else if (mType == Def.PickerType.COLOR_EDIT) {
                if (position < mColors.size) {
                    setFab(viewHolder, position)
                } else if (position == mColors.size) {
                    // Divider — no bind work; the layout is purely visual.
                } else {
                    // position == N+1 → random pure
                    // position == N+2 → random gradient
                    setRandomFab(viewHolder as FabViewHolder, position)
                }
            }
        }

        private fun bindAllColor(viewHolder: BaseViewHolder) {
            val holder: AllColorViewHolder = viewHolder as AllColorViewHolder
            if (mPickedPosition == 0) {
                holder.bt.setCompoundDrawablesWithIntrinsicBounds(
                        R.drawable.ic_checkbox_checked, 0, 0, 0)
                holder.bt.setContentDescription(
                        mActivity.getString(R.string.cd_picked) + holder.bt.getText() + ",")
            } else {
                holder.bt.setCompoundDrawablesWithIntrinsicBounds(
                        R.drawable.ic_checkbox_unchecked, 0, 0, 0)
                holder.bt.setContentDescription(
                        mActivity.getString(R.string.cd_unpicked) + holder.bt.getText() + ",")
            }
            holder.bt.isClickable = mPickedPosition != 0
        }

        private fun setFab(viewHolder: RecyclerView.ViewHolder, position: Int) {
            val holder: FabViewHolder = viewHolder as FabViewHolder
            val index: Int = if (mType == Def.PickerType.COLOR_HAVE_ALL
                    || mType == Def.PickerType.HUE_BUCKET) position - 1 else position
            val color: Int = mColors[index]
            // Phase 8 round 2: cell is now a clipped FrameLayout. Solid colour
            // goes on the inner background View; checkmark on the inner
            // ImageView. Ripple is the cell's own foreground (set in onCreate).
            holder.bg.setBackgroundColor(color)
            setFabMargin(holder.itemView, index)
            if (mPickedPosition == position) {
                holder.check.setVisibility(View.VISIBLE)
                holder.check.setImageDrawable(tintedCheckmark(color))
                holder.cell.setContentDescription(
                        mActivity.getString(R.string.cd_picked) + mColorsNames[index] + ",")
            } else {
                holder.check.setVisibility(View.GONE)
                holder.check.setImageDrawable(null)
                holder.cell.setContentDescription(
                        mActivity.getString(R.string.cd_unpicked) + mColorsNames[index] + ",")
            }
            holder.cell.isClickable = mPickedPosition != position
        }

        /**
         * Return `ic_color_picked` tinted black on light FABs (so the
         * checkmark stays visible on pale colours like yellow) and white otherwise.
         * Phase 6 fix #5.
         */
        private fun tintedCheckmark(fabColor: Int): Drawable? {
            val raw: Drawable = ContextCompat.getDrawable(
                    mActivity, R.drawable.ic_color_picked) ?: return null
            val d: Drawable = raw.mutate()
            val tint: Int = if (com.ywwynm.everythingdone.utils.BackgroundUtil.isLight(fabColor))
                    android.graphics.Color.BLACK
                    else android.graphics.Color.WHITE
            d.setColorFilter(tint, PorterDuff.Mode.SRC_IN)
            return d
        }

        /**
         * Render one of the two trailing "random" FABs in COLOR_EDIT mode.
         * Random-pure FAB looks like an ordinary coloured palette FAB (no icon).
         * Random-gradient FAB shows a real two-colour circular GradientDrawable
         * so the user can see what the rolled gradient looks like before picking.
         * Tapping either re-rolls and re-selects.
         */
        private fun setRandomFab(holder: FabViewHolder, position: Int) {
            val isGradient: Boolean = position == mColors.size + 2
            val bg: ThingBackground? = if (isGradient) mRandomGradientBg else mRandomPureBg
            setFabMargin(holder.itemView, position)

            if (isGradient && bg != null) {
                // Gradient on the inner bg View. The cell's clipToOutline=oval
                // crops it to a circle; cell's foreground RippleDrawable draws
                // the press feedback on top.
                val gd: android.graphics.drawable.GradientDrawable =
                        android.graphics.drawable.GradientDrawable(
                                toGdOrientation(bg.orientation),
                                intArrayOf(bg.color, bg.endColor))
                gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE)
                holder.bg.background = gd
            } else {
                val representative: Int = bg?.representativeColor() ?: 0
                holder.bg.setBackgroundColor(representative)
            }
            val rep: Int = bg?.representativeColor() ?: 0
            if (mPickedPosition == position) {
                holder.check.setVisibility(View.VISIBLE)
                holder.check.setImageDrawable(tintedCheckmark(rep))
            } else {
                holder.check.setVisibility(View.GONE)
                holder.check.setImageDrawable(null)
            }
            holder.cell.setContentDescription(mActivity.getString(
                    if (isGradient) R.string.cd_random_gradient_background
                                  else R.string.cd_random_pure_background))
            holder.cell.isClickable = true // always re-rollable on tap
        }

        /**
         * Apply the per-position grid margins. After Phase 6 fix round 3 the item
         * root view is the wrapper FrameLayout (the FAB has been demoted to a
         * child), so its layout params are the GridLayoutManager.LayoutParams —
         * not the FAB's own (which are FrameLayout.LayoutParams).
         */
        private fun setFabMargin(itemRoot: View, index: Int) {
            val m8: Int = (8 * mScreenDensity).toInt()
            val m4: Int = m8 shr 1
            val m16: Int = m8 shl 1
            val params: GridLayoutManager.LayoutParams =
                    itemRoot.layoutParams as GridLayoutManager.LayoutParams
            when (index) {
                0 ->
                    if (mType == Def.PickerType.COLOR_HAVE_ALL) {
                        params.setMargins(m16, m8, m8, m4)
                    } else {
                        params.setMargins(m16, m16, m8, m4)
                    }
                1 ->
                    if (mType == Def.PickerType.COLOR_HAVE_ALL) {
                        params.setMargins(m8, m8, m16, m4)
                    } else {
                        params.setMargins(m8, m16, m16, m4)
                    }
                2 -> params.setMargins(m16, m4, m8, m4)
                3 -> params.setMargins(m8, m4, m16, m4)
                4 -> params.setMargins(m16, m4, m8, m4)
                5 -> params.setMargins(m8, m4, m16, m4)
                6 ->
                    // HUE_BUCKET's last row is at FAB indices 6/7 (8 buckets).
                    if (mType == Def.PickerType.HUE_BUCKET) {
                        params.setMargins(m16, m4, m8, m16)
                    } else {
                        params.setMargins(m16, m4, m8, m4)
                    }
                7 ->
                    if (mType == Def.PickerType.HUE_BUCKET) {
                        params.setMargins(m8, m4, m16, m16)
                    } else {
                        params.setMargins(m8, m4, m16, m4)
                    }
                8 ->
                    // Aein_red's row sits above the random row in COLOR_EDIT, so it
                    // mustn't carry the larger bottom margin (which would otherwise
                    // stretch the cell taller than its siblings).
                    if (mType == Def.PickerType.COLOR_EDIT) {
                        params.setMargins(m16, m4, m8, m4)
                    } else {
                        params.setMargins(m16, m4, m8, m16)
                    }
                9 ->
                    if (mType == Def.PickerType.COLOR_EDIT) {
                        params.setMargins(m8, m4, m16, m4)
                    } else {
                        params.setMargins(m8, m4, m16, m16)
                    }
                // Adapter positions 11/12 are the trailing random FABs in
                // COLOR_EDIT mode (after the divider at position 10). They get
                // the m16 bottom margin since they're the last row.
                11 ->  // random pure
                    params.setMargins(m16, m4, m8, m16)
                12 ->  // random gradient
                    params.setMargins(m8, m4, m16, m16)
                else -> { }
            }
            params.setMarginStart(params.leftMargin)
            params.setMarginEnd(params.rightMargin)
        }

        override fun getItemCount(): Int {
            return when (mType) {
                Def.PickerType.COLOR_HAVE_ALL, Def.PickerType.HUE_BUCKET -> mColors.size + 1
                Def.PickerType.COLOR_NO_ALL -> mColors.size
                Def.PickerType.COLOR_EDIT -> mColors.size + 3  // + divider + random-pure + random-gradient
                else -> 0
            }
        }

        override fun getItemViewType(position: Int): Int {
            return when (mType) {
                Def.PickerType.COLOR_HAVE_ALL, Def.PickerType.HUE_BUCKET -> {
                    if (position == 0) ALL_COLOR else NORMAL
                }
                Def.PickerType.COLOR_NO_ALL -> NORMAL
                Def.PickerType.COLOR_EDIT -> {
                    if (position == mColors.size) DIVIDER else NORMAL
                }
                else -> super.getItemViewType(position)
            }
        }

        inner class AllColorViewHolder(itemView: View) : BaseViewHolder(itemView) {

            val bt: Button = f(R.id.bt_all_color)

            init {
                bt.setOnClickListener(object : View.OnClickListener {
                    override fun onClick(v: View) {
                        mPopupWindow.dismiss()
                        pickForUI(0)
                        mOnClickListener?.onClick(v)
                    }
                })
            }
        }

        inner class FabViewHolder(itemView: View) : BaseViewHolder(itemView) {

            /** Outer 40dp clipped-to-oval cell — owns the click + ripple. */
            val cell: android.widget.FrameLayout = f(R.id.fl_color_cell)

            /** Inner View carrying the solid/gradient background. */
            val bg: View
            /** Inner ImageView for the picked checkmark; scaleType=center keeps 24dp intrinsic. */
            val check: android.widget.ImageView

            init {
                bg = f(R.id.v_color_cell_bg)
                check = f(R.id.iv_color_cell_check)

                // Phase 8 round 2: clip to an oval so background colours +
                // gradients are circular, and install a ripple foreground so
                // tap feedback draws on top of those children. circularRipple()
                // returns a RippleDrawable with an OVAL mask — the cell's
                // clipToOutline keeps everything bounded to the circle.
                cell.setClipToOutline(true)
                cell.outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, outline: android.graphics.Outline) {
                        outline.setOval(0, 0, v.width, v.height)
                    }
                }
                cell.setForeground(
                        com.ywwynm.everythingdone.utils.BackgroundUtil.circularRipple())

                cell.setOnClickListener(object : View.OnClickListener {
                    override fun onClick(v: View) {
                        val pos: Int = adapterPosition
                        // Phase 6 fix round 4: only re-roll when the user taps
                        // an already-picked random FAB. First tap commits the
                        // colour the user can SEE, so display == result. Tap
                        // again to shuffle.
                        if (mType == Def.PickerType.COLOR_EDIT && mPickedPosition == pos) {
                            if (pos == mColors.size + 1) {
                                mRandomPureBg = rollPureBackground()
                            } else if (pos == mColors.size + 2) {
                                mRandomGradientBg = rollGradientBackground()
                            }
                        }
                        mPopupWindow.dismiss()
                        pickForUI(pos)
                        mOnClickListener?.onClick(v)
                    }
                })
            }
        }

    }

    companion object {
        const val TAG: String = "ColorPicker"

        // Inner adapter view-type constants. Hoisted here because the
        // inner adapter class can't host a companion object in Kotlin.
        private const val ALL_COLOR: Int = 0
        private const val NORMAL: Int = 1
        private const val DIVIDER: Int = 2  // COLOR_EDIT only

        // Inlined copy of BackgroundUtil's enum mapping to avoid pulling
        // BackgroundUtil into this UI class. Identical mapping.
        private fun toGdOrientation(o: ThingBackground.Orientation):
                android.graphics.drawable.GradientDrawable.Orientation {
            return when (o) {
                ThingBackground.Orientation.L_R -> android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT
                ThingBackground.Orientation.T_B -> android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM
                ThingBackground.Orientation.LT_RB -> android.graphics.drawable.GradientDrawable.Orientation.TL_BR
                ThingBackground.Orientation.RT_LB -> android.graphics.drawable.GradientDrawable.Orientation.TR_BL
                ThingBackground.Orientation.LB_RT -> android.graphics.drawable.GradientDrawable.Orientation.BL_TR
                ThingBackground.Orientation.RB_LT -> android.graphics.drawable.GradientDrawable.Orientation.BR_TL
                ThingBackground.Orientation.R_L -> android.graphics.drawable.GradientDrawable.Orientation.RIGHT_LEFT
                ThingBackground.Orientation.B_T -> android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP
            }
        }

        /**
         * Representative colours for the 8 hue buckets used in
         * [Def.PickerType.HUE_BUCKET] mode. Index order matches
         * `BackgroundUtil.HUE_BUCKET_RED..GREY - 1` (i.e. RED=0, ORANGE=1, …
         * GREY=7).
         */
        private val HUE_BUCKET_COLORS: IntArray = intArrayOf(
                0xFFE53935.toInt(), // RED
                0xFFFB8C00.toInt(), // ORANGE
                0xFFFDD835.toInt(), // YELLOW
                0xFF43A047.toInt(), // GREEN
                0xFF00ACC1.toInt(), // CYAN
                0xFF1E88E5.toInt(), // BLUE
                0xFF8E24AA.toInt(), // PURPLE
                0xFF757575.toInt()  // GREY
        )
    }
}
