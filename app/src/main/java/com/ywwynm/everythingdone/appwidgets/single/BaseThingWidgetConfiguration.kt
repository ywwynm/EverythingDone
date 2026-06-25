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
import android.os.Parcelable
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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.SeekBar
import android.widget.TextView

import com.bumptech.glide.Glide
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.EverythingDoneBaseActivity
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.adapters.BaseThingsAdapter
import com.ywwynm.everythingdone.adapters.ThingsAdapter
import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper
import com.ywwynm.everythingdone.database.AppWidgetDAO
import com.ywwynm.everythingdone.database.ThingDAO
import com.ywwynm.everythingdone.database.ThingFolderDAO
import com.ywwynm.everythingdone.helpers.AuthenticationHelper
import com.ywwynm.everythingdone.managers.ModeManager
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.model.ThingFolder
import com.ywwynm.everythingdone.model.ThingFolderCardPresentation
import com.ywwynm.everythingdone.model.ThingListEntry
import com.ywwynm.everythingdone.model.ThingWidgetInfo
import com.ywwynm.everythingdone.permission.PermissionUtil
import com.ywwynm.everythingdone.permission.SimplePermissionCallback
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.EdgeEffectUtil
import com.ywwynm.everythingdone.utils.ThingsSorter

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

    private var mAdapter: MixedThingsAdapter? = null
    private var mThingCardAdapter: ThingCardDelegateAdapter? = null
    private var mFolderCardAdapter: FolderCardDelegateAdapter? = null
    private var mThings: MutableList<Thing?>? = null
    private var mEntries: MutableList<ConfigEntry> = ArrayList()
    private var mFolderDao: ThingFolderDAO? = null
    private var mCurrentFolderId: Long? = null
    private val mFolderScrollStates = HashMap<Long, Parcelable>()
    private val mAuthenticatedPrivateFolderIds = HashSet<Long>()
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
        syncCardDelegateRecyclerView()

        if (mThings!!.size > 1) {
            mRecyclerView!!.scrollToPosition(0)
        }
        mAdapter!!.notifyDataSetChanged()
        mRecyclerView!!.post {
            syncCardDelegateRecyclerView()
            mAdapter!!.notifyDataSetChanged()
        }
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

        mFolderDao = ThingFolderDAO.getInstance(this)
        loadCurrentFolderEntries()
        mThingCardAdapter = ThingCardDelegateAdapter()
        mFolderCardAdapter = FolderCardDelegateAdapter()
        // The thing-picker list is an in-app browsing list, so it animates like the
        // home list. The actual widget preview is rendered through RemoteViews
        // (renderPreviewAppWidget) and is inherently static. See ADR-0007.
        mAdapter = MixedThingsAdapter()
    }

    private fun loadCurrentFolderEntries() {
        val thingDAO = ThingDAO.getInstance(this)!!
        val folderDAO = mFolderDao ?: ThingFolderDAO.getInstance(this)!!
        val loadedThings = thingDAO.getThingsForProjection(
            Def.ThingStatus.UNDERWAY,
            ThingWidgetInfo.TYPE_FILTER_ALL,
            mCurrentFolderId,
            null,
            0
        )
        mThings = loadedThings
            .filter { thing ->
                thing != null &&
                    thing.type != Thing.HEADER &&
                    Thing.isRealThingType(thing.type)
            }
            .map { Thing(it!!) }
            .toMutableList()

        val entries = ArrayList<ConfigEntry>()
        for ((thingIndex, thing) in mThings!!.withIndex()) {
            entries.add(ConfigEntry.ThingEntry(thing!!, thingIndex))
        }
        for (folderEntry in folderDAO.getFolderEntriesForWidgetProjection(
            mCurrentFolderId,
            ThingWidgetInfo.TYPE_FILTER_ALL,
            Def.ThingStatus.UNDERWAY
        )) {
            entries.add(ConfigEntry.FolderEntry(folderEntry))
        }
        mEntries = entries.sortedWith { entry1, entry2 ->
            val result = ThingsSorter.compareByLocationAndSticky(
                entry1.location,
                entry2.location
            )
            if (result != 0) result else entry1.stableId.compareTo(entry2.stableId)
        }.toMutableList()
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
        applyTopChrome()

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
        syncCardDelegateRecyclerView()
        mRecyclerView!!.setAdapter(mAdapter)
        mRecyclerView!!.post {
            syncCardDelegateRecyclerView()
            mAdapter!!.notifyDataSetChanged()
        }
    }

    private fun syncCardDelegateRecyclerView() {
        mThingCardAdapter?.setHostRecyclerViewForDelegatedBinding(mRecyclerView)
        mFolderCardAdapter?.setHostRecyclerViewForDelegatedBinding(mRecyclerView)
    }

    override fun setActionbar() {
        setSupportActionBar(mActionBar)
        val actionBar: ActionBar? = supportActionBar
        actionBar?.setDisplayHomeAsUpEnabled(true)

        mActionBar!!.setNavigationOnClickListener { _ -> handleToolbarNavigation() }
        updateActionbarTitle()
    }

    override fun setEvents() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (mFlPreviewAndConfig!!.visibility == View.VISIBLE) {
                    endPreviewAppWidget()
                } else if (openParentFolder()) {
                    return
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

    private fun updateActionbarTitle() {
        val folderId = mCurrentFolderId
        val title = if (folderId == null) {
            getString(R.string.title_activity_thing_widget_configure)
        } else {
            val folder = mFolderDao!!.getFolderById(folderId)
            folder?.title?.ifEmpty {
                getString(R.string.default_thing_folder_name)
            } ?: getString(R.string.title_activity_thing_widget_configure)
        }
        supportActionBar?.title = title
        applyTopChrome()
    }

    private fun applyTopChrome() {
        val folderBackground = getCurrentFolderBackgroundForChrome()
        val background = folderBackground ?: App.defaultAccentBackground
        val foreground = getTopChromeForeground(folderBackground)

        BackgroundUtil.applyBackground(findViewById<View>(R.id.view_status_bar), background)
        BackgroundUtil.applyBackground(mActionBar, background)
        if (folderBackground != null && BackgroundUtil.isLight(background)) {
            DisplayUtil.darkStatusBar(this)
        } else {
            DisplayUtil.cancelDarkStatusBar(this)
        }

        val toolbar = mActionBar ?: return
        applyToolbarForeground(toolbar, foreground)
        toolbar.post {
            applyToolbarForeground(toolbar, foreground)
        }
    }

    private fun getCurrentFolderBackgroundForChrome(): ThingBackground? {
        val folderId = mCurrentFolderId ?: return null
        val folder = mFolderDao?.getFolderById(folderId) ?: return null
        return folder.getBackground() ?: ThingBackground.pure(folder.getColor())
    }

    private fun getTopChromeForeground(folderBackground: ThingBackground?): Int {
        if (folderBackground == null) {
            return ContextCompat.getColor(this, R.color.white_86p)
        }
        return BackgroundUtil.onColor(
            folderBackground.representativeColor(),
            BackgroundUtil.ON_ALPHA_PRIMARY
        )
    }

    private fun applyToolbarForeground(toolbar: Toolbar, foreground: Int) {
        toolbar.setTitleTextColor(foreground)
        toolbar.navigationIcon?.let { icon ->
            toolbar.navigationIcon = DisplayUtil.opaqueTintDrawable(this, icon, foreground)
        }
        toolbar.overflowIcon?.let { icon ->
            toolbar.overflowIcon = DisplayUtil.opaqueTintDrawable(this, icon, foreground)
        }
        val menu = toolbar.menu
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            val icon = item.icon ?: continue
            item.icon = DisplayUtil.opaqueTintDrawable(this, icon, foreground)
        }
    }

    private fun handleToolbarNavigation() {
        if (!openParentFolder()) {
            finish()
        }
    }

    private fun openParentFolder(): Boolean {
        val folderId = mCurrentFolderId ?: return false
        saveCurrentFolderScrollState()
        val path = mFolderDao!!.getFolderPath(folderId)
        mCurrentFolderId = if (path.size <= 1) {
            null
        } else {
            path[path.size - 2].id
        }
        trimAuthenticatedPrivateFoldersToCurrentPath()
        reloadCurrentFolderUi(restoreScroll = true)
        return true
    }

    private fun openFolder(folder: ThingFolder) {
        saveCurrentFolderScrollState()
        mCurrentFolderId = folder.id
        reloadCurrentFolderUi(restoreScroll = false)
    }

    private fun previewSelectedThing(thing: Thing) {
        updateStatusBarAndBottomUi(false)
        previewAppWidget(thing)
    }

    private fun openFolderEntry(entry: ThingListEntry.FolderEntry) {
        val folder = entry.folder
        if (entry.effectivePrivate && !isFolderPrivacyAuthenticated(folder.id)) {
            authenticateAndOpenFolder(folder)
        } else {
            openFolder(folder)
        }
    }

    private fun trimAuthenticatedPrivateFoldersToCurrentPath() {
        if (mAuthenticatedPrivateFolderIds.isEmpty()) return
        val folderId = mCurrentFolderId
        if (folderId == null) {
            mAuthenticatedPrivateFolderIds.clear()
            return
        }
        val pathIds = mFolderDao!!.getFolderPath(folderId).map { it.id }.toHashSet()
        mAuthenticatedPrivateFolderIds.retainAll(pathIds)
    }

    private fun markFolderPrivacyAuthenticated(folderId: Long) {
        val path = mFolderDao!!.getFolderPath(folderId)
        for (folder in path) {
            if (folder.isPrivate) {
                mAuthenticatedPrivateFolderIds.add(folder.id)
            }
        }
    }

    private fun isFolderPrivacyAuthenticated(folderId: Long): Boolean {
        val path = mFolderDao!!.getFolderPath(folderId)
        for (folder in path) {
            if (folder.isPrivate && mAuthenticatedPrivateFolderIds.contains(folder.id)) {
                return true
            }
        }
        return false
    }

    private fun isCurrentFolderPrivacyAuthenticated(): Boolean {
        val folderId = mCurrentFolderId ?: return false
        return isFolderPrivacyAuthenticated(folderId)
    }

    private fun isCurrentFolderEffectivelyPrivate(): Boolean {
        val folderId = mCurrentFolderId ?: return false
        return mFolderDao!!.isEffectivelyPrivate(folderId)
    }

    private fun reloadCurrentFolderUi(restoreScroll: Boolean) {
        loadCurrentFolderEntries()
        updateActionbarTitle()
        syncCardDelegateRecyclerView()
        mAdapter!!.notifyDataSetChanged()
        if (restoreScroll) {
            restoreCurrentFolderScrollStateOrTop()
        } else {
            scrollToTop()
        }
    }

    private fun scrollToTop() {
        val layoutManager = mStaggeredGridLayoutManager
        if (layoutManager != null) {
            // StaggeredGridLayoutManager.scrollToPosition(0) only guarantees item 0 is
            // visible; if it is already partially visible the current offset is kept,
            // leaking the parent list's scroll distance into the just-opened folder.
            // scrollToPositionWithOffset(0, 0) forces the top item to align with offset 0.
            layoutManager.scrollToPositionWithOffset(0, 0)
        } else {
            mRecyclerView?.scrollToPosition(0)
        }
    }

    private fun saveCurrentFolderScrollState() {
        val key = mCurrentFolderId ?: ROOT_FOLDER_KEY
        val state = mRecyclerView?.layoutManager?.onSaveInstanceState() ?: return
        mFolderScrollStates[key] = state
    }

    private fun restoreCurrentFolderScrollStateOrTop() {
        val recyclerView = mRecyclerView ?: return
        val key = mCurrentFolderId ?: ROOT_FOLDER_KEY
        val state = mFolderScrollStates[key]
        if (state == null) {
            scrollToTop()
            return
        }
        recyclerView.layoutManager?.onRestoreInstanceState(state)
        recyclerView.requestLayout()
    }

    private fun dp(value: Int): Int {
        return (resources.displayMetrics.density * value).toInt()
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
        DisplayUtil.setSeekBarBackground(sbAlpha, thing.getBackground())
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

        val btFinish: TextView = f(R.id.bt_finish_set_alpha_app_widget)
        BackgroundUtil.installThingPillRipple(
            btFinish,
            thing.getBackground()?.representativeColor() ?: thing.getColor()
        )
        btFinish.background = null
        BackgroundUtil.applyTextBackground(btFinish, thing.getBackground())
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
        installRoundedPreviewOutline(previewContainer)
    }

    private fun installRoundedPreviewOutline(view: View) {
        val radius = resources.getDimension(R.dimen.thing_card_corner_radius)
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        view.clipToOutline = true
        if (view is ViewGroup) {
            view.clipChildren = true
            view.clipToPadding = true
        }
        view.invalidateOutline()
        view.post { view.invalidateOutline() }
    }

    private fun renderPreviewAppWidget(previewContainer: FrameLayout, thing: Thing) {
        try {
            val views = AppWidgetHelper.createRemoteViewsForSingleThingPreview(
                    this, Thing(thing), mAppWidgetId, getSenderClass(), mWidgetAlpha)
            val rendered = views.apply(this, previewContainer)
            installRoundedPreviewOutline(rendered)
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
        adapter.setAnimatedPlaybackEnabled(false)
        rvPreview.adapter = adapter
    }

    private fun endPreviewAppWidget() {
        mFlPreviewAndConfig!!.visibility = View.GONE
        mActionBar!!.visibility = View.VISIBLE
        mRecyclerView!!.visibility = View.VISIBLE

        updateStatusBarAndBottomUi(true)
        applyTopChrome()
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

    private sealed class ConfigEntry {
        abstract val stableId: Long
        abstract val location: Long

        data class ThingEntry(
            val thing: Thing,
            val thingIndex: Int
        ) : ConfigEntry() {
            override val stableId: Long
                get() = thing.id
            override val location: Long
                get() = thing.location
        }

        data class FolderEntry(
            val entry: ThingListEntry.FolderEntry
        ) : ConfigEntry() {
            override val stableId: Long
                get() = entry.stableId
            override val location: Long
                get() = entry.location
        }
    }

    private inner class ThingCardDelegateAdapter :
        ThingsAdapter(this@BaseThingWidgetConfiguration.application as App, null) {

        init {
            setShouldThingsAnimWhenAppearing(false)
        }

        override fun getCurrentMode(): Int {
            return ModeManager.NORMAL
        }

        override fun getThings(): MutableList<Thing?>? {
            return mThings
        }

        override fun getEntries(): List<ThingListEntry>? {
            return null
        }

        override fun getStickyThingParentFolderBackground(thing: Thing): ThingBackground? {
            val folderId = thing.folderId ?: return null
            return mFolderDao?.getFolderById(folderId)?.getBackground()
        }

        override fun isThingEffectivelyPrivate(thing: Thing): Boolean {
            return (thing.isPrivate() || isCurrentFolderEffectivelyPrivate()) &&
                !isCurrentFolderPrivacyAuthenticated()
        }

        override fun isFullSpanThingCard(thing: Thing): Boolean {
            return thing.type != Thing.HEADER
                    && thing.type < Thing.NOTIFICATION_UNDERWAY
                    && thing.thingCardAppearance.spanMode == Thing.THING_CARD_SPAN_FULL
        }
    }

    private inner class FolderCardDelegateAdapter :
        ThingsAdapter(
            this@BaseThingWidgetConfiguration.application as App,
            object : ThingsAdapter.OnItemTouchedListener {
                override fun onItemTouch(v: View?, event: MotionEvent?): Boolean {
                    return false
                }

                override fun onItemClick(v: View?, listPosition: Int) { }

                override fun onItemLongClick(v: View?, listPosition: Int): Boolean {
                    return false
                }

                override fun onFolderThumbnailClick(v: View?, thing: Thing) {
                    previewSelectedThing(thing)
                }

                override fun onFolderThumbnailFolderClick(
                    v: View?,
                    entry: ThingListEntry.FolderEntry
                ) {
                    openFolderEntry(entry)
                }
            }
        ) {

        fun bindFolderHolder(
            holder: BaseThingsAdapter.BaseThingViewHolder,
            entry: ThingListEntry.FolderEntry
        ) {
            bindFolderCard(holder, entry)
        }

        override fun shouldShowFolderPrivateContent(): Boolean {
            return isCurrentFolderPrivacyAuthenticated()
        }
    }

    private inner class MixedThingsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int): Int {
            return when (mEntries[position]) {
                is ConfigEntry.ThingEntry -> VIEW_TYPE_THING
                is ConfigEntry.FolderEntry -> VIEW_TYPE_FOLDER
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == VIEW_TYPE_THING) {
                mThingCardAdapter!!.onCreateViewHolder(parent, viewType)
            } else {
                mFolderCardAdapter!!.onCreateViewHolder(parent, viewType)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            syncCardDelegateRecyclerView()
            when (val entry = mEntries[position]) {
                is ConfigEntry.ThingEntry -> {
                    val thingHolder = holder as BaseThingsAdapter.BaseThingViewHolder
                    mThingCardAdapter!!.onBindViewHolder(thingHolder, entry.thingIndex)
                    thingHolder.cv!!.setOnClickListener {
                        previewSelectedThing(entry.thing)
                    }
                }
                is ConfigEntry.FolderEntry -> {
                    val folderHolder = holder as BaseThingsAdapter.BaseThingViewHolder
                    mFolderCardAdapter!!.bindFolderHolder(folderHolder, entry.entry)
                    folderHolder.cv!!.setOnClickListener {
                        openFolderEntry(entry.entry)
                    }
                }
            }
        }

        override fun getItemCount(): Int {
            return mEntries.size
        }

        override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
            super.onViewAttachedToWindow(holder)
            val lp = holder.itemView.layoutParams
            if (lp is StaggeredGridLayoutManager.LayoutParams) {
                val position = holder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    lp.isFullSpan = when (val entry = mEntries[position]) {
                        is ConfigEntry.ThingEntry ->
                            entry.thing.type != Thing.HEADER &&
                                entry.thing.type < Thing.NOTIFICATION_UNDERWAY &&
                                entry.thing.thingCardAppearance.spanMode ==
                                Thing.THING_CARD_SPAN_FULL
                        is ConfigEntry.FolderEntry ->
                            entry.entry.folder.effectiveCardPresentation().spanMode ==
                                ThingFolderCardPresentation.SPAN_FULL
                    }
                }
            }
        }

    }

    private fun authenticateAndOpenFolder(folder: ThingFolder) {
        val password = getSharedPreferences(Def.Meta.PREFERENCES_NAME, MODE_PRIVATE)
            .getString(Def.Meta.KEY_PRIVATE_PASSWORD, null)
        AuthenticationHelper.authenticate(
            this,
            folder.getBackground() ?: ThingBackground.pure(folder.getColor()),
            getString(R.string.open_private_thing_folder),
            password,
            object : AuthenticationHelper.AuthenticationCallback {
                override fun onAuthenticated() {
                    markFolderPrivacyAuthenticated(folder.id)
                    openFolder(folder)
                }

                override fun onCancel() { }
            }
        )
    }

    private companion object {
        const val VIEW_TYPE_THING = 0
        const val VIEW_TYPE_FOLDER = 1
        const val ROOT_FOLDER_KEY = -1L
    }
}
