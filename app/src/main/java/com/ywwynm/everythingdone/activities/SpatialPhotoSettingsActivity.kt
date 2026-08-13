package com.ywwynm.everythingdone.activities

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.ConnectivityManager
import android.os.Build
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.widget.CompoundButtonCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.fragments.AlertDialogFragment
import com.ywwynm.everythingdone.fragments.ChooserDialogFragment
import com.ywwynm.everythingdone.permission.SimplePermissionCallback
import com.ywwynm.everythingdone.spatial.SpatialCatalogClient
import com.ywwynm.everythingdone.spatial.SpatialBoundaryRefinementCatalogEntry
import com.ywwynm.everythingdone.spatial.SpatialBoundaryRefinementDownloadCoordinator
import com.ywwynm.everythingdone.spatial.SpatialBoundaryRefinementDownloadWorker
import com.ywwynm.everythingdone.spatial.SpatialBoundaryRefinementModel
import com.ywwynm.everythingdone.spatial.SpatialBoundaryRefinementModelStore
import com.ywwynm.everythingdone.spatial.SpatialDepthModel
import com.ywwynm.everythingdone.spatial.SpatialDerivativeStore
import com.ywwynm.everythingdone.spatial.SpatialInpaintingCatalogEntry
import com.ywwynm.everythingdone.spatial.SpatialInpaintingDownloadCoordinator
import com.ywwynm.everythingdone.spatial.SpatialInpaintingDownloadWorker
import com.ywwynm.everythingdone.spatial.SpatialInpaintingModel
import com.ywwynm.everythingdone.spatial.SpatialInpaintingModelStore
import com.ywwynm.everythingdone.spatial.SpatialInpaintingQuality
import com.ywwynm.everythingdone.spatial.SpatialModelDownloadCoordinator
import com.ywwynm.everythingdone.spatial.SpatialModelDownloadWorker
import com.ywwynm.everythingdone.spatial.SpatialModelStore
import com.ywwynm.everythingdone.spatial.SpatialPreferences
import com.ywwynm.everythingdone.spatial.SpatialRuntimeCatalogEntry
import com.ywwynm.everythingdone.spatial.SpatialRuntimeDownloadCoordinator
import com.ywwynm.everythingdone.spatial.SpatialRuntimeDownloadWorker
import com.ywwynm.everythingdone.spatial.SpatialRuntimeStore
import com.ywwynm.everythingdone.spatial.SpatialSegmentationCatalogEntry
import com.ywwynm.everythingdone.spatial.SpatialSegmentationDownloadCoordinator
import com.ywwynm.everythingdone.spatial.SpatialSegmentationDownloadWorker
import com.ywwynm.everythingdone.spatial.SpatialSegmentationModel
import com.ywwynm.everythingdone.spatial.SpatialSegmentationModelStore
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.EdgeEffectUtil
import com.ywwynm.everythingdone.views.GradientRippleDrawable
import java.util.concurrent.Executors

class SpatialPhotoSettingsActivity : EverythingDoneBaseActivity() {

    private var toolbar: Toolbar? = null
    private var tiltSwitch: CompoundButton? = null
    private var storageText: TextView? = null
    private val background = Executors.newFixedThreadPool(2)
    private val workInfo = mutableMapOf<SpatialDepthModel, WorkInfo?>()
    private var runtimeWorkInfo: WorkInfo? = null
    private var mattingWorkInfo: WorkInfo? = null
    private var segmentationWorkInfo: WorkInfo? = null
    private var boundaryRefinementWorkInfo: WorkInfo? = null
    private val inpaintingWorkInfo =
        mutableMapOf<SpatialInpaintingModel, WorkInfo?>()
    private var runtimeEntry: SpatialRuntimeCatalogEntry? = null
    private val inpaintingEntries =
        mutableMapOf<SpatialInpaintingModel, SpatialInpaintingCatalogEntry>()
    private var segmentationEntry: SpatialSegmentationCatalogEntry? = null
    private var boundaryRefinementEntry: SpatialBoundaryRefinementCatalogEntry? = null
    private var runtimeCatalogLoading = false
    private var runtimeCatalogError: String? = null

    private data class CatalogSnapshot(
        val runtime: SpatialRuntimeCatalogEntry,
        val inpainting: Map<SpatialInpaintingModel, SpatialInpaintingCatalogEntry>,
        val segmentation: SpatialSegmentationCatalogEntry?,
        val boundaryRefinement: SpatialBoundaryRefinementCatalogEntry?
    )

    override fun getLayoutResource(): Int = R.layout.activity_spatial_photo_settings

    override fun initMembers() = Unit

    override fun findViews() {
        toolbar = f(R.id.actionbar)
        tiltSwitch = f(R.id.sw_spatial_tilt)
        storageText = f(R.id.tv_spatial_storage)
    }

    override fun initUI() {
        DisplayUtil.expandLayoutToStatusBarAboveLollipop(this)
        DisplayUtil.expandStatusBarViewAboveKitkat(f(R.id.view_status_bar))
        DisplayUtil.darkStatusBar(this)

        val accent = App.defaultAccentBackground
        BackgroundUtil.applyBackground(f<View>(R.id.view_status_bar), accent)
        BackgroundUtil.applyBackground(toolbar, accent)

        val scrollView: ScrollView = f(R.id.sv_spatial_settings)!!
        EdgeEffectUtil.forScrollView(scrollView, accent.color)
        DisplayUtil.applyBottomInsetAsScrollPadding(scrollView)

        applyGroupTitleAccents()
        applyControlAccents()

        tiltSwitch?.isChecked = SpatialPreferences.deviceTiltEnabled(this)
        refreshAll()
        refreshRuntimeCatalog()
    }

    override fun setActionbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.spatial_settings_title)
        toolbar?.setNavigationOnClickListener { finish() }
        // 顶栏背景为 accent 渐变，标题与返回图标改为偏白，与 SettingsActivity 一致。
        val fg = BackgroundUtil.onColor(App.defaultAccentBackground, BackgroundUtil.ON_ALPHA_PRIMARY)
        toolbar?.setTitleTextColor(fg)
        toolbar?.navigationIcon?.let {
            toolbar?.navigationIcon = DisplayUtil.opaqueTintDrawable(this, it, fg)
        }
    }

    private fun applyGroupTitleAccents() {
        val bg = App.defaultAccentBackground
        listOf(
            R.id.tv_title_group_spatial_runtime,
            R.id.tv_title_group_spatial_depth_models,
            R.id.tv_title_group_spatial_inpainting,
            R.id.tv_title_group_spatial_matting,
            R.id.tv_title_group_spatial_segmentation,
            R.id.tv_title_group_spatial_interaction,
            R.id.tv_title_group_spatial_storage
        ).forEach { id ->
            val tv = findViewById<TextView>(id)
            BackgroundUtil.applyTextBackground(tv, bg)
            val source = tv.compoundDrawablesRelative[0] ?: tv.compoundDrawables[0]
            val icon = source?.let { BackgroundUtil.tintDrawable(resources, it, bg) }
            if (icon != null) {
                val size = (20 * resources.displayMetrics.density).toInt()
                icon.setBounds(0, 0, size, size)
                tv.setCompoundDrawablesRelative(icon, null, null, null)
            }
        }
    }

    private fun applyControlAccents() {
        val bg = App.defaultAccentBackground
        listOf(
            R.id.btn_runtime, R.id.btn_zipdepth, R.id.btn_dav2, R.id.btn_da3,
            R.id.btn_migan, R.id.btn_aotgan, R.id.btn_modnet, R.id.btn_rfdetr_seg,
            R.id.btn_edgetam_refinement
        ).forEach { id ->
            val button = findViewById<Button>(id)
            BackgroundUtil.applyTextBackground(button, bg)
            // 文字动作（下载/取消）统一为胶囊形渐变 ripple。
            GradientRippleDrawable.applyAccentRipple(button, bg, bg.representativeColor())
        }
        // 清除行是整行动作：文字渐变 + 整行矩形渐变 ripple，左缘与其它行对齐。
        findViewById<TextView>(R.id.btn_clear_spatial_derivatives).let { clear ->
            BackgroundUtil.applyTextBackground(clear, bg)
            GradientRippleDrawable.applyAccentRowRipple(clear, bg, bg.representativeColor())
        }
        // 可点击整行统一为渐变矩形 ripple。
        listOf(
            R.id.runtime_component, R.id.model_zipdepth, R.id.model_dav2, R.id.model_da3,
            R.id.model_moge2,
            R.id.model_migan, R.id.model_aotgan, R.id.model_big_lama, R.id.model_modnet,
            R.id.model_rfdetr_seg, R.id.model_edgetam_refinement,
            R.id.rl_spatial_tilt_as_bt, R.id.row_inpainting_quality
        ).forEach { id ->
            GradientRippleDrawable.applyAccentRowRipple(
                findViewById(id), bg, bg.representativeColor()
            )
        }
        val deleteTint = ContextCompat.getColor(this, R.color.app_chrome_control_unchecked)
        listOf(
            R.id.iv_runtime_delete, R.id.iv_zipdepth_delete, R.id.iv_dav2_delete,
            R.id.iv_da3_delete, R.id.iv_migan_delete, R.id.iv_aotgan_delete,
            R.id.iv_modnet_delete, R.id.iv_rfdetr_seg_delete,
            R.id.iv_edgetam_refinement_delete
        ).forEach { id ->
            val icon = findViewById<android.widget.ImageView>(id)
            icon.setImageDrawable(
                DisplayUtil.opaqueTintDrawable(
                    this,
                    ContextCompat.getDrawable(this, R.drawable.vec_ic_delete),
                    deleteTint
                )
            )
            icon.background = GradientRippleDrawable(bg, shapeOval = true)
        }
        listOf(
            R.id.rb_zipdepth, R.id.rb_dav2, R.id.rb_da3, R.id.rb_migan, R.id.rb_aotgan,
            R.id.rb_rfdetr_seg, R.id.rb_edgetam_refinement
        ).forEach { id ->
            val radio = findViewById<RadioButton>(id)
            CompoundButtonCompat.setButtonTintList(
                radio,
                ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf()
                    ),
                    intArrayOf(
                        bg.representativeColor(),
                        ContextCompat.getColor(this, R.color.app_chrome_control_unchecked)
                    )
                )
            )
            GradientRippleDrawable.applyCheckboxRipple(radio, bg)
        }
        tiltSwitch?.let {
            BackgroundUtil.applyCheckboxAccent(
                it, bg,
                footprintDp = BackgroundUtil.CHECKBOX_LABEL_ROW_FOOTPRINT_DP,
                uncheckedGradient = true
            )
        }
    }

    private fun showAppAlert(
        title: String,
        content: String,
        confirmText: String,
        action: () -> Unit
    ) {
        val adf = AlertDialogFragment()
        adf.setTitleBackground(App.defaultAccentBackground)
        adf.setConfirmBackground(App.defaultAccentBackground)
        adf.setContentColor(ContextCompat.getColor(this, R.color.app_chrome_on_surface_medium))
        adf.setTitle(title)
        adf.setContent(content)
        adf.setConfirmText(confirmText)
        adf.setConfirmListener(object : AlertDialogFragment.ConfirmListener {
            override fun onConfirm() = action()
        })
        adf.show(supportFragmentManager, AlertDialogFragment.TAG)
    }

    override fun setEvents() {
        tiltSwitch?.setOnCheckedChangeListener { _, checked ->
            SpatialPreferences.setDeviceTiltEnabled(this, checked)
        }
        findViewById<View>(R.id.rl_spatial_tilt_as_bt).setOnClickListener {
            tiltSwitch?.toggle()
        }
        SpatialDepthModel.entries.forEach(::bindModel)
        findViewById<View>(R.id.runtime_component).setOnClickListener {
            val button = findViewById<View>(R.id.btn_runtime)
            if (button.visibility == View.VISIBLE) {
                button.performClick()
            } else {
                findViewById<View>(R.id.iv_runtime_delete).performClick()
            }
        }
        findViewById<View>(R.id.btn_runtime).setOnClickListener {
            onRuntimeAction()
        }
        findViewById<View>(R.id.iv_runtime_delete).setOnClickListener {
            onRuntimeAction()
        }
        SpatialInpaintingModel.entries.forEach(::bindInpaintingModel)
        bindInpaintingQuality()
        bindMattingModel()
        bindSegmentationModel()
        bindBoundaryRefinementModel()
        WorkManager.getInstance(applicationContext)
            .getWorkInfosForUniqueWorkLiveData(
                SpatialRuntimeDownloadCoordinator.UNIQUE_WORK_NAME
            )
            .observe(this) { infos ->
                runtimeWorkInfo = currentWorkInfo(infos)
                refreshRuntime()
            }
        findViewById<View>(R.id.btn_clear_spatial_derivatives).setOnClickListener {
            showAppAlert(
                title = getString(R.string.spatial_clear_derivatives),
                content = getString(R.string.spatial_clear_derivatives_confirm),
                confirmText = getString(R.string.confirm)
            ) {
                background.execute {
                    SpatialDerivativeStore(applicationContext).clearAll()
                    runOnUiThread { refreshStorage() }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
        if ((runtimeEntry == null || inpaintingEntries.isEmpty()) &&
            !runtimeCatalogLoading
        ) {
            refreshRuntimeCatalog()
        }
    }

    override fun onDestroy() {
        background.shutdownNow()
        super.onDestroy()
    }

    private fun bindModel(model: SpatialDepthModel) {
        radio(model).setOnClickListener {
            if (SpatialModelStore.isInstalled(this, model)) {
                SpatialPreferences.setSelectedModel(this, model)
                refreshAll()
            }
        }
        row(model).setOnClickListener { radio(model).performClick() }
        button(model).setOnClickListener { onModelAction(model) }
        deleteIcon(model).setOnClickListener { onModelAction(model) }
        WorkManager.getInstance(applicationContext)
            .getWorkInfosForUniqueWorkLiveData(
                SpatialModelDownloadCoordinator.uniqueWorkName(model)
            )
            .observe(this) { infos ->
                // 历史失败任务可能拥有更大的重试次数；当前活动任务必须优先，否则 UI 会错误显示
                // “下载”而不是“取消”。
                workInfo[model] = currentWorkInfo(infos)
                refreshModel(model)
                refreshRuntime()
            }
    }

    private fun bindInpaintingModel(model: SpatialInpaintingModel) {
        inpaintingRadio(model).setOnClickListener {
            if (SpatialInpaintingModelStore.isInstalled(this, model) &&
                SpatialInpaintingModelStore.isDeviceEligible(this, model)
            ) {
                SpatialPreferences.setSelectedInpaintingModel(this, model)
                refreshAll()
            }
        }
        inpaintingRow(model).setOnClickListener {
            inpaintingRadio(model).performClick()
        }
        inpaintingButton(model).setOnClickListener {
            onInpaintingAction(model)
        }
        inpaintingDeleteIcon(model).setOnClickListener {
            onInpaintingAction(model)
        }
        WorkManager.getInstance(applicationContext)
            .getWorkInfosForUniqueWorkLiveData(
                SpatialInpaintingDownloadCoordinator.uniqueWorkName(model)
            )
            .observe(this) { infos ->
                inpaintingWorkInfo[model] = currentWorkInfo(infos)
                refreshInpainting(model)
                refreshRuntime()
            }
    }

    private fun bindMattingModel() {
        val model = com.ywwynm.everythingdone.spatial.SpatialMattingModel.MODNET_PHOTOGRAPHIC
        findViewById<View>(R.id.model_modnet).setOnClickListener { onMattingAction(model) }
        findViewById<View>(R.id.btn_modnet).setOnClickListener { onMattingAction(model) }
        findViewById<View>(R.id.iv_modnet_delete).setOnClickListener { onMattingAction(model) }
        WorkManager.getInstance(applicationContext)
            .getWorkInfosForUniqueWorkLiveData(
                com.ywwynm.everythingdone.spatial.SpatialMattingDownloadCoordinator
                    .uniqueWorkName(model)
            )
            .observe(this) { infos ->
                mattingWorkInfo = currentWorkInfo(infos)
                refreshMatting()
                refreshRuntime()
            }
    }

    private fun onMattingAction(
        model: com.ywwynm.everythingdone.spatial.SpatialMattingModel
    ) {
        val info = mattingWorkInfo
        if (info?.let(::isActive) == true) {
            com.ywwynm.everythingdone.spatial.SpatialMattingDownloadCoordinator
                .cancel(this, model)
            refreshMatting()
            return
        }
        if (com.ywwynm.everythingdone.spatial.SpatialMattingModelStore.isInstalled(this, model)) {
            showAppAlert(
                title = getString(R.string.spatial_delete_model_title, model.displayName),
                content = getString(R.string.spatial_delete_model_message),
                confirmText = getString(R.string.act_delete)
            ) {
                background.execute {
                    com.ywwynm.everythingdone.spatial.SpatialMattingModelStore
                        .delete(applicationContext, model)
                    runOnUiThread { refreshAll() }
                }
            }
            return
        }
        if (!com.ywwynm.everythingdone.spatial.SpatialMattingModelStore
                .isDeviceEligible(this, model)
        ) {
            Toast.makeText(this, R.string.spatial_model_device_ineligible, Toast.LENGTH_LONG)
                .show()
            return
        }
        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        com.ywwynm.everythingdone.spatial.SpatialMattingDownloadCoordinator.enqueue(
            this,
            model,
            allowMetered = connectivity.isActiveNetworkMetered
        )
        refreshMatting()
    }

    private fun refreshMatting() {
        val model = com.ywwynm.everythingdone.spatial.SpatialMattingModel.MODNET_PHOTOGRAPHIC
        val installed =
            com.ywwynm.everythingdone.spatial.SpatialMattingModelStore.isInstalled(this, model)
        val eligible =
            com.ywwynm.everythingdone.spatial.SpatialMattingModelStore.isDeviceEligible(this, model)
        val info = mattingWorkInfo
        val active = info?.let(::isActive) == true
        val size = Formatter.formatFileSize(this, model.sizeBytes)
        findViewById<TextView>(R.id.tv_modnet_status).text = when {
            installed -> getString(R.string.spatial_model_installed, size)
            !eligible -> getString(
                R.string.spatial_model_memory_required,
                model.minimumTotalRamMb / 1024
            )
            active -> mattingWorkStatus(info, size)
            info?.state == WorkInfo.State.FAILED -> failedStatusText(
                info.outputData.getString(
                    com.ywwynm.everythingdone.spatial.SpatialMattingDownloadWorker.KEY_ERROR
                ),
                info.outputData.getString(
                    com.ywwynm.everythingdone.spatial.SpatialMattingDownloadWorker.KEY_ERROR_STAGE
                ) == com.ywwynm.everythingdone.spatial.SpatialMattingDownloadWorker
                    .ERROR_STAGE_SELF_TEST
            )
            else -> getString(R.string.spatial_model_not_downloaded, size)
        }
        val showDelete = installed && !active
        findViewById<View>(R.id.iv_modnet_delete).visibility =
            if (showDelete) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btn_modnet).apply {
            visibility = if (showDelete) View.GONE else View.VISIBLE
            isEnabled = eligible || active
            setText(if (active) R.string.cancel else R.string.spatial_download)
        }
    }

    private fun mattingWorkStatus(info: WorkInfo, size: String): String {
        val downloaded = info.progress.getLong(
            com.ywwynm.everythingdone.spatial.SpatialMattingDownloadWorker.KEY_DOWNLOADED, 0L
        )
        val total = info.progress.getLong(
            com.ywwynm.everythingdone.spatial.SpatialMattingDownloadWorker.KEY_TOTAL, 0L
        )
        val state = info.progress.getString(
            com.ywwynm.everythingdone.spatial.SpatialMattingDownloadWorker.KEY_STATE
        )
        return when (state) {
            com.ywwynm.everythingdone.spatial.SpatialMattingDownloadWorker.STATE_VERIFYING ->
                getString(R.string.spatial_download_verifying)
            com.ywwynm.everythingdone.spatial.SpatialMattingDownloadWorker.STATE_SELF_TEST ->
                getString(R.string.spatial_download_self_test)
            com.ywwynm.everythingdone.spatial.SpatialMattingDownloadWorker
                .STATE_RUNTIME_DOWNLOADING,
            com.ywwynm.everythingdone.spatial.SpatialMattingDownloadWorker
                .STATE_RUNTIME_VERIFYING,
            com.ywwynm.everythingdone.spatial.SpatialMattingDownloadWorker
                .STATE_RUNTIME_INSTALLING ->
                getString(R.string.spatial_runtime_installing)
            else -> {
                val percent = if (total <= 0L) {
                    0
                } else {
                    ((downloaded * 100L) / total).toInt()
                }
                getString(R.string.spatial_download_progress, percent)
            }
        }
    }

    private fun bindSegmentationModel() {
        val model = SpatialSegmentationModel.RF_DETR_SEG_NANO
        findViewById<RadioButton>(R.id.rb_rfdetr_seg).setOnClickListener {
            if (SpatialSegmentationModelStore.isInstalled(this, model) &&
                SpatialSegmentationModelStore.isDeviceEligible(this, model)
            ) {
                SpatialPreferences.setSelectedSegmentationModel(this, model)
                refreshAll()
            } else {
                onSegmentationAction(model)
            }
        }
        findViewById<View>(R.id.model_rfdetr_seg).setOnClickListener {
            findViewById<RadioButton>(R.id.rb_rfdetr_seg).performClick()
        }
        findViewById<View>(R.id.btn_rfdetr_seg).setOnClickListener {
            onSegmentationAction(model)
        }
        findViewById<View>(R.id.iv_rfdetr_seg_delete).setOnClickListener {
            onSegmentationAction(model)
        }
        WorkManager.getInstance(applicationContext)
            .getWorkInfosForUniqueWorkLiveData(
                SpatialSegmentationDownloadCoordinator.uniqueWorkName(model)
            )
            .observe(this) { infos ->
                segmentationWorkInfo = currentWorkInfo(infos)
                refreshSegmentation()
                refreshRuntime()
            }
    }

    private fun onSegmentationAction(model: SpatialSegmentationModel) {
        val info = segmentationWorkInfo
        if (info?.let(::isActive) == true) {
            SpatialSegmentationDownloadCoordinator.cancel(this, model)
            refreshSegmentation()
            return
        }
        if (SpatialSegmentationModelStore.isInstalled(this, model)) {
            showAppAlert(
                title = getString(R.string.spatial_delete_model_title, model.displayName),
                content = getString(R.string.spatial_delete_model_message),
                confirmText = getString(R.string.act_delete)
            ) {
                background.execute {
                    SpatialSegmentationModelStore.delete(applicationContext, model)
                    if (SpatialPreferences.selectedSegmentationModel(applicationContext) == model) {
                        SpatialPreferences.setSelectedSegmentationModel(applicationContext, null)
                    }
                    runOnUiThread { refreshAll() }
                }
            }
            return
        }
        if (!SpatialSegmentationModelStore.isDeviceEligible(this, model)) {
            Toast.makeText(
                this,
                getString(
                    R.string.spatial_model_memory_required,
                    model.minimumTotalRamMb / 1024
                ),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val entry = segmentationEntry
        if (entry == null) {
            Toast.makeText(
                this,
                runtimeCatalogError ?: getString(R.string.spatial_catalog_loading),
                Toast.LENGTH_LONG
            ).show()
            refreshRuntimeCatalog()
            return
        }
        if (!entry.enabled) {
            Toast.makeText(
                this,
                entry.disabledReason ?: getString(R.string.spatial_model_not_ready),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val metered = connectivity.isActiveNetworkMetered
        val dataSaver = connectivity.restrictBackgroundStatus ==
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        if (metered || dataSaver) {
            val totalBytes = model.sizeBytes +
                if (SpatialRuntimeStore.isInstalled(this)) {
                    0L
                } else {
                    runtimeEntry?.sizeBytes ?: 0L
                }
            showAppAlert(
                title = getString(R.string.spatial_metered_title),
                content = getString(
                    R.string.spatial_metered_message,
                    model.displayName,
                    Formatter.formatFileSize(this, totalBytes)
                ),
                confirmText = getString(R.string.spatial_download)
            ) {
                requestNotificationAndEnqueueSegmentation(model, allowMetered = true)
            }
        } else {
            requestNotificationAndEnqueueSegmentation(model, allowMetered = false)
        }
    }

    private fun refreshSegmentation() {
        val model = SpatialSegmentationModel.RF_DETR_SEG_NANO
        val installed = SpatialSegmentationModelStore.isInstalled(this, model)
        val info = segmentationWorkInfo
        val active = info?.let(::isActive) == true
        val size = Formatter.formatFileSize(this, model.sizeBytes)
        findViewById<RadioButton>(R.id.rb_rfdetr_seg).apply {
            isChecked = installed &&
                SpatialPreferences.selectedSegmentationModel(this@SpatialPhotoSettingsActivity) ==
                model
            isEnabled = installed && SpatialSegmentationModelStore.isDeviceEligible(
                this@SpatialPhotoSettingsActivity,
                model
            )
        }
        findViewById<TextView>(R.id.tv_rfdetr_seg_status).text = when {
            active -> segmentationWorkStatus(info)
            installed && SpatialPreferences.selectedSegmentationModel(this) == model ->
                getString(R.string.spatial_segmentation_enabled, size)
            installed -> getString(R.string.spatial_segmentation_disabled, size)
            !SpatialSegmentationModelStore.isDeviceEligible(this, model) -> getString(
                R.string.spatial_model_memory_required,
                model.minimumTotalRamMb / 1024
            )
            info?.state == WorkInfo.State.FAILED -> failedStatusText(
                info.outputData.getString(SpatialSegmentationDownloadWorker.KEY_ERROR),
                info.outputData.getString(SpatialSegmentationDownloadWorker.KEY_ERROR_STAGE) ==
                    SpatialSegmentationDownloadWorker.ERROR_STAGE_SELF_TEST
            )
            else -> getString(R.string.spatial_model_not_downloaded, size)
        }
        val showDelete = installed && !active
        findViewById<View>(R.id.iv_rfdetr_seg_delete).visibility =
            if (showDelete) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btn_rfdetr_seg).apply {
            visibility = if (showDelete) View.GONE else View.VISIBLE
            isEnabled = active || SpatialSegmentationModelStore.isDeviceEligible(
                this@SpatialPhotoSettingsActivity,
                model
            )
            setText(if (active) R.string.cancel else R.string.spatial_download)
        }
    }

    private fun segmentationWorkStatus(info: WorkInfo): String {
        val state = info.progress.getString(SpatialSegmentationDownloadWorker.KEY_STATE)
        return when (state) {
            SpatialSegmentationDownloadWorker.STATE_VERIFYING ->
                getString(R.string.spatial_download_verifying)
            SpatialSegmentationDownloadWorker.STATE_SELF_TEST ->
                getString(R.string.spatial_download_self_test)
            SpatialSegmentationDownloadWorker.STATE_RUNTIME_VERIFYING ->
                getString(R.string.spatial_runtime_verifying)
            SpatialSegmentationDownloadWorker.STATE_RUNTIME_DOWNLOADING,
            SpatialSegmentationDownloadWorker.STATE_RUNTIME_INSTALLING ->
                getString(R.string.spatial_runtime_installing)
            else -> {
                val downloaded = info.progress.getLong(
                    SpatialSegmentationDownloadWorker.KEY_DOWNLOADED,
                    0L
                )
                val total = info.progress.getLong(
                    SpatialSegmentationDownloadWorker.KEY_TOTAL,
                    0L
                )
                val percent = if (total > 0L) ((downloaded * 100L) / total).toInt() else 0
                getString(R.string.spatial_download_progress, percent)
            }
        }
    }

    private fun bindBoundaryRefinementModel() {
        val model = SpatialBoundaryRefinementModel.EDGETAM
        findViewById<RadioButton>(R.id.rb_edgetam_refinement).setOnClickListener {
            Toast.makeText(this, R.string.spatial_legacy_component_unused, Toast.LENGTH_LONG)
                .show()
        }
        findViewById<View>(R.id.model_edgetam_refinement).setOnClickListener {
            Toast.makeText(this, R.string.spatial_legacy_component_unused, Toast.LENGTH_LONG)
                .show()
        }
        findViewById<View>(R.id.btn_edgetam_refinement).setOnClickListener {
            onBoundaryRefinementAction(model)
        }
        findViewById<View>(R.id.iv_edgetam_refinement_delete).setOnClickListener {
            onBoundaryRefinementAction(model)
        }
        WorkManager.getInstance(applicationContext)
            .getWorkInfosForUniqueWorkLiveData(
                SpatialBoundaryRefinementDownloadCoordinator.uniqueWorkName(model)
            )
            .observe(this) { infos ->
                boundaryRefinementWorkInfo = currentWorkInfo(infos)
                refreshBoundaryRefinement()
                refreshRuntime()
            }
    }

    private fun onBoundaryRefinementAction(model: SpatialBoundaryRefinementModel) {
        val info = boundaryRefinementWorkInfo
        if (info?.let(::isActive) == true) {
            SpatialBoundaryRefinementDownloadCoordinator.cancel(this, model)
            refreshBoundaryRefinement()
            return
        }
        if (SpatialBoundaryRefinementModelStore.isInstalled(this, model)) {
            showAppAlert(
                title = getString(R.string.spatial_delete_model_title, model.displayName),
                content = getString(R.string.spatial_delete_model_message),
                confirmText = getString(R.string.act_delete)
            ) {
                background.execute {
                    SpatialBoundaryRefinementModelStore.delete(applicationContext, model)
                    if (SpatialPreferences.selectedBoundaryRefinementModel(applicationContext) ==
                        model
                    ) {
                        SpatialPreferences.setSelectedBoundaryRefinementModel(
                            applicationContext,
                            null
                        )
                    }
                    runOnUiThread { refreshAll() }
                }
            }
            return
        }
        Toast.makeText(this, R.string.spatial_legacy_component_unused, Toast.LENGTH_LONG).show()
    }

    private fun refreshBoundaryRefinement() {
        val model = SpatialBoundaryRefinementModel.EDGETAM
        val installed = SpatialBoundaryRefinementModelStore.isInstalled(this, model)
        val info = boundaryRefinementWorkInfo
        val active = info?.let(::isActive) == true
        val size = Formatter.formatFileSize(this, model.archiveSizeBytes)
        findViewById<RadioButton>(R.id.rb_edgetam_refinement).apply {
            isChecked = false
            isEnabled = false
        }
        findViewById<TextView>(R.id.tv_edgetam_refinement_status).text = when {
            active -> boundaryRefinementWorkStatus(info)
            installed -> getString(R.string.spatial_legacy_component_installed, size)
            else -> getString(R.string.spatial_legacy_component_unused)
        }
        val showDelete = installed && !active
        findViewById<View>(R.id.iv_edgetam_refinement_delete).visibility =
            if (showDelete) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btn_edgetam_refinement).apply {
            visibility = if (active) View.VISIBLE else View.GONE
            isEnabled = active
            setText(R.string.cancel)
        }
    }

    private fun boundaryRefinementWorkStatus(info: WorkInfo): String {
        val state = info.progress.getString(SpatialBoundaryRefinementDownloadWorker.KEY_STATE)
        return when (state) {
            SpatialBoundaryRefinementDownloadWorker.STATE_VERIFYING ->
                getString(R.string.spatial_download_verifying)
            SpatialBoundaryRefinementDownloadWorker.STATE_SELF_TEST ->
                getString(R.string.spatial_download_self_test)
            SpatialBoundaryRefinementDownloadWorker.STATE_RUNTIME_VERIFYING ->
                getString(R.string.spatial_runtime_verifying)
            SpatialBoundaryRefinementDownloadWorker.STATE_RUNTIME_DOWNLOADING,
            SpatialBoundaryRefinementDownloadWorker.STATE_RUNTIME_INSTALLING ->
                getString(R.string.spatial_runtime_installing)
            else -> {
                val downloaded = info.progress.getLong(
                    SpatialBoundaryRefinementDownloadWorker.KEY_DOWNLOADED,
                    0L
                )
                val total = info.progress.getLong(
                    SpatialBoundaryRefinementDownloadWorker.KEY_TOTAL,
                    0L
                )
                val percent = if (total > 0L) ((downloaded * 100L) / total).toInt() else 0
                getString(R.string.spatial_download_progress, percent)
            }
        }
    }

    private fun bindInpaintingQuality() {
        findViewById<View>(R.id.row_inpainting_quality).setOnClickListener {
            showInpaintingQualityChooser()
        }
        refreshInpaintingQuality()
    }

    private fun showInpaintingQualityChooser() {
        val qualities = listOf(
            SpatialInpaintingQuality.STANDARD,
            SpatialInpaintingQuality.HIGH,
            SpatialInpaintingQuality.MAXIMUM
        )
        val cdf = ChooserDialogFragment()
        cdf.setAccentBackground(App.defaultAccentBackground)
        cdf.setTitle(getString(R.string.spatial_inpainting_resolution))
        cdf.setShouldShowMore(false)
        cdf.setItems(
            qualities.map { qualityLabel(it) as String? }.toMutableList()
        )
        val initialIndex = qualities.indexOf(SpatialPreferences.inpaintingQuality(this))
            .coerceAtLeast(0)
        cdf.setInitialIndex(initialIndex)
        cdf.setConfirmListener(View.OnClickListener {
            val picked = cdf.getPickedIndex()
            if (picked != initialIndex && picked in qualities.indices) {
                SpatialPreferences.setInpaintingQuality(this, qualities[picked])
                refreshInpaintingQuality()
            }
        })
        cdf.show(supportFragmentManager, ChooserDialogFragment.TAG)
    }

    private fun qualityLabel(quality: SpatialInpaintingQuality): String = getString(
        when (quality) {
            SpatialInpaintingQuality.STANDARD -> R.string.spatial_inpainting_resolution_512
            SpatialInpaintingQuality.HIGH -> R.string.spatial_inpainting_resolution_768
            SpatialInpaintingQuality.MAXIMUM -> R.string.spatial_inpainting_resolution_1024
        }
    )

    private fun onModelAction(model: SpatialDepthModel) {
        val info = workInfo[model]
        if (info?.let(::isActive) == true) {
            SpatialModelDownloadCoordinator.cancel(this, model)
            refreshModel(model)
            return
        }
        if (SpatialModelStore.isInstalled(this, model)) {
            showAppAlert(
                title = getString(R.string.spatial_delete_model_title, model.displayName),
                content = getString(R.string.spatial_delete_model_message),
                confirmText = getString(R.string.act_delete)
            ) {
                background.execute {
                    SpatialModelStore.delete(applicationContext, model)
                    val alternate = SpatialDepthModel.entries.firstOrNull {
                        it != model && SpatialModelStore.isInstalled(applicationContext, it)
                    }
                    if (alternate != null &&
                        SpatialPreferences.selectedModel(applicationContext) == model
                    ) {
                        SpatialPreferences.setSelectedModel(applicationContext, alternate)
                    } else if (alternate == null &&
                        SpatialPreferences.selectedModel(applicationContext) == model
                    ) {
                        SpatialPreferences.setSelectedModel(
                            applicationContext,
                            SpatialDepthModel.ZIPDEPTH
                        )
                    }
                    runOnUiThread { refreshAll() }
                }
            }
            return
        }

        if (!SpatialRuntimeStore.isInstalled(this) && runtimeEntry == null) {
            Toast.makeText(
                this,
                runtimeCatalogError ?: getString(R.string.spatial_catalog_loading),
                Toast.LENGTH_LONG
            ).show()
            refreshRuntimeCatalog()
            return
        }
        if (!SpatialRuntimeStore.isInstalled(this) && runtimeEntry?.enabled != true) {
            Toast.makeText(
                this,
                runtimeEntry?.disabledReason ?: getString(R.string.spatial_runtime_unavailable),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val metered = connectivity.isActiveNetworkMetered
        val dataSaver = connectivity.restrictBackgroundStatus ==
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        if (metered || dataSaver) {
            val totalBytes = model.sizeBytes +
                if (SpatialRuntimeStore.isInstalled(this)) 0L else runtimeEntry?.sizeBytes ?: 0L
            val size = Formatter.formatFileSize(this, totalBytes)
            showAppAlert(
                title = getString(R.string.spatial_metered_title),
                content = getString(R.string.spatial_metered_message, model.displayName, size),
                confirmText = getString(R.string.spatial_download)
            ) {
                requestNotificationAndEnqueue(model, allowMetered = true)
            }
        } else {
            requestNotificationAndEnqueue(model, allowMetered = false)
        }
    }

    private fun onRuntimeAction() {
        val info = runtimeWorkInfo
        if (info?.let(::isActive) == true) {
            SpatialRuntimeDownloadCoordinator.cancel(this)
            refreshRuntime()
            return
        }
        val modelRuntimeDownload = workInfo.entries.firstOrNull { (_, modelInfo) ->
            modelInfo != null && isActive(modelInfo) && isRuntimeStage(modelInfo)
        }
        if (modelRuntimeDownload != null) {
            SpatialModelDownloadCoordinator.cancel(this, modelRuntimeDownload.key)
            refreshRuntime()
            return
        }
        if (SpatialRuntimeStore.isInstalled(this)) {
            showAppAlert(
                title = getString(R.string.spatial_delete_runtime_title),
                content = getString(R.string.spatial_delete_runtime_message),
                confirmText = getString(R.string.act_delete)
            ) {
                background.execute {
                    SpatialRuntimeStore.delete(applicationContext)
                    runOnUiThread { refreshAll() }
                }
            }
            return
        }

        val entry = runtimeEntry
        if (entry == null) {
            Toast.makeText(
                this,
                runtimeCatalogError ?: getString(R.string.spatial_catalog_loading),
                Toast.LENGTH_LONG
            ).show()
            refreshRuntimeCatalog()
            return
        }
        if (!entry.enabled) {
            Toast.makeText(
                this,
                entry.disabledReason ?: getString(R.string.spatial_runtime_unavailable),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val metered = connectivity.isActiveNetworkMetered
        val dataSaver = connectivity.restrictBackgroundStatus ==
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        if (metered || dataSaver) {
            val size = Formatter.formatFileSize(this, entry.sizeBytes)
            showAppAlert(
                title = getString(R.string.spatial_metered_title),
                content = getString(
                    R.string.spatial_metered_message,
                    getString(R.string.spatial_runtime_title),
                    size
                ),
                confirmText = getString(R.string.spatial_download)
            ) {
                requestNotificationAndEnqueueRuntime(allowMetered = true)
            }
        } else {
            requestNotificationAndEnqueueRuntime(allowMetered = false)
        }
    }

    private fun onInpaintingAction(model: SpatialInpaintingModel) {
        val info = inpaintingWorkInfo[model]
        if (info?.let(::isActive) == true) {
            SpatialInpaintingDownloadCoordinator.cancel(this, model)
            refreshInpainting(model)
            return
        }
        if (SpatialInpaintingModelStore.isInstalled(this, model)) {
            showAppAlert(
                title = getString(R.string.spatial_delete_model_title, model.displayName),
                content = getString(R.string.spatial_delete_inpainting_message),
                confirmText = getString(R.string.act_delete)
            ) {
                background.execute {
                    SpatialInpaintingModelStore.delete(
                        applicationContext,
                        model
                    )
                    if (SpatialPreferences.selectedInpaintingModel(
                            applicationContext
                        ) == model
                    ) {
                        val alternate = SpatialInpaintingModel.entries
                            .firstOrNull {
                                it != model &&
                                    SpatialInpaintingModelStore.isInstalled(
                                        applicationContext,
                                        it
                                    )
                            }
                            ?: SpatialInpaintingModel.MIGAN_PLACES2_512_PIPELINE
                        SpatialPreferences.setSelectedInpaintingModel(
                            applicationContext,
                            alternate
                        )
                    }
                    runOnUiThread { refreshAll() }
                }
            }
            return
        }
        if (!SpatialInpaintingModelStore.isDeviceEligible(this, model)) {
            Toast.makeText(
                this,
                getString(
                    R.string.spatial_model_memory_required,
                    model.minimumTotalRamMb / 1024
                ),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val entry = inpaintingEntries[model]
        if (entry == null) {
            Toast.makeText(
                this,
                runtimeCatalogError ?: getString(R.string.spatial_catalog_loading),
                Toast.LENGTH_LONG
            ).show()
            refreshRuntimeCatalog()
            return
        }
        if (!entry.enabled) {
            Toast.makeText(
                this,
                entry.disabledReason ?: getString(R.string.spatial_model_not_ready),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val metered = connectivity.isActiveNetworkMetered
        val dataSaver = connectivity.restrictBackgroundStatus ==
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        if (metered || dataSaver) {
            val totalBytes = model.sizeBytes +
                if (SpatialRuntimeStore.isInstalled(this)) {
                    0L
                } else {
                    runtimeEntry?.sizeBytes ?: 0L
                }
            showAppAlert(
                title = getString(R.string.spatial_metered_title),
                content = getString(
                    R.string.spatial_metered_message,
                    model.displayName,
                    Formatter.formatFileSize(this, totalBytes)
                ),
                confirmText = getString(R.string.spatial_download)
            ) {
                requestNotificationAndEnqueueInpainting(
                    model,
                    allowMetered = true
                )
            }
        } else {
            requestNotificationAndEnqueueInpainting(
                model,
                allowMetered = false
            )
        }
    }

    private fun requestNotificationAndEnqueue(
        model: SpatialDepthModel,
        allowMetered: Boolean
    ) {
        requestNotificationThen {
            if (SpatialDepthModel.entries.none { SpatialModelStore.isInstalled(this, it) }) {
                SpatialPreferences.setSelectedModel(this, model)
            }
            SpatialModelDownloadCoordinator.enqueue(this, model, allowMetered)
            refreshModel(model)
        }
    }

    private fun requestNotificationAndEnqueueRuntime(allowMetered: Boolean) {
        requestNotificationThen {
            SpatialRuntimeDownloadCoordinator.enqueue(this, allowMetered)
            refreshRuntime()
        }
    }

    private fun requestNotificationAndEnqueueInpainting(
        model: SpatialInpaintingModel,
        allowMetered: Boolean
    ) {
        requestNotificationThen {
            if (SpatialInpaintingModel.entries.none {
                    SpatialInpaintingModelStore.isInstalled(this, it)
                }
            ) {
                SpatialPreferences.setSelectedInpaintingModel(this, model)
            }
            SpatialInpaintingDownloadCoordinator.enqueue(
                this,
                model,
                allowMetered
            )
            refreshInpainting(model)
        }
    }

    private fun requestNotificationAndEnqueueSegmentation(
        model: SpatialSegmentationModel,
        allowMetered: Boolean
    ) {
        requestNotificationThen {
            SpatialPreferences.setSelectedSegmentationModel(this, model)
            SpatialSegmentationDownloadCoordinator.enqueue(
                this,
                model,
                allowMetered
            )
            refreshSegmentation()
        }
    }

    private fun requestNotificationThen(enqueue: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            doWithPermissionChecked(
                object : SimplePermissionCallback(this) {
                    override fun onGranted() = enqueue()

                    override fun onDenied() {
                        Toast.makeText(
                            this@SpatialPhotoSettingsActivity,
                            R.string.spatial_notification_denied,
                            Toast.LENGTH_LONG
                        ).show()
                        enqueue()
                    }
                },
                REQUEST_NOTIFICATION_PERMISSION,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            enqueue()
        }
    }

    private fun refreshRuntimeCatalog() {
        if (runtimeCatalogLoading) return
        runtimeCatalogLoading = true
        runtimeCatalogError = null
        refreshRuntime()
        background.execute {
            val result = runCatching {
                val catalog = SpatialCatalogClient(applicationContext)
                    .fetchOrCached()
                    .catalog
                val runtime = catalog.runtimeForCurrentDevice()
                    ?: error(getString(R.string.spatial_runtime_unavailable))
                val inpainting = catalog.allInpaintingModels()
                    .mapNotNull { entry ->
                        entry.builtInModel()?.let { it to entry }
                    }
                    .toMap()
                val segmentation = catalog.segmentationModels.orEmpty()
                    .firstOrNull {
                        it.builtInModel() == SpatialSegmentationModel.RF_DETR_SEG_NANO
                    }
                val boundaryRefinement = catalog.boundaryRefinementModels.orEmpty()
                    .firstOrNull {
                        it.builtInModel() == SpatialBoundaryRefinementModel.EDGETAM
                    }
                CatalogSnapshot(runtime, inpainting, segmentation, boundaryRefinement)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                runtimeCatalogLoading = false
                runtimeEntry = result.getOrNull()?.runtime
                inpaintingEntries.clear()
                result.getOrNull()?.inpainting?.let(inpaintingEntries::putAll)
                segmentationEntry = result.getOrNull()?.segmentation
                boundaryRefinementEntry = result.getOrNull()?.boundaryRefinement
                runtimeCatalogError = result.exceptionOrNull()?.message
                    ?: if (runtimeEntry == null) {
                        getString(R.string.spatial_runtime_unavailable)
                    } else {
                        null
                    }
                refreshAll()
            }
        }
    }

    private fun refreshAll() {
        refreshRuntime()
        SpatialDepthModel.entries.forEach(::refreshModel)
        SpatialInpaintingModel.entries.forEach(::refreshInpainting)
        refreshInpaintingQuality()
        refreshMatting()
        refreshSegmentation()
        refreshBoundaryRefinement()
        refreshStorage()
    }

    private fun refreshRuntime() {
        val installed = SpatialRuntimeStore.isInstalled(this)
        val independentInfo = runtimeWorkInfo
        val modelInfo = activeModelRuntimeWork()
        val info = independentInfo?.takeIf(::isActive) ?: modelInfo
        val active = info?.let(::isActive) == true
        val entry = runtimeEntry
        val formattedSize = Formatter.formatFileSize(
            this,
            if (installed) SpatialRuntimeStore.totalBytes(this) else entry?.sizeBytes ?: 0L
        )
        findViewById<TextView>(R.id.tv_runtime_status).text = when {
            installed -> getString(R.string.spatial_runtime_installed, formattedSize)
            active && info === independentInfo -> runtimeWorkStatus(info, formattedSize)
            active -> modelWorkStatus(info, formattedSize)
            independentInfo?.state == WorkInfo.State.FAILED -> getString(
                R.string.spatial_model_failed,
                independentInfo.outputData.getString(SpatialRuntimeDownloadWorker.KEY_ERROR)
                    ?: getString(R.string.unknown_error)
            )
            runtimeCatalogLoading -> getString(R.string.spatial_catalog_loading)
            entry != null && !entry.enabled ->
                entry.disabledReason ?: getString(R.string.spatial_runtime_unavailable)
            entry != null -> getString(R.string.spatial_runtime_not_downloaded, formattedSize)
            runtimeCatalogError != null -> getString(
                R.string.spatial_model_failed,
                runtimeCatalogError
            )
            else -> getString(R.string.spatial_catalog_loading)
        }
        val showRuntimeDelete = installed && !active
        findViewById<View>(R.id.iv_runtime_delete).visibility =
            if (showRuntimeDelete) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btn_runtime).apply {
            visibility = if (showRuntimeDelete) View.GONE else View.VISIBLE
            isEnabled = active || entry?.enabled == true
            setText(if (active) R.string.cancel else R.string.spatial_download)
        }
    }

    private fun refreshModel(model: SpatialDepthModel) {
        val installed = SpatialModelStore.isInstalled(this, model)
        val eligible = SpatialModelStore.isDeviceEligible(this, model)
        val info = workInfo[model]
        val active = info?.let(::isActive) == true
        val anyInstalled = SpatialDepthModel.entries.any {
            SpatialModelStore.isInstalled(this, it)
        }
        val selected = if (anyInstalled) {
            installed && SpatialPreferences.selectedModel(this) == model
        } else {
            model == SpatialDepthModel.ZIPDEPTH
        }
        radio(model).isChecked = selected
        radio(model).isEnabled = installed && eligible

        val size = Formatter.formatFileSize(this, model.sizeBytes)
        status(model).text = when {
            installed -> getString(R.string.spatial_model_installed, size)
            !eligible -> getString(
                R.string.spatial_model_memory_required,
                model.minimumTotalRamMb / 1024
            )
            active -> modelWorkStatus(info, size)
            info?.state == WorkInfo.State.FAILED -> failedStatusText(
                info.outputData.getString(SpatialModelDownloadWorker.KEY_ERROR),
                info.outputData.getString(SpatialModelDownloadWorker.KEY_ERROR_STAGE) ==
                    SpatialModelDownloadWorker.ERROR_STAGE_SELF_TEST
            )
            else -> getString(R.string.spatial_model_not_downloaded, size)
        }
        val showDelete = installed && !active
        deleteIcon(model).visibility = if (showDelete) View.VISIBLE else View.GONE
        button(model).visibility = if (showDelete) View.GONE else View.VISIBLE
        button(model).isEnabled = eligible || active
        button(model).setText(if (active) R.string.cancel else R.string.spatial_download)
    }

    private fun refreshInpainting(model: SpatialInpaintingModel) {
        val installed = SpatialInpaintingModelStore.isInstalled(this, model)
        val eligible = SpatialInpaintingModelStore.isDeviceEligible(this, model)
        val info = inpaintingWorkInfo[model]
        val active = info?.let(::isActive) == true
        val entry = inpaintingEntries[model]
        val anyInstalled = SpatialInpaintingModel.entries.any {
            SpatialInpaintingModelStore.isInstalled(this, it)
        }
        inpaintingRadio(model).isChecked = if (anyInstalled) {
            installed && SpatialPreferences.selectedInpaintingModel(this) == model
        } else {
            model == SpatialInpaintingModel.MIGAN_PLACES2_512_PIPELINE
        }
        inpaintingRadio(model).isEnabled = installed && eligible
        val size = Formatter.formatFileSize(this, model.sizeBytes)
        inpaintingStatus(model).text = when {
            installed -> getString(R.string.spatial_model_installed, size)
            !eligible -> getString(
                R.string.spatial_model_memory_required,
                model.minimumTotalRamMb / 1024
            )
            active -> inpaintingWorkStatus(info, size)
            info?.state == WorkInfo.State.FAILED -> failedStatusText(
                info.outputData.getString(SpatialInpaintingDownloadWorker.KEY_ERROR),
                info.outputData.getString(SpatialInpaintingDownloadWorker.KEY_ERROR_STAGE) ==
                    SpatialInpaintingDownloadWorker.ERROR_STAGE_SELF_TEST
            )
            runtimeCatalogLoading -> getString(R.string.spatial_catalog_loading)
            entry == null -> getString(R.string.spatial_model_not_ready)
            !entry.enabled ->
                entry.disabledReason
                    ?: getString(R.string.spatial_model_not_ready)
            else -> getString(R.string.spatial_model_not_downloaded, size)
        }
        val showDelete = installed && !active
        inpaintingDeleteIcon(model).visibility = if (showDelete) View.VISIBLE else View.GONE
        inpaintingButton(model).apply {
            visibility = if (showDelete) View.GONE else View.VISIBLE
            isEnabled = eligible && (active || entry?.enabled == true)
            setText(if (active) R.string.cancel else R.string.spatial_download)
        }
    }

    private fun failedStatusText(message: String?, selfTest: Boolean): String {
        val detail = message ?: getString(R.string.unknown_error)
        return if (selfTest) {
            getString(R.string.spatial_model_self_test_failed, detail)
        } else {
            getString(R.string.spatial_model_failed, detail)
        }
    }

    private fun refreshInpaintingQuality() {
        // 工作分辨率只对 AOT-GAN 有意义，未选中它时整块隐藏。
        val aotSelected = SpatialPreferences.selectedInpaintingModel(this) ==
            SpatialInpaintingModel.AOTGAN_PLACES2_512 &&
            SpatialInpaintingModelStore.isInstalled(
                this,
                SpatialInpaintingModel.AOTGAN_PLACES2_512
            )
        findViewById<View>(R.id.ll_inpainting_quality).visibility =
            if (aotSelected) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.tv_inpainting_quality_value).text =
            qualityLabel(SpatialPreferences.inpaintingQuality(this))
    }

    private fun inpaintingWorkStatus(
        info: WorkInfo?,
        formattedSize: String
    ): String {
        if (info?.state == WorkInfo.State.ENQUEUED) {
            return getString(R.string.spatial_download_waiting, formattedSize)
        }
        return when (
            info?.progress?.getString(SpatialInpaintingDownloadWorker.KEY_STATE)
        ) {
            SpatialInpaintingDownloadWorker.STATE_RUNTIME_VERIFYING ->
                getString(R.string.spatial_runtime_verifying)
            SpatialInpaintingDownloadWorker.STATE_RUNTIME_INSTALLING ->
                getString(R.string.spatial_runtime_installing)
            SpatialInpaintingDownloadWorker.STATE_VERIFYING ->
                getString(R.string.spatial_download_verifying)
            SpatialInpaintingDownloadWorker.STATE_SELF_TEST ->
                getString(R.string.spatial_download_self_test)
            else -> {
                val downloaded = info?.progress?.getLong(
                    SpatialInpaintingDownloadWorker.KEY_DOWNLOADED,
                    0L
                ) ?: 0L
                val total = info?.progress?.getLong(
                    SpatialInpaintingDownloadWorker.KEY_TOTAL,
                    0L
                ) ?: 0L
                val percent = if (total > 0L) {
                    ((downloaded * 100L) / total).toInt()
                } else {
                    0
                }
                getString(R.string.spatial_download_progress, percent)
            }
        }
    }

    private fun modelWorkStatus(info: WorkInfo?, formattedSize: String): String {
        if (info?.state == WorkInfo.State.ENQUEUED) {
            return getString(R.string.spatial_download_waiting, formattedSize)
        }
        val state = info?.progress?.getString(SpatialModelDownloadWorker.KEY_STATE)
        if (state == SpatialModelDownloadWorker.STATE_RUNTIME_VERIFYING) {
            return getString(R.string.spatial_runtime_verifying)
        }
        if (state == SpatialModelDownloadWorker.STATE_RUNTIME_INSTALLING) {
            return getString(R.string.spatial_runtime_installing)
        }
        if (state == SpatialModelDownloadWorker.STATE_VERIFYING) {
            return getString(R.string.spatial_download_verifying)
        }
        if (state == SpatialModelDownloadWorker.STATE_SELF_TEST) {
            return getString(R.string.spatial_download_self_test)
        }
        val downloaded = info?.progress?.getLong(
            SpatialModelDownloadWorker.KEY_DOWNLOADED,
            0L
        ) ?: 0L
        val total = info?.progress?.getLong(
            SpatialModelDownloadWorker.KEY_TOTAL,
            0L
        ) ?: 0L
        val percent = if (total > 0L) ((downloaded * 100L) / total).toInt() else 0
        return getString(R.string.spatial_download_progress, percent)
    }

    private fun runtimeWorkStatus(info: WorkInfo?, formattedSize: String): String {
        if (info?.state == WorkInfo.State.ENQUEUED) {
            return getString(R.string.spatial_download_waiting, formattedSize)
        }
        return when (
            info?.progress?.getString(SpatialRuntimeDownloadWorker.KEY_STATE)
        ) {
            SpatialRuntimeDownloadWorker.STATE_CATALOG ->
                getString(R.string.spatial_catalog_loading)
            SpatialRuntimeDownloadWorker.STATE_VERIFYING ->
                getString(R.string.spatial_runtime_verifying)
            SpatialRuntimeDownloadWorker.STATE_INSTALLING ->
                getString(R.string.spatial_runtime_installing)
            else -> {
                val downloaded = info?.progress?.getLong(
                    SpatialRuntimeDownloadWorker.KEY_DOWNLOADED,
                    0L
                ) ?: 0L
                val total = info?.progress?.getLong(
                    SpatialRuntimeDownloadWorker.KEY_TOTAL,
                    0L
                ) ?: 0L
                val percent = if (total > 0L) ((downloaded * 100L) / total).toInt() else 0
                getString(R.string.spatial_download_progress, percent)
            }
        }
    }

    private fun activeModelRuntimeWork(): WorkInfo? =
        (
            workInfo.values.filterNotNull() +
                inpaintingWorkInfo.values.filterNotNull() +
                listOfNotNull(segmentationWorkInfo, boundaryRefinementWorkInfo)
            )
            .firstOrNull { info ->
            isActive(info) && isRuntimeStage(info)
        }

    private fun isRuntimeStage(info: WorkInfo): Boolean =
        when (info.progress.getString(SpatialModelDownloadWorker.KEY_STATE)) {
            SpatialModelDownloadWorker.STATE_RUNTIME_DOWNLOADING,
            SpatialModelDownloadWorker.STATE_RUNTIME_VERIFYING,
            SpatialModelDownloadWorker.STATE_RUNTIME_INSTALLING -> true
            else -> false
        }

    private fun currentWorkInfo(infos: List<WorkInfo>): WorkInfo? =
        infos.firstOrNull(::isActive) ?: infos.maxByOrNull { it.runAttemptCount }

    private fun isActive(info: WorkInfo): Boolean =
        info.state == WorkInfo.State.RUNNING ||
            info.state == WorkInfo.State.ENQUEUED ||
            info.state == WorkInfo.State.BLOCKED

    private fun refreshStorage() {
        background.execute {
            val derivativeBytes = SpatialDerivativeStore(applicationContext).totalBytes()
            val runtimeBytes = SpatialRuntimeStore.totalBytes(applicationContext)
            val analysisModelBytes = SpatialDepthModel.entries.sumOf {
                if (SpatialModelStore.isInstalled(applicationContext, it)) it.sizeBytes else 0L
            } + com.ywwynm.everythingdone.spatial.SpatialMattingModelStore
                .totalBytes(applicationContext) +
                SpatialSegmentationModelStore.totalBytes(applicationContext) +
                SpatialBoundaryRefinementModelStore.totalBytes(applicationContext)
            val inpaintingBytes =
                SpatialInpaintingModelStore.totalBytes(applicationContext)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    storageText?.text = getString(
                        R.string.spatial_storage_summary_v2,
                        Formatter.formatFileSize(this, runtimeBytes),
                        Formatter.formatFileSize(this, analysisModelBytes),
                        Formatter.formatFileSize(this, inpaintingBytes),
                        Formatter.formatFileSize(this, derivativeBytes)
                    )
                }
            }
        }
    }

    private fun row(model: SpatialDepthModel): View = findViewById(
        when (model) {
            SpatialDepthModel.ZIPDEPTH -> R.id.model_zipdepth
            SpatialDepthModel.DEPTH_ANYTHING_V2_SMALL -> R.id.model_dav2
            SpatialDepthModel.DEPTH_ANYTHING_3_SMALL -> R.id.model_da3
            SpatialDepthModel.MOGE_2_VITS_NORMAL -> R.id.model_moge2
        }
    )

    private fun radio(model: SpatialDepthModel): RadioButton = findViewById(
        when (model) {
            SpatialDepthModel.ZIPDEPTH -> R.id.rb_zipdepth
            SpatialDepthModel.DEPTH_ANYTHING_V2_SMALL -> R.id.rb_dav2
            SpatialDepthModel.DEPTH_ANYTHING_3_SMALL -> R.id.rb_da3
            SpatialDepthModel.MOGE_2_VITS_NORMAL -> R.id.rb_moge2
        }
    )

    private fun status(model: SpatialDepthModel): TextView = findViewById(
        when (model) {
            SpatialDepthModel.ZIPDEPTH -> R.id.tv_zipdepth_status
            SpatialDepthModel.DEPTH_ANYTHING_V2_SMALL -> R.id.tv_dav2_status
            SpatialDepthModel.DEPTH_ANYTHING_3_SMALL -> R.id.tv_da3_status
            SpatialDepthModel.MOGE_2_VITS_NORMAL -> R.id.tv_moge2_status
        }
    )

    private fun button(model: SpatialDepthModel): Button = findViewById(
        when (model) {
            SpatialDepthModel.ZIPDEPTH -> R.id.btn_zipdepth
            SpatialDepthModel.DEPTH_ANYTHING_V2_SMALL -> R.id.btn_dav2
            SpatialDepthModel.DEPTH_ANYTHING_3_SMALL -> R.id.btn_da3
            SpatialDepthModel.MOGE_2_VITS_NORMAL -> R.id.btn_moge2
        }
    )

    private fun deleteIcon(model: SpatialDepthModel): View = findViewById(
        when (model) {
            SpatialDepthModel.ZIPDEPTH -> R.id.iv_zipdepth_delete
            SpatialDepthModel.DEPTH_ANYTHING_V2_SMALL -> R.id.iv_dav2_delete
            SpatialDepthModel.DEPTH_ANYTHING_3_SMALL -> R.id.iv_da3_delete
            SpatialDepthModel.MOGE_2_VITS_NORMAL -> R.id.iv_moge2_delete
        }
    )

    // 三个补全模型各占一行；这里必须用穷举 when，加第四个模型时编译器会直接报错，
    // 而原来的两路 if/else 会把新模型静默映射到 AOT-GAN 那一行。
    private fun inpaintingDeleteIcon(model: SpatialInpaintingModel): View = findViewById(
        when (model) {
            SpatialInpaintingModel.MIGAN_PLACES2_512_PIPELINE -> R.id.iv_migan_delete
            SpatialInpaintingModel.AOTGAN_PLACES2_512 -> R.id.iv_aotgan_delete
            SpatialInpaintingModel.BIG_LAMA_PLACES2_512 -> R.id.iv_big_lama_delete
        }
    )

    private fun inpaintingRow(model: SpatialInpaintingModel): View = findViewById(
        when (model) {
            SpatialInpaintingModel.MIGAN_PLACES2_512_PIPELINE -> R.id.model_migan
            SpatialInpaintingModel.AOTGAN_PLACES2_512 -> R.id.model_aotgan
            SpatialInpaintingModel.BIG_LAMA_PLACES2_512 -> R.id.model_big_lama
        }
    )

    private fun inpaintingRadio(model: SpatialInpaintingModel): RadioButton = findViewById(
        when (model) {
            SpatialInpaintingModel.MIGAN_PLACES2_512_PIPELINE -> R.id.rb_migan
            SpatialInpaintingModel.AOTGAN_PLACES2_512 -> R.id.rb_aotgan
            SpatialInpaintingModel.BIG_LAMA_PLACES2_512 -> R.id.rb_big_lama
        }
    )

    private fun inpaintingStatus(model: SpatialInpaintingModel): TextView = findViewById(
        when (model) {
            SpatialInpaintingModel.MIGAN_PLACES2_512_PIPELINE -> R.id.tv_migan_status
            SpatialInpaintingModel.AOTGAN_PLACES2_512 -> R.id.tv_aotgan_status
            SpatialInpaintingModel.BIG_LAMA_PLACES2_512 -> R.id.tv_big_lama_status
        }
    )

    private fun inpaintingButton(model: SpatialInpaintingModel): Button = findViewById(
        when (model) {
            SpatialInpaintingModel.MIGAN_PLACES2_512_PIPELINE -> R.id.btn_migan
            SpatialInpaintingModel.AOTGAN_PLACES2_512 -> R.id.btn_aotgan
            SpatialInpaintingModel.BIG_LAMA_PLACES2_512 -> R.id.btn_big_lama
        }
    )

    companion object {
        private const val REQUEST_NOTIFICATION_PERMISSION = 9201
    }
}
