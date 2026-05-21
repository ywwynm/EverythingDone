@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.activities

import androidx.fragment.app.FragmentTransaction
import androidx.core.content.ContextCompat
import androidx.appcompat.app.ActionBar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.widget.Toolbar
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.adapters.BaseViewHolder
import com.ywwynm.everythingdone.fragments.HelpDetailFragment
import com.ywwynm.everythingdone.helpers.SendInfoHelper
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.EdgeEffectUtil

open class HelpActivity : EverythingDoneBaseActivity() {

    private var mTitles: Array<String?>? = null
    private var mContents: Array<String?>? = null

    private var mHelpDetailFragment: HelpDetailFragment? = null

    private var mRecyclerView: RecyclerView? = null

    override fun getLayoutResource(): Int = R.layout.activity_help

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_help, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        if (id == R.id.act_feedback) {
            SendInfoHelper.sendFeedback(this, false)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun initMembers() {
        mTitles   = resources.getStringArray(R.array.help_titles) as Array<String?>
        mContents = resources.getStringArray(R.array.help_contents) as Array<String?>
    }

    override fun findViews() {
    }

    override fun initUI() {
        DisplayUtil.expandLayoutToStatusBarAboveLollipop(this)
        DisplayUtil.expandStatusBarViewAboveKitkat(f(R.id.view_status_bar))
        DisplayUtil.darkStatusBar(this)

        mRecyclerView = f(R.id.rv_help)
        mRecyclerView!!.adapter = HelpAdapter()
        mRecyclerView!!.layoutManager = LinearLayoutManager(this)
        DisplayUtil.applyBottomInsetAsScrollPadding(mRecyclerView)

        mRecyclerView!!.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            val color: Int = ContextCompat.getColor(this@HelpActivity, R.color.blue_deep)
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                EdgeEffectUtil.forRecyclerView(mRecyclerView, color)
            }
        })
    }

    override fun setActionbar() {
        val toolbar: Toolbar = f(R.id.actionbar)!!
        setSupportActionBar(toolbar)
        val actionBar: ActionBar? = supportActionBar
        actionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener {
            if (mHelpDetailFragment != null && mHelpDetailFragment!!.isVisible) {
                supportFragmentManager.popBackStack()
            } else {
                finish()
            }
        }
    }

    override fun setEvents() {
    }

    open fun updateActionBarTitle(toDetail: Boolean) {
        val actionBar: ActionBar? = supportActionBar
        actionBar?.setTitle(if (toDetail) R.string.help_detail else R.string.help)
    }

    // Used to make the RecyclerView not focusable in talkback mode
    open fun setRecyclerViewFocusable(focusable: Boolean) {
        if (focusable) {
            mRecyclerView!!.visibility = View.VISIBLE
        } else {
            mRecyclerView!!.visibility = View.GONE
        }
    }

    internal inner class HelpAdapter : RecyclerView.Adapter<HelpAdapter.HelperHolder>() {

        private val mInflater: LayoutInflater = LayoutInflater.from(this@HelpActivity)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HelperHolder {
            return HelperHolder(mInflater.inflate(R.layout.rv_help, parent, false))
        }

        override fun onBindViewHolder(holder: HelperHolder, position: Int) {
            holder.tv!!.text = mTitles!![position]
        }

        override fun getItemCount(): Int = mTitles!!.size

        internal inner class HelperHolder(itemView: View?) : BaseViewHolder(itemView) {

            var tv: TextView? = f(R.id.tv_help_rv)

            init {
                f<View>(R.id.ll_help_rv)!!.setOnClickListener {
                    val pos = adapterPosition
                    mHelpDetailFragment = HelpDetailFragment.newInstance(
                        mTitles, mContents, pos
                    )
                    val tag = HelpDetailFragment.TAG
                    supportFragmentManager
                        .beginTransaction()
                        .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                        .replace(R.id.fl_fragment_container_help,
                            mHelpDetailFragment!!, tag)
                        .addToBackStack(tag)
                        .commit()
                }
            }
        }
    }
}
