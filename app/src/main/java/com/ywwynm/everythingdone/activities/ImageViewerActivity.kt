@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import androidx.activity.OnBackPressedCallback
import android.graphics.Point
import android.graphics.PorterDuff
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.viewpager.widget.ViewPager
import androidx.appcompat.app.ActionBar
import androidx.appcompat.widget.Toolbar
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar

import com.bumptech.glide.Glide
import android.graphics.drawable.Drawable
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.github.chrisbanes.photoview.OnPhotoTapListener
import com.github.chrisbanes.photoview.PhotoView
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.adapters.ImageViewerPagerAdapter
import com.ywwynm.everythingdone.fragments.AlertDialogFragment
import com.ywwynm.everythingdone.fragments.AttachmentInfoDialogFragment
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.EdgeEffectUtil
import com.ywwynm.everythingdone.utils.FileUtil

import java.io.File
import java.util.ArrayList

open class ImageViewerActivity : EverythingDoneBaseActivity() {

    private var mSystemUiVisible: Boolean = true

    private var mAccentColor: Int = 0
    /** Phase 8: full ThingBackground for gradient text on title / confirm. */
    private var mAccentBackground: ThingBackground? = null
    private var mNightModeMask: Int = 0
    private var mEditable: Boolean = false
    private var mTypePathNames: ArrayList<String>? = null
    private var mPosition: Int = 0

    private var mUpdated: Boolean = false

    private var mActionbar: Toolbar? = null

    private var mVpImage: ViewPager? = null
    private var mAdapter: ImageViewerPagerAdapter? = null
    private var mTabs: MutableList<View?>? = null

    override fun getLayoutResource(): Int = R.layout.activity_image_viewer

    override fun initMembers() {
        mNightModeMask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

        val intent: Intent = getIntent()
        mAccentColor = intent.getIntExtra(Def.Communication.KEY_COLOR, 0)
        val bgJson = intent.getStringExtra(Def.Communication.KEY_BACKGROUND)
        mAccentBackground = ThingBackground.fromJson(bgJson)
        if (mAccentBackground == null) {
            mAccentBackground = ThingBackground.pure(mAccentColor)
        }
        mEditable = intent.getBooleanExtra(Def.Communication.KEY_EDITABLE, true)
        @Suppress("UNCHECKED_CAST")
        mTypePathNames = intent.getStringArrayListExtra(
            Def.Communication.KEY_TYPE_PATH_NAME
        )
        mPosition = intent.getIntExtra(Def.Communication.KEY_POSITION, 0)

        val size = mTypePathNames!!.size
        mTabs = ArrayList(size)
    }

    override fun findViews() {
        mActionbar = f(R.id.actionbar)
        mVpImage   = f(R.id.vp_image_viewer)
    }

    override fun initUI() {
        val decorView: View = window.decorView
        val flags = (View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        decorView.systemUiVisibility = flags

        val appAccent = App.defaultAccentBackground.representativeColor()
        EdgeEffectUtil.forViewPager(mVpImage, appAccent)

        val size: IntArray = getImageSize()
        val imageListener: OnPhotoTapListener = getImageListener()
        val videoListener: View.OnClickListener = getVideoListener()

        val inflater = LayoutInflater.from(this)
        for (typePathName in mTypePathNames!!) {
            @SuppressLint("InflateParams")
            val tab: View = inflater.inflate(R.layout.tab_image_attachment, null)

            val type = if (typePathName[0] == '0') AttachmentHelper.IMAGE else AttachmentHelper.VIDEO
            val pathName = typePathName.substring(1, typePathName.length)

            val pb: ProgressBar       = f(tab, R.id.pb_image_attachment)!!
            val iv: PhotoView         = f(tab, R.id.iv_image_attachment)!!
            val videoSignal: ImageView = f(tab, R.id.iv_video_signal)!!

            BackgroundUtil.applyProgressBarGradient(pb, App.defaultAccentBackground)

            iv.setScaleLevels(1.0f, 3.0f, 6.0f)

            if (type == 0) {
                iv.contentDescription = getString(R.string.cd_image_attachment)
                videoSignal.visibility = View.GONE
                iv.setOnPhotoTapListener(imageListener)
            } else {
                iv.contentDescription = getString(R.string.cd_video_attachment)
                videoSignal.visibility = View.VISIBLE
                videoSignal.setOnClickListener(videoListener)
                iv.isZoomable = false
            }

            loadImage(pathName, iv, pb, size)

            mTabs!!.add(tab)
        }

        mAdapter = ImageViewerPagerAdapter(mTabs)
        mVpImage!!.adapter = mAdapter

        mVpImage!!.currentItem = mPosition
    }

    private fun getImageSize(): IntArray {
        val screen: Point = DisplayUtil.getScreenSize(this)
        val width  = screen.x
        val height = screen.y
        return intArrayOf(width, height)
    }

    private fun getImageListener(): OnPhotoTapListener {
        return OnPhotoTapListener { _, _, _ ->
            toggleSystemUI()
        }
    }

    private fun getVideoListener(): View.OnClickListener {
        return View.OnClickListener {
            val pos = mVpImage!!.currentItem
            val typePathName: String = mTypePathNames!![pos]
            val pathName = typePathName.substring(1, typePathName.length)
            val file = File(pathName)

            val intent = Intent(Intent.ACTION_VIEW)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val uri: Uri = FileProvider.getUriForFile(
                this@ImageViewerActivity,
                "com.ywwynm.everythingdone", file
            )
            intent.setDataAndType(uri, "video/" + FileUtil.getPostfix(pathName))
            startActivity(intent)
        }
    }

    private fun loadImage(
        pathName: String, iv: PhotoView,
        pb: ProgressBar, size: IntArray
    ) {
        Glide.with(this)
            .load(pathName)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?, model: Any?, target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean = false

                override fun onResourceReady(
                    resource: Drawable, model: Any, target: Target<Drawable>?,
                    dataSource: DataSource, isFirstResource: Boolean
                ): Boolean {
                    iv.setImageDrawable(resource)
                    pb.visibility = View.GONE
                    return true
                }
            })
            .override(size[0], size[1])
            .into(iv)
    }

    override fun setActionbar() {
        DisplayUtil.applyTopInsetAsMargin(mActionbar)

        setSupportActionBar(mActionbar)
        val actionBar: ActionBar? = supportActionBar
        actionBar?.setDisplayHomeAsUpEnabled(true)
        updateAttachmentNumber()
        mActionbar!!.setNavigationOnClickListener { returnToDetailActivity() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_image_viewer, menu)
        if (!mEditable) {
            val item: MenuItem = menu.findItem(R.id.act_delete_attachment)
            item.isVisible = false
            item.isEnabled = false
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        if (id == R.id.act_show_attachment_info) {
            showAttachmentInfoDialogForCurrentImage()
        } else if (id == R.id.act_delete_attachment) {
            val adf = AlertDialogFragment()
            adf.setContentColor(ContextCompat.getColor(this, R.color.app_chrome_on_surface_medium))
            adf.setConfirmBackground(mAccentBackground)
            adf.setContent(getString(R.string.alert_delete_attachment))
            adf.setConfirmListener(object : AlertDialogFragment.ConfirmListener {
                override fun onConfirm() {
                    val currentIndex = mVpImage!!.currentItem
                    mTypePathNames!!.removeAt(currentIndex)
                    mAdapter!!.removeTab(mVpImage, currentIndex)
                    updateAttachmentNumber()
                    mUpdated = true
                    if (mAdapter!!.count == 0) {
                        returnToDetailActivity()
                    }
                }
            })
            adf.show(fragmentManager, AlertDialogFragment.TAG)
            return true
        }
        return false
    }

    private fun showAttachmentInfoDialogForCurrentImage() {
        val typePathNames = mTypePathNames ?: return
        if (typePathNames.isEmpty()) return
        val currentItem = mVpImage?.currentItem ?: return
        val index = currentItem.coerceIn(0, typePathNames.size - 1)
        AttachmentHelper.showAttachmentInfoDialog(this, mAccentBackground, typePathNames[index])
    }

    override fun setEvents() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                returnToDetailActivity()
            }
        })

        mVpImage!!.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                updateAttachmentNumber()
            }
        })
    }

    private fun updateAttachmentNumber() {
        val current = mVpImage!!.currentItem + 1
        val total   = mTypePathNames!!.size
        val actionBar: ActionBar? = supportActionBar
        if (actionBar != null) {
            actionBar.title = "$current / $total"
        }
    }

    private fun toggleSystemUI() {
        val decorView: View = window.decorView
        val visibility = decorView.systemUiVisibility
        if (mSystemUiVisible) {
            decorView.systemUiVisibility = (visibility
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE)
            mActionbar!!.visibility = View.GONE
        } else {
            decorView.systemUiVisibility = (visibility
                    and View.SYSTEM_UI_FLAG_FULLSCREEN.inv()
                    and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION.inv()
                    and View.SYSTEM_UI_FLAG_IMMERSIVE.inv())
            mActionbar!!.visibility = View.VISIBLE
        }
        mSystemUiVisible = !mSystemUiVisible
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        val newNightModeMask = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (newNightModeMask == mNightModeMask) return

        mNightModeMask = newNightModeMask
        delegate.applyDayNight()

        val attachmentInfoWasShowing =
            fragmentManager.findFragmentByTag(AttachmentInfoDialogFragment.TAG) is AttachmentInfoDialogFragment
        dismissDialogFragment(AttachmentInfoDialogFragment.TAG)
        dismissDialogFragment(AlertDialogFragment.TAG)

        if (attachmentInfoWasShowing) {
            mVpImage?.post {
                if (!isFinishing && !isDestroyed) {
                    showAttachmentInfoDialogForCurrentImage()
                }
            }
        }
    }

    private fun dismissDialogFragment(tag: String) {
        val fragment = fragmentManager.findFragmentByTag(tag)
        if (fragment is android.app.DialogFragment) {
            fragment.dismissAllowingStateLoss()
        }
    }

    private fun returnToDetailActivity() {
        if (mUpdated) {
            val intent = Intent()
            intent.putExtra(Def.Communication.KEY_TYPE_PATH_NAME, mTypePathNames)
            setResult(Def.Communication.RESULT_UPDATE_IMAGE_DONE, intent)
        } else {
            setResult(Def.Communication.RESULT_NO_UPDATE)
        }
        finish()
    }

    companion object {
        const val TAG: String = "ImageViewerActivity"
    }
}
