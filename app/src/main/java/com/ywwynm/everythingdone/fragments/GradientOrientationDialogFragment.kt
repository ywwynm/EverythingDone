@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.fragments

import android.graphics.Color
import android.graphics.Outline
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView

import androidx.core.content.ContextCompat

import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil

/**
 * Phase 8: pick a new gradient direction for a GRADIENT thing.
 */
open class GradientOrientationDialogFragment : BaseDialogFragment() {

    interface OnPickListener {
        fun onPicked(orientation: ThingBackground.Orientation?)
    }

    private var mAccent: ThingBackground? = null
    private var mListener: OnPickListener? = null

    open fun setAccent(accent: ThingBackground?) {
        mAccent = accent
    }

    open fun setOnPickListener(listener: OnPickListener?) {
        mListener = listener
    }

    override fun getLayoutResource(): Int = R.layout.fragment_gradient_orientation

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)

        if (mAccent == null || mAccent!!.mode !== ThingBackground.Mode.GRADIENT) {
            dismiss()
            return mContentView
        }

        val tvTitle: TextView = f(R.id.tv_title_gradient_orientation)!!
        BackgroundUtil.applyTextBackground(tvTitle, mAccent)

        val grid: GridLayout = f(R.id.gl_gradient_orientations)!!
        val columns = 4
        val m: Int = (8 * resources.displayMetrics.density).toInt()
        for (i in ORDER.indices) {
            val orientation: ThingBackground.Orientation = ORDER[i]
            val cell: View = inflater.inflate(R.layout.color_picker_fab, grid, false)

            val glp = cell.layoutParams as GridLayout.LayoutParams
            glp.rowSpec    = GridLayout.spec(i / columns)
            glp.columnSpec = GridLayout.spec(i % columns)
            glp.setMargins(m, m, m, m)

            bind(cell, orientation)
            grid.addView(cell)
        }

        return mContentView
    }

    private fun bind(cell: View, orientation: ThingBackground.Orientation) {
        val root: FrameLayout = cell as FrameLayout  // cell is the FrameLayout itself
        val bg: View = cell.findViewById(R.id.v_color_cell_bg)
        val check: ImageView = cell.findViewById(R.id.iv_color_cell_check)

        root.clipToOutline = true
        root.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setOval(0, 0, v.width, v.height)
            }
        }
        root.foreground = BackgroundUtil.circularRipple()

        val gd = GradientDrawable(
            toGdOrientation(orientation),
            intArrayOf(mAccent!!.color, mAccent!!.endColor)
        )
        gd.shape = GradientDrawable.RECTANGLE
        bg.background = gd

        if (orientation === mAccent!!.orientation) {
            check.visibility = View.VISIBLE
            check.setImageDrawable(tintedCheckmark())
            root.contentDescription = getString(R.string.cd_picked) +
                orientationContentDescription(orientation)
        } else {
            check.visibility = View.GONE
            check.setImageDrawable(null)
            root.contentDescription = orientationContentDescription(orientation)
        }

        root.setOnClickListener {
            if (mListener != null) mListener!!.onPicked(orientation)
            dismiss()
        }
    }

    private fun tintedCheckmark(): Drawable? {
        var d: Drawable =
            ContextCompat.getDrawable(activity!!, R.drawable.ic_color_picked) ?: return null
        d = d.mutate()
        val tint: Int = if (BackgroundUtil.isLight(mAccent!!.representativeColor()))
            Color.BLACK else Color.WHITE
        d.setColorFilter(tint, PorterDuff.Mode.SRC_IN)
        return d
    }

    private fun orientationContentDescription(o: ThingBackground.Orientation): String {
        return when (o) {
            ThingBackground.Orientation.L_R   -> getString(R.string.cd_gradient_orientation_l_r)
            ThingBackground.Orientation.T_B   -> getString(R.string.cd_gradient_orientation_t_b)
            ThingBackground.Orientation.R_L   -> getString(R.string.cd_gradient_orientation_r_l)
            ThingBackground.Orientation.B_T   -> getString(R.string.cd_gradient_orientation_b_t)
            ThingBackground.Orientation.LT_RB -> getString(R.string.cd_gradient_orientation_lt_rb)
            ThingBackground.Orientation.RT_LB -> getString(R.string.cd_gradient_orientation_rt_lb)
            ThingBackground.Orientation.LB_RT -> getString(R.string.cd_gradient_orientation_lb_rt)
            ThingBackground.Orientation.RB_LT -> getString(R.string.cd_gradient_orientation_rb_lt)
        }
    }

    companion object {
        const val TAG: String = "GradientOrientationDialogFragment"

        /** Layout order: 4 cardinal directions first row, 4 diagonals second row. */
        private val ORDER: Array<ThingBackground.Orientation> = arrayOf(
            ThingBackground.Orientation.L_R,
            ThingBackground.Orientation.T_B,
            ThingBackground.Orientation.R_L,
            ThingBackground.Orientation.B_T,
            ThingBackground.Orientation.LT_RB,
            ThingBackground.Orientation.RT_LB,
            ThingBackground.Orientation.LB_RT,
            ThingBackground.Orientation.RB_LT
        )

        @JvmStatic
        private fun toGdOrientation(o: ThingBackground.Orientation): GradientDrawable.Orientation {
            return when (o) {
                ThingBackground.Orientation.L_R   -> GradientDrawable.Orientation.LEFT_RIGHT
                ThingBackground.Orientation.T_B   -> GradientDrawable.Orientation.TOP_BOTTOM
                ThingBackground.Orientation.LT_RB -> GradientDrawable.Orientation.TL_BR
                ThingBackground.Orientation.RT_LB -> GradientDrawable.Orientation.TR_BL
                ThingBackground.Orientation.LB_RT -> GradientDrawable.Orientation.BL_TR
                ThingBackground.Orientation.RB_LT -> GradientDrawable.Orientation.BR_TL
                ThingBackground.Orientation.R_L   -> GradientDrawable.Orientation.RIGHT_LEFT
                ThingBackground.Orientation.B_T   -> GradientDrawable.Orientation.BOTTOM_TOP
            }
        }
    }
}
