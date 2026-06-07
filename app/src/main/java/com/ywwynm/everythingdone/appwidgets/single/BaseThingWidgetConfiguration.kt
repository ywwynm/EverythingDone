@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.appwidgets.single

import android.app.WallpaperManager
import androidx.activity.OnBackPressedCallback
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
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
import android.view.ViewOutlineProvider
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
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.model.ThingWidgetInfo
import com.ywwynm.everythingdone.permission.PermissionUtil
import com.ywwynm.everythingdone.permission.SimplePermissionCallback
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.EdgeEffectUtil

import kotlin.math.max
import kotlin.math.min

/**
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 */
open class BaseThingWidgetConfiguration : EverythingDoneBaseActivity() {

    protected open fun getSenderClass(): Class<*>? {
        return try {
            val provider = AppWidgetManager.getInstance(this)
                    .getAppWidgetInfo(mAppWidgetId)
                    ?.provider
                    ?.className
            if (provider == null) {
                BaseThingWidget::class.java
            } else {
                Class.forName(provider)
            }
        } catch (_: Exception) {
            BaseThingWidget::class.java
        }
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
                    *PermissionUtil.getRequiredPermissionsForThings(mThings))
        } else {
            initRecyclerView()
        }
    }

    private fun updateStatusBarAndBottomUi(selecting: Boolean) {
        val window: Window = getWindow()
        val flp: FrameLayout.LayoutParams = mLlConfig!!.layoutParams as FrameLayout.LayoutParams

        if (selecting) {
            DisplayUtil.clearBottomInsetAsMargin(mLlConfig)
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

        mActionBar!!.setNavigationOnClickListener { _ -> finish() }
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
        } catch (_: SecurityException) {
            ivBackground.setBackgroundColor(0xCC000000.toInt())
        }

        val previewContainer: FrameLayout = f(R.id.fl_app_widget_preview)
        layoutPreviewContainer(previewContainer, getSenderClass())
        renderPreviewAppWidget(previewContainer, thing)
        previewContainer.setOnTouchListener(object : View.OnTouchListener {
            private var mDx: Int = 0
            private var mDy: Int = 0
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val rawX: Int = event.rawX.toInt()
                val rawY: Int = event.rawY.toInt()
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        val flp: FrameLayout.LayoutParams =
                                previewContainer.layoutParams as FrameLayout.LayoutParams
                        mDx = rawX - flp.leftMargin
                        mDy = rawY - flp.topMargin
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val flp: FrameLayout.LayoutParams =
                                previewContainer.layoutParams as FrameLayout.LayoutParams
                        flp.leftMargin = rawX - mDx
                        flp.topMargin  = rawY - mDy
                        previewContainer.requestLayout()
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
                    renderPreviewAppWidget(previewContainer, thing)
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
        btFinish.setOnClickListener { _ -> endSelectThing(thing) }
    }

    private fun layoutPreviewContainer(previewContainer: FrameLayout, clazz: Class<*>?) {
        val sizeDp = AppWidgetHelper.getDefaultSizeDpByProviderClass(clazz)
        val density = DisplayUtil.getScreenDensity(this)
        val targetWidth = max(1, (sizeDp[0] * density).toInt())
        val targetHeight = max(1, (sizeDp[1] * density).toInt())
        val maxWidth = max(1, resources.displayMetrics.widthPixels - (32 * density).toInt())
        val maxHeight = max(1, resources.displayMetrics.heightPixels - (128 * density).toInt())
        val scale = min(
                1f,
                min(maxWidth.toFloat() / targetWidth, maxHeight.toFloat() / targetHeight))

        val flp: FrameLayout.LayoutParams =
                previewContainer.layoutParams as FrameLayout.LayoutParams
        flp.width = max(1, (targetWidth * scale).toInt())
        flp.height = max(1, (targetHeight * scale).toInt())
        flp.leftMargin = 0
        flp.topMargin = 0
        previewContainer.requestLayout()
        installPreviewContainerOutline(previewContainer)
    }

    private fun installPreviewContainerOutline(previewContainer: FrameLayout) {
        val radius = resources.getDimension(R.dimen.thing_card_corner_radius)
        previewContainer.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        previewContainer.clipToOutline = true
        previewContainer.invalidateOutline()
        previewContainer.post { previewContainer.invalidateOutline() }
    }

    private fun renderPreviewAppWidget(previewContainer: FrameLayout, thing: Thing) {
        try {
            val views = AppWidgetHelper.createRemoteViewsForSingleThingPreview(
                    this, Thing(thing), mAppWidgetId, getSenderClass(), mWidgetAlpha)
            val rendered = views.apply(this, previewContainer)
            previewContainer.removeAllViews()
            previewContainer.addView(rendered, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT))
        } catch (e: Exception) {
            e.printStackTrace()
            renderFallbackThingCardPreview(previewContainer, thing)
        }
    }

    private fun renderFallbackThingCardPreview(previewContainer: FrameLayout, thing: Thing) {
        previewContainer.removeAllViews()

        val rvPreview = RecyclerView(this)
        rvPreview.overScrollMode = View.OVER_SCROLL_NEVER
        rvPreview.isVerticalScrollBarEnabled = false
        rvPreview.layoutManager = object : LinearLayoutManager(this) {
            override fun canScrollVertically(): Boolean {
                return false
            }
        }
        previewContainer.addView(rvPreview, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT))

        val previewThings = mutableListOf<Thing?>(Thing(thing))
        val adapter: BaseThingsAdapter = object : BaseThingsAdapter(this@BaseThingWidgetConfiguration) {

            override fun getCurrentMode(): Int {
                return ModeManager.NORMAL
            }

            override fun getThings(): MutableList<Thing?> {
                return previewThings
            }

            override fun isFullSpanThingCard(thing: Thing): Boolean {
                return thing.type != Thing.HEADER
                        && thing.type < Thing.NOTIFICATION_UNDERWAY
                        && thing.thingCardAppearance.spanMode == Thing.THING_CARD_SPAN_FULL
            }

            override fun onBindViewHolder(holder: BaseThingViewHolder, position: Int) {
                super.onBindViewHolder(holder, position)
                holder.cv!!.radius = 0f
                holder.cv.cardElevation = 0f

                val alpha: Int = (mWidgetAlpha / 100f * 255).toInt()
                val bg: ThingBackground = thing.getBackground()!!
                val s: Int = DisplayUtil.getTransparentColor(bg.color, alpha)
                val e: Int = DisplayUtil.getTransparentColor(bg.endColor, alpha)
                val tinted: ThingBackground =
                        if (bg.mode === ThingBackground.Mode.PURE) {
                            ThingBackground.pure(s)
                        } else {
                            ThingBackground.gradient(s, e, bg.orientation)
                        }
                BackgroundUtil.applyCardBackground(holder.cv, tinted)
                holder.ivStickyOngoing!!.imageAlpha = alpha
            }
        }

        val previewWidth = max(1, previewContainer.layoutParams.width)
        val previewHeight = max(1, previewContainer.layoutParams.height)
        adapter.setCardWidth(previewWidth)
        adapter.setFullSpanCardWidth(previewWidth)
        adapter.setThingCardSurfaceAvailableHeight(previewHeight)
        rvPreview.adapter = adapter
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

        override fun isFullSpanThingCard(thing: Thing): Boolean {
            return thing.type != Thing.HEADER
                    && thing.type < Thing.NOTIFICATION_UNDERWAY
                    && thing.thingCardAppearance.spanMode == Thing.THING_CARD_SPAN_FULL
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseThingViewHolder {
            return Holder(mInflater!!.inflate(R.layout.card_thing, parent, false))
        }

        override fun onBindViewHolder(holder: BaseThingViewHolder, position: Int) {
            val m: Int = (mDensity * 6).toInt()

            val lp: StaggeredGridLayoutManager.LayoutParams =
                    holder.itemView.layoutParams as StaggeredGridLayoutManager.LayoutParams
            lp.setMargins(m, m, m, m)
            lp.isFullSpan = isFullSpanThingCard(mThings!![position]!!)

            super.onBindViewHolder(holder, position)
        }

        inner class Holder(item: View) : BaseThingViewHolder(item) {

            init {
                cv!!.setOnClickListener { _ ->
                    updateStatusBarAndBottomUi(false)
                    previewAppWidget(mThings!![adapterPosition]!!)
                }
            }
        }

    }
}
