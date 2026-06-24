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
import android.widget.TextView
import android.widget.Toast
import android.content.ActivityNotFoundException
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build

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

    /** HDR badge shown for gain-map images on API 34+. */
    private var mTvHdrBadge: TextView? = null
    /** Per-page: the decoded image carries a gain map (content is HDR). */
    private var mHasGainmap: BooleanArray = BooleanArray(0)
    /** Per-page, ephemeral: user tapped the badge to force SDR on this page. */
    private var mForcedSdr: BooleanArray = BooleanArray(0)

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
        mHasGainmap = BooleanArray(size)
        mForcedSdr = BooleanArray(size)
    }

    override fun findViews() {
        mActionbar = f(R.id.actionbar)
        mVpImage   = f(R.id.vp_image_viewer)
        mTvHdrBadge = f(R.id.tv_hdr_badge)
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
        for ((index, typePathName) in mTypePathNames!!.withIndex()) {
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

            loadImage(index, pathName, iv, pb, size)

            mTabs!!.add(tab)
        }

        mAdapter = ImageViewerPagerAdapter(mTabs)
        mVpImage!!.adapter = mAdapter

        mVpImage!!.currentItem = mPosition

        DisplayUtil.applyTopInsetAsMargin(mTvHdrBadge)
        mTvHdrBadge!!.setOnClickListener {
            val pos = mVpImage?.currentItem ?: return@setOnClickListener
            if (pos in mForcedSdr.indices) {
                mForcedSdr[pos] = !mForcedSdr[pos]
                applyHdrStateForCurrentPage()
            }
        }
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
            // getUriForFile throws IllegalArgumentException for paths outside the
            // FileProvider roots (e.g. a removable volume); startActivity throws
            // ActivityNotFoundException when no player is installed. Guard both so
            // a tap on a video can never crash the viewer.
            try {
                val uri: Uri = FileProvider.getUriForFile(
                    this@ImageViewerActivity,
                    "com.ywwynm.everythingdone", file
                )
                intent.setDataAndType(uri, "video/*")
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(
                    this@ImageViewerActivity,
                    R.string.image_viewer_no_video_player,
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: IllegalArgumentException) {
                Toast.makeText(
                    this@ImageViewerActivity,
                    R.string.image_viewer_no_video_player,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun loadImage(
        position: Int, pathName: String, iv: PhotoView,
        pb: ProgressBar, size: IntArray
    ) {
        // asBitmap + dontTransform + disallowHardwareConfig: decode straight to
        // an ARGB_8888 bitmap that still carries the UltraHDR gain map, with no
        // software-Canvas transform step that would flatten it to SDR. PhotoView
        // does its own matrix fit/zoom, so no Glide fitting transform is needed.
        Glide.with(this)
            .asBitmap()
            .load(pathName)
            .dontTransform()
            .disallowHardwareConfig()
            .listener(object : RequestListener<Bitmap> {
                override fun onLoadFailed(
                    e: GlideException?, model: Any?, target: Target<Bitmap>,
                    isFirstResource: Boolean
                ): Boolean {
                    pb.visibility = View.GONE
                    return false
                }

                override fun onResourceReady(
                    resource: Bitmap, model: Any, target: Target<Bitmap>?,
                    dataSource: DataSource, isFirstResource: Boolean
                ): Boolean {
                    iv.setImageBitmap(resource)
                    pb.visibility = View.GONE
                    val hdr = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                            resource.hasGainmap()
                    if (position in mHasGainmap.indices) {
                        mHasGainmap[position] = hdr
                    }
                    if (position == mVpImage?.currentItem) {
                        applyHdrStateForCurrentPage()
                    }
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
                applyHdrStateForCurrentPage()
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

    /**
     * Apply HDR for the currently visible page: switch the window to
     * [ActivityInfo.COLOR_MODE_HDR] (API 34+) when the page's image carries a
     * gain map and the user has not forced SDR on it, and refresh the badge.
     * On a non-HDR display the window mode is harmless; the gain map simply
     * isn't boosted.
     */
    private fun applyHdrStateForCurrentPage() {
        val pos = mVpImage?.currentItem ?: return
        val isHdr = pos in mHasGainmap.indices && mHasGainmap[pos]
        val boostOn = isHdr && !(pos in mForcedSdr.indices && mForcedSdr[pos])
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val mode = if (boostOn) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
            if (window.colorMode != mode) {
                window.colorMode = mode
            }
        }
        updateHdrBadge(isHdr, boostOn)
    }

    private fun updateHdrBadge(isHdr: Boolean, boostOn: Boolean) {
        val badge = mTvHdrBadge ?: return
        if (!isHdr || !mSystemUiVisible) {
            badge.visibility = View.GONE
            return
        }
        badge.visibility = View.VISIBLE
        if (boostOn) {
            badge.setBackgroundResource(R.drawable.bg_hdr_badge_on)
            badge.setTextColor(Color.BLACK)
            badge.alpha = 1f
            badge.contentDescription = getString(R.string.cd_hdr_badge_on)
        } else {
            badge.setBackgroundResource(R.drawable.bg_hdr_badge_off)
            badge.setTextColor(Color.WHITE)
            badge.alpha = 0.9f
            badge.contentDescription = getString(R.string.cd_hdr_badge_off)
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
        applyHdrStateForCurrentPage()
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
