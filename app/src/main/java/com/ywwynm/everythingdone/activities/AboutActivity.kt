@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.widget.ImageView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.core.content.ContextCompat
import androidx.appcompat.app.ActionBar
import androidx.appcompat.widget.Toolbar
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.fragments.AlertDialogFragment
import com.ywwynm.everythingdone.fragments.LicenseDialogFragment
import com.ywwynm.everythingdone.fragments.ThreeActionsAlertDialogFragment
import com.ywwynm.everythingdone.helpers.DebugApkUpdateHelper
import com.ywwynm.everythingdone.helpers.SendInfoHelper
import com.ywwynm.everythingdone.utils.AppearanceUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.FontCache

open class AboutActivity : EverythingDoneBaseActivity() {

    private var mStatusBar: View? = null
    private var mActionbar: Toolbar? = null

    private var mFabHead: ImageView? = null
    private var mTvYwwynm: TextView? = null
    private var mTvEverythingDone: TextView? = null
    private var mTvVersion: TextView? = null

    private var mFab: FloatingActionButton? = null

    private var mFlBottom: FrameLayout? = null
    private var mTvCheckUpdate: TextView? = null

    private var mSupportDf: ThreeActionsAlertDialogFragment? = null
    private var mDonateDf: AlertDialogFragment? = null
    private var mDebugUpdateHelper: DebugApkUpdateHelper? = null

    override fun getLayoutResource(): Int = R.layout.activity_about

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_about, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val itemId = item.itemId
        if (itemId == R.id.act_share) {
            SendInfoHelper.shareApp(this@AboutActivity)
        } else if (itemId == R.id.act_feedback) {
            SendInfoHelper.sendFeedback(this, false)
        }
        return true
    }

    override fun initMembers() { }

    override fun findViews() {
        mStatusBar = f(R.id.view_status_bar)
        mActionbar = f(R.id.actionbar)

        mFabHead          = f(R.id.fab_about_head)
        mTvYwwynm         = f(R.id.tv_ywwynm)
        mTvEverythingDone = f(R.id.tv_everything_done)
        mTvVersion        = f(R.id.tv_version)

        mFab = f(R.id.fab_support)

        mFlBottom = f(R.id.fl_bottom_about)
        mTvCheckUpdate = f(R.id.tv_check_update_as_bt)
    }

    override fun initUI() {
        DisplayUtil.expandLayoutToStatusBarAboveLollipop(this)
        DisplayUtil.expandStatusBarViewAboveKitkat(mStatusBar)
        if (AppearanceUtil.isDarkMode(this)) {
            DisplayUtil.cancelDarkStatusBar(this)
        } else {
            DisplayUtil.darkStatusBar(this)
        }

        DisplayUtil.applyBottomInsetAsMargin(mFab)
        DisplayUtil.applyBottomInsetAsPadding(mFlBottom)

        val tf: Typeface? = FontCache.get(Def.Meta.ROBOTO_MONO, this)
        mTvYwwynm!!.setTypeface(tf)
        mTvEverythingDone!!.setTypeface(tf)

        mTvVersion!!.append(" " + BuildConfig.VERSION_NAME)

        val tvLicense: TextView = f(R.id.tv_license_as_bt)!!
        val paint: Paint = tvLicense.paint
        paint.flags = paint.flags or Paint.UNDERLINE_TEXT_FLAG
        val checkUpdatePaint: Paint = mTvCheckUpdate!!.paint
        checkUpdatePaint.flags = checkUpdatePaint.flags or Paint.UNDERLINE_TEXT_FLAG
        if (!BuildConfig.DEBUG) {
            mTvCheckUpdate!!.visibility = View.GONE
        }
    }

    override fun setActionbar() {
        setSupportActionBar(mActionbar)
        val actionBar: ActionBar? = supportActionBar
        actionBar?.setDisplayHomeAsUpEnabled(true)
        mActionbar!!.setNavigationOnClickListener { finish() }
    }

    override fun setEvents() {
        mFabHead!!.setOnClickListener(object : View.OnClickListener {

            val times: LongArray = LongArray(16)

            override fun onClick(v: View) {
                val context: Context = this@AboutActivity
                System.arraycopy(times, 1, times, 0, times.size - 1)
                times[times.size - 1] = System.currentTimeMillis()
                if (times[0] >= (System.currentTimeMillis() - 500000)) {
                    Toast.makeText(context, getString(R.string.fly_sing_qiong), Toast.LENGTH_LONG).show()
                    for (i in times.indices) {
                        times[i] = 0
                    }
                }
            }
        })

        mFab!!.setOnClickListener {
            if (mSupportDf == null) {
                initSupportDialog()
            }
            mSupportDf!!.show(fragmentManager, ThreeActionsAlertDialogFragment.TAG)
        }

        mTvCheckUpdate!!.setOnClickListener {
            getDebugUpdateHelper().checkForUpdate()
        }
    }

    override fun onResume() {
        super.onResume()
        mDebugUpdateHelper?.continuePendingInstallIfAllowed()
    }

    private fun initSupportDialog() {
        mSupportDf = ThreeActionsAlertDialogFragment()
        val color = ContextCompat.getColor(this, R.color.app_pink)
        mSupportDf!!.setTitleColor(color)
        mSupportDf!!.setContinueColor(color)
        mSupportDf!!.setTitle(getString(R.string.act_support))
        mSupportDf!!.setContent(getString(R.string.support_content))
        mSupportDf!!.setFirstAction(getString(R.string.support_star))
        mSupportDf!!.setSecondAction(getString(R.string.support_donate))
        mSupportDf!!.setOnClickListener(object : ThreeActionsAlertDialogFragment.OnClickListener {
            override fun onFirstClicked() {
                SendInfoHelper.rateApp(this@AboutActivity)
            }

            override fun onSecondClicked() {
                if (mDonateDf == null) {
                    initDonateDialog()
                }
                mDonateDf!!.show(fragmentManager, AlertDialogFragment.TAG)
            }

            override fun onThirdClicked() { }
        })
    }

    private fun initDonateDialog() {
        mDonateDf = AlertDialogFragment()
        val color = ContextCompat.getColor(this, R.color.app_pink)
        mDonateDf!!.setTitleColor(color)
        mDonateDf!!.setConfirmColor(color)
        mDonateDf!!.setTitle(getString(R.string.support_donate))
        mDonateDf!!.setContent(getString(R.string.support_donate_content))
        mDonateDf!!.setConfirmText(getString(R.string.support_donate_copy_name))
        mDonateDf!!.setConfirmListener(object : AlertDialogFragment.ConfirmListener {
            override fun onConfirm() {
                val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val clipData = ClipData.newPlainText(
                    getString(R.string.support_donate_clip_title),
                    getString(R.string.support_donate_clip_content)
                )
                clipboardManager.setPrimaryClip(clipData)
                Toast.makeText(
                    this@AboutActivity, R.string.success_clipboard,
                    Toast.LENGTH_SHORT
                ).show()
            }
        })
    }

    open fun showLicenseDialog(view: View?) {
        val ldf = LicenseDialogFragment()
        ldf.show(fragmentManager, LicenseDialogFragment.TAG)
    }

    private fun getDebugUpdateHelper(): DebugApkUpdateHelper {
        if (mDebugUpdateHelper == null) {
            mDebugUpdateHelper = DebugApkUpdateHelper(this)
        }
        return mDebugUpdateHelper!!
    }

    companion object {
        const val TAG: String = "AboutActivity"
    }
}
