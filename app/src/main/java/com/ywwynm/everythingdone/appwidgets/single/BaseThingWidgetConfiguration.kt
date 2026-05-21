@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.appwidgets.single

import android.app.WallpaperManager
import androidx.activity.OnBackPressedCallback
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.appcompat.app.ActionBar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.appcompat.widget.Toolbar
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.SeekBar

import com.bumptech.glide.Glide
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.EverythingDoneBaseActivity
import com.ywwynm.everythingdone.adapters.BaseThingsAdapter
import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper
import com.ywwynm.everythingdone.database.AppWidgetDAO
import com.ywwynm.everythingdone.database.ThingDAO
import com.ywwynm.everythingdone.managers.ModeManager
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingWidgetInfo
import com.ywwynm.everythingdone.permission.PermissionUtil
import com.ywwynm.everythingdone.permission.SimplePermissionCallback
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.EdgeEffectUtil

import java.util.Collections

/**
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 */
open class BaseThingWidgetConfiguration : EverythingDoneBaseActivity() {

    protected open fun getSenderClass(): Class<*>? {
        return BaseThingWidget::class.java
    }

    private var mActionBar: Toolbar? = null
    private var mRecyclerView: RecyclerView? = null

    private var mAdapter: ThingsAdapter? = null
    private var mThings: MutableList<Thing?>? = null
    private var mStaggeredGridLayoutManager: StaggeredGridLayoutManager? = null

    private var mSpanCount: Int = 0

    private var mAppWidgetId: Int = 0

    private var mFlPreviewAndConfig: FrameLayout? = null
    private var mLlConfig: LinearLayout? = null
    private var mWidgetAlpha: Int = 100

    override fun getLayoutResource(): Int {
        return R.layout.activity_thing_widget_configuration
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        mSpanCount = if (DisplayUtil.isTablet(this)) 3 else 2
        if (getResources().configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            mSpanCount++
        }
        mStaggeredGridLayoutManager!!.setSpanCount(mSpanCount)

        if (mThings!!.size > 1) {
            mRecyclerView!!.scrollToPosition(0)
        }
        mAdapter!!.notifyDataSetChanged()

        DisplayUtil.applyBottomInsetAsMargin(mLlConfig)
    }

    override fun initMembers() {
        val intent: Intent = getIntent()
        mAppWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID)
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mAppWidgetId)
        setResult(RESULT_CANCELED, intent)

        if (mAppWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        mSpanCount = if (DisplayUtil.isTablet(this)) 3 else 2
        if (getResources().configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            mSpanCount++
        }

        mThings = ThingDAO.getInstance(this)!!
                .getThingsForDisplay(Def.LimitForGettingThings.ALL_UNDERWAY)!!.toMutableList()
        mThings!!.removeAt(0) // no header
        mAdapter = ThingsAdapter()
    }

    override fun findViews() {
        mActionBar    = f(R.id.actionbar)
        mRecyclerView = f(R.id.rv_things)

        mFlPreviewAndConfig = f(R.id.fl_app_widget_preview_and_ui_config)
        mLlConfig = f(R.id.ll_widget_ui_config)
    }

    override fun initUI() {
        updateStatusBarAndBottomUi(true)
        DisplayUtil.expandLayoutToStatusBarAboveLollipop(this)
        DisplayUtil.expandStatusBarViewAboveKitkat(findViewById(R.id.view_status_bar))
        DisplayUtil.darkStatusBar(this)

        mLlConfig!!.setBackgroundColor(Color.parseColor("#66000000"))
        DisplayUtil.applyBottomInsetAsScrollPadding(mRecyclerView)

        if (!PermissionUtil.hasStoragePermission(this)
                && PermissionUtil.shouldRequestPermissionWhenLoadingThings(mThings)) {
            doWithPermissionChecked(object : SimplePermissionCallback(this) {
                override fun onGranted() {
                    initRecyclerView()
                }

                override fun onDenied() {
                    super.onDenied()
                    finish()
                }
            }, Def.Communication.REQUEST_PERMISSION_LOAD_THINGS_2,
                    *PermissionUtil.getRequiredPermissionsForThings(mThings)!!)
        } else {
            initRecyclerView()
        }
    }

    private fun updateStatusBarAndBottomUi(selecting: Boolean) {
        val window: Window = getWindow()
        val flp: FrameLayout.LayoutParams = mLlConfig!!.layoutParams as FrameLayout.LayoutParams

        if (selecting) {
            window.statusBarColor = ContextCompat.getColor(this, R.color.bg_statusbar_lollipop)
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            flp.bottomMargin = 0
            flp.rightMargin = 0
            mLlConfig!!.requestLayout()
        } else {
            window.statusBarColor = Color.TRANSPARENT
            window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            DisplayUtil.applyBottomInsetAsMargin(mLlConfig)
        }
    }

    private fun initRecyclerView() {
        mStaggeredGridLayoutManager = StaggeredGridLayoutManager(
                mSpanCount, StaggeredGridLayoutManager.VERTICAL)
        mRecyclerView!!.setLayoutManager(mStaggeredGridLayoutManager)
        mRecyclerView!!.setAdapter(mAdapter)
    }

    override fun setActionbar() {
        setSupportActionBar(mActionBar)
        val actionBar: ActionBar? = supportActionBar
        actionBar?.setDisplayHomeAsUpEnabled(true)

        mActionBar!!.setNavigationOnClickListener { v -> finish() }
    }

    override fun setEvents() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (mFlPreviewAndConfig!!.visibility == View.VISIBLE) {
                    endPreviewAppWidget()
                } else {
                    isEnabled = false
                    this@BaseThingWidgetConfiguration.onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        mRecyclerView!!.addOnScrollListener(object : RecyclerView.OnScrollListener() {

            val edgeColor: Int = EdgeEffectUtil.getEdgeColorDark()
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                EdgeEffectUtil.forRecyclerView(recyclerView, edgeColor)
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    Glide.with(this@BaseThingWidgetConfiguration).resumeRequests()
                } else { // dragging or settling
                    Glide.with(this@BaseThingWidgetConfiguration).pauseRequests()
                }
            }
        })
    }

    private fun previewAppWidget(thing: Thing) {
        mFlPreviewAndConfig!!.visibility = View.VISIBLE
        mActionBar!!.visibility = View.GONE
        mRecyclerView!!.visibility = View.GONE
        DisplayUtil.cancelDarkStatusBar(this)

        val ivBackground: ImageView = f(R.id.iv_app_widget_preview_background)
        try {
            val wm: WallpaperManager = WallpaperManager.getInstance(applicationContext)
            val wallpaper: Drawable? = wm.drawable
            if (wallpaper != null) {
                ivBackground.setImageDrawable(wallpaper)
            }
        } catch (e: SecurityException) {
            ivBackground.setBackgroundColor(0xCC000000.toInt())
        }

        val singleThing: List<Thing?> = Collections.singletonList(Thing(thing))
        val adapter: BaseThingsAdapter = object : BaseThingsAdapter(this@BaseThingWidgetConfiguration) {

            override fun getCurrentMode(): Int {
                return ModeManager.NORMAL
            }

            override fun getThings(): MutableList<Thing?>? {
                return singleThing.toMutableList()
            }

            override fun onBindViewHolder(holder: BaseThingViewHolder, position: Int) {
                super.onBindViewHolder(holder, position)
                holder.cv!!.radius = 0f
                holder.cv!!.cardElevation = 0f
                val alpha: Int = (mWidgetAlpha / 100f * 255).toInt()
                // Phase 4.d: preview supports gradient backgrounds.
                val bg: com.ywwynm.everythingdone.model.ThingBackground = thing.getBackground()!!
                val s: Int = DisplayUtil.getTransparentColor(bg.color,    alpha)
                val e: Int = DisplayUtil.getTransparentColor(bg.endColor, alpha)
                val tinted: com.ywwynm.everythingdone.model.ThingBackground =
                        if (bg.mode === com.ywwynm.everythingdone.model.ThingBackground.Mode.PURE)
                                com.ywwynm.everythingdone.model.ThingBackground.pure(s)!!
                        else com.ywwynm.everythingdone.model.ThingBackground.gradient(s, e, bg.orientation)!!
                com.ywwynm.everythingdone.utils.BackgroundUtil.applyCardBackground(
                        holder.cv, tinted)
                holder.ivStickyOngoing!!.imageAlpha = alpha
            }
        }
        val rvPreview: RecyclerView = f(R.id.rv_app_widget_preview)
        val flp: FrameLayout.LayoutParams = rvPreview.layoutParams as FrameLayout.LayoutParams
        flp.width = DisplayUtil.getThingCardWidth(this)
        rvPreview.requestLayout()
        rvPreview.setAdapter(adapter)
        rvPreview.setLayoutManager(LinearLayoutManager(this))
        rvPreview.setOnTouchListener(object : View.OnTouchListener {
            private var mDx: Int = 0
            private var mDy: Int = 0
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val rawX: Int = event.rawX.toInt()
                val rawY: Int = event.rawY.toInt()
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        val flp: FrameLayout.LayoutParams =
                                rvPreview.layoutParams as FrameLayout.LayoutParams
                        mDx = rawX - flp.leftMargin
                        mDy = rawY - flp.topMargin
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val flp: FrameLayout.LayoutParams =
                                rvPreview.layoutParams as FrameLayout.LayoutParams
                        flp.leftMargin = rawX - mDx
                        flp.topMargin  = rawY - mDy
                        rvPreview.requestLayout()
                        return true
                    }
                }
                return false
            }
        })

        val sbAlpha: SeekBar = f(R.id.sb_app_widget_alpha)
        sbAlpha.setMax(100)
        sbAlpha.progress = 100
        DisplayUtil.setSeekBarColor(sbAlpha, ContextCompat.getColor(this, R.color.app_accent))
        sbAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mWidgetAlpha = progress
                    adapter.notifyDataSetChanged()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) { }

            override fun onStopTrackingTouch(seekBar: SeekBar) { }
        })

        val btFinish: Button = f(R.id.bt_finish_set_alpha_app_widget)
        DisplayUtil.setButtonColor(btFinish, Color.WHITE)
        // Phase 8: gradient text for the "Done" label.
        com.ywwynm.everythingdone.utils.BackgroundUtil.applyTextBackground(
                btFinish, thing.getBackground())
        btFinish.setOnClickListener { v -> endSelectThing(thing) }
    }

    private fun endPreviewAppWidget() {
        mFlPreviewAndConfig!!.visibility = View.GONE
        mActionBar!!.visibility = View.VISIBLE
        mRecyclerView!!.visibility = View.VISIBLE

        updateStatusBarAndBottomUi(true)
        DisplayUtil.darkStatusBar(this)
    }

    private fun endSelectThing(thing: Thing) {
        val clazz: Class<*> = getSenderClass()!!
        AppWidgetDAO.getInstance(this)!!.insert(mAppWidgetId, thing.id,
                AppWidgetHelper.getSizeByProviderClass(clazz), mWidgetAlpha,
                ThingWidgetInfo.STYLE_NORMAL)

        val views: RemoteViews = AppWidgetHelper.createRemoteViewsForSingleThing(
                this, thing, -1, mAppWidgetId, clazz)
        AppWidgetManager.getInstance(this).updateAppWidget(mAppWidgetId, views)

        val intent = Intent()
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mAppWidgetId)
        setResult(RESULT_OK, intent)
        finish()
    }

    inner class ThingsAdapter : BaseThingsAdapter(this@BaseThingWidgetConfiguration) {

        override fun getCurrentMode(): Int {
            return ModeManager.NORMAL
        }

        override fun getThings(): MutableList<Thing?>? {
            return mThings
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseThingViewHolder {
            return Holder(mInflater!!.inflate(R.layout.card_thing, parent, false))
        }

        override fun onBindViewHolder(holder: BaseThingViewHolder, position: Int) {
            val m: Int = (mDensity * 6).toInt()

            val lp: StaggeredGridLayoutManager.LayoutParams =
                    holder.itemView.layoutParams as StaggeredGridLayoutManager.LayoutParams
            lp.setMargins(m, m, m, m)

            super.onBindViewHolder(holder, position)
        }

        inner class Holder(item: View) : BaseThingViewHolder(item) {

            init {
                cv!!.setOnClickListener { v ->
                    updateStatusBarAndBottomUi(false)
                    previewAppWidget(mThings!![adapterPosition]!!)
                }
            }
        }

    }
}
