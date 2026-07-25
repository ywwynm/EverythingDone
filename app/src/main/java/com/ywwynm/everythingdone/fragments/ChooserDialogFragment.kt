@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.fragments

import android.content.DialogInterface
import android.os.Bundle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.adapters.RadioChooserAdapter
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.EdgeEffectUtil
import com.ywwynm.everythingdone.views.GradientRippleDrawable

import java.util.ArrayList

/**
 * Created by ywwynm on 2016/3/11.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * chooser dialog fragment
 */
open class ChooserDialogFragment : BaseDialogFragment() {

    private var mAccentColor: Int = 0
    /** Phase 8: ThingBackground-typed accent so dialog title / buttons can render gradient text. */
    private var mAccentBackground: ThingBackground? = null

    private var mTitle: String? = null
    private var mConfirmText: String? = null
    private var mItems: MutableList<String?>? = null
    private var mInitialIndex: Int = 0

    private var mConfirmListener: View.OnClickListener? = null
    private var mMoreListener: View.OnClickListener? = null

    private var mTvTitle: TextView? = null
    private var mRecyclerView: RecyclerView? = null
    private var mAdapter: RadioChooserAdapter? = null
    private var mTvConfirmAsBt: TextView? = null
    private var mTvCancelAsBt: TextView? = null
    private var mTvMoreAsBt: TextView? = null

    private var mShouldOverScroll: Boolean          = false
    private var mShouldShowMore: Boolean            = true
    private var mShouldShowActions: Boolean         = true
    private var mShouldDismissAfterConfirm: Boolean = true

    private var mSeparator1: View? = null
    private var mSeparator2: View? = null

    private var mOnItemClickListener: View.OnClickListener? = null

    interface OnDismissListener {
        fun onDismiss()
    }
    private var mOnDismissListener: OnDismissListener? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)

        mTvTitle       = f(R.id.tv_title_fragment_chooser)
        mRecyclerView  = f(R.id.rv_fragment_chooser)
        mTvConfirmAsBt = f(R.id.tv_confirm_as_bt_fragment_chooser)
        mTvCancelAsBt  = f(R.id.tv_cancel_as_bt_fragment_chooser)
        mTvMoreAsBt    = f(R.id.tv_more_as_bt_fragment_chooser)

        mSeparator1 = f(R.id.view_separator_1)
        mSeparator2 = f(R.id.view_separator_2)

        if (mItems == null) {
            mItems = ArrayList(1)
        }

        initUI()
        setEvents()

        return mContentView
    }

    override fun getLayoutResource(): Int = R.layout.fragment_chooser

    override fun onDismiss(dialog: DialogInterface) {
        if (mOnDismissListener != null) {
            mOnDismissListener!!.onDismiss()
        }

        mConfirmListener     = null
        mOnDismissListener   = null
        mMoreListener        = null
        mOnItemClickListener = null

        super.onDismiss(dialog)
    }

    private fun initUI() {
        mTvTitle!!.text = mTitle
        if (mConfirmText != null) {
            mTvConfirmAsBt!!.text = mConfirmText
        }
        applyAccent(mTvTitle!!)
        applyAccent(mTvConfirmAsBt!!)
        GradientRippleDrawable.applyAccentRipple(mTvConfirmAsBt!!, mAccentBackground, mAccentColor)

        if (!mShouldShowMore) {
            mTvMoreAsBt!!.visibility = View.GONE
        } else {
            applyAccent(mTvMoreAsBt!!)
            GradientRippleDrawable.applyAccentRipple(mTvMoreAsBt!!, mAccentBackground, mAccentColor)
        }
        if (!mShouldShowActions) {
            (mTvConfirmAsBt!!.parent as View).visibility = View.GONE
            val params = mRecyclerView!!.layoutParams as LinearLayout.LayoutParams
            params.bottomMargin = (12 * DisplayUtil.getScreenDensity(App.getApp())).toInt()
            mRecyclerView!!.layoutParams = params
        }

        if (mItems!!.size > 9) {
            val params = mRecyclerView!!.layoutParams as LinearLayout.LayoutParams
            params.height = (40 * 8.5 * DisplayUtil.getScreenDensity(App.getApp())).toInt()
            mRecyclerView!!.requestLayout()
        } else {
            mSeparator1!!.visibility = View.INVISIBLE
            mSeparator2!!.visibility = View.INVISIBLE
        }

        if (mShouldOverScroll) {
            mRecyclerView!!.overScrollMode = View.OVER_SCROLL_ALWAYS
        } else {
            mRecyclerView!!.overScrollMode = View.OVER_SCROLL_NEVER
        }

        mAdapter = RadioChooserAdapter(activity, mItems, mAccentColor)
        if (mAccentBackground != null) {
            mAdapter!!.setAccentBackground(mAccentBackground)
        }
        mAdapter!!.setOnItemClickListener(mOnItemClickListener)
        mRecyclerView!!.adapter = mAdapter
        mRecyclerView!!.layoutManager = LinearLayoutManager(activity)
        mAdapter!!.pick(mInitialIndex)

        if (mItems!!.size > 9) {
            mRecyclerView!!.post {
                mRecyclerView!!.scrollToPosition(mInitialIndex)
                updateSeparators()
            }
        }
    }

    private fun setEvents() {
        if (mItems!!.size > 9) {
            mRecyclerView!!.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    updateSeparators()
                }
            })
        }
        mTvCancelAsBt!!.setOnClickListener { dismiss() }
        mTvConfirmAsBt!!.setOnClickListener { v ->
            if (mConfirmListener != null) {
                mConfirmListener!!.onClick(v)
            }
            if (mShouldDismissAfterConfirm) {
                dismiss()
            }
        }
        if (mShouldShowMore) {
            mTvMoreAsBt!!.setOnClickListener { v ->
                if (mMoreListener != null) {
                    mMoreListener!!.onClick(v)
                }
            }
        }

        mRecyclerView!!.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                EdgeEffectUtil.forRecyclerView(mRecyclerView, mAccentColor)
            }
        })
    }

    open fun notifyDataSetChanged() {
        mAdapter!!.notifyDataSetChanged()
    }

    open fun pick(position: Int) {
        mAdapter!!.pick(position)
    }

    open fun getPickedIndex(): Int = mAdapter!!.getPickedPosition()

    open fun setAccentBackground(bg: ThingBackground?) {
        mAccentBackground = bg
        if (bg != null) mAccentColor = bg.color
    }

    private fun applyAccent(tv: TextView) {
        if (mAccentBackground != null) {
            BackgroundUtil.applyTextBackground(tv, mAccentBackground)
        } else {
            tv.setTextColor(mAccentColor)
        }
    }

    open fun setAccentColor(accentColor: Int) {
        mAccentBackground = null
        mAccentColor = accentColor
    }

    open fun setTitle(title: String?) {
        mTitle = title
    }

    open fun setConfirmText(confirmText: String?) {
        mConfirmText = confirmText
    }

    open fun setItems(items: MutableList<String?>?) {
        mItems = items
    }

    open fun setShouldOverScroll(shouldOverScroll: Boolean) {
        mShouldOverScroll = shouldOverScroll
    }

    open fun setShouldShowMore(shouldShowMore: Boolean) {
        mShouldShowMore = shouldShowMore
    }

    open fun setShouldShowActions(shouldShowActions: Boolean) {
        mShouldShowActions = shouldShowActions
    }

    open fun setShouldDismissAfterConfirm(shouldDismissAfterConfirm: Boolean) {
        mShouldDismissAfterConfirm = shouldDismissAfterConfirm
    }

    open fun setInitialIndex(initialIndex: Int) {
        mInitialIndex = initialIndex
    }

    open fun setConfirmListener(confirmListener: View.OnClickListener?) {
        mConfirmListener = confirmListener
    }

    open fun setMoreListener(moreListener: View.OnClickListener?) {
        mMoreListener = moreListener
    }

    open fun setOnItemClickListener(onItemClickListener: View.OnClickListener?) {
        mOnItemClickListener = onItemClickListener
    }

    open fun setOnDismissListener(onDismissListener: OnDismissListener?) {
        mOnDismissListener = onDismissListener
    }

    private fun updateSeparators() {
        if (!mRecyclerView!!.canScrollVertically(-1)) {
            mSeparator1!!.visibility = View.INVISIBLE
            mSeparator2!!.visibility = View.VISIBLE
        } else if (!mRecyclerView!!.canScrollVertically(1)) {
            mSeparator1!!.visibility = View.VISIBLE
            mSeparator2!!.visibility = View.INVISIBLE
        } else {
            mSeparator1!!.visibility = View.VISIBLE
            mSeparator2!!.visibility = View.VISIBLE
        }
    }

    companion object {
        const val TAG: String = "ChooserDialogFragment"
    }
}
