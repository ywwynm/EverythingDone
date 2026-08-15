package com.ywwynm.everythingdone.activities

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import com.ywwynm.everythingdone.spatial.SpatialDepthDetail
import com.ywwynm.everythingdone.spatial.SpatialDepthModel
import com.ywwynm.everythingdone.spatial.SpatialDepthOutputContract
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
import com.ywwynm.everythingdone.spatial.SpatialQnnPrecompiledDownloadCoordinator
import com.ywwynm.everythingdone.spatial.SpatialQnnPrecompiledDownloadWorker
import com.ywwynm.everythingdone.spatial.SpatialQnnPrecompiledStore
import com.ywwynm.everythingdone.spatial.SpatialQnnRuntimeDownloadCoordinator
import com.ywwynm.everythingdone.spatial.SpatialQnnRuntimeDownloadWorker
import com.ywwynm.everythingdone.spatial.SpatialQnnPrecompiledCatalogEntry
import com.ywwynm.everythingdone.spatial.SpatialQnnRuntimeCatalogEntry
import com.ywwynm.everythingdone.spatial.SpatialQnnSupport
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
    private var precompiledWorkInfo: WorkInfo? = null

    /**
     * NPU 运行组件**自己的**任务状态。绝不能复用 [runtimeWorkInfo]——那是 CPU 版那一行的，
     * 共用会让 NPU 的下载进度显示在「推理运行环境」行里（2026-08-14 用户两次指出）。
     */
    private var qnnRuntimeWorkInfo: WorkInfo? = null
    private var boundaryRefinementWorkInfo: WorkInfo? = null
    private val inpaintingWorkInfo =
        mutableMapOf<SpatialInpaintingModel, WorkInfo?>()
    private var runtimeEntry: SpatialRuntimeCatalogEntry? = null
    private var qnnRuntimeEntry: SpatialQnnRuntimeCatalogEntry? = null
    private var qnnPrecompiledEntry: SpatialQnnPrecompiledCatalogEntry? = null
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
        val boundaryRefinement: SpatialBoundaryRefinementCatalogEntry?,
        val qnnRuntime: SpatialQnnRuntimeCatalogEntry?,
        val qnnPrecompiled: SpatialQnnPrecompiledCatalogEntry?
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

    /**
     * 遍历整棵视图树，把 `btn_*`（下载/取消）与 `iv_*_delete`（删除）两类图标交给调用方。
     * 用命名而不是 id 列表，是因为手写列表在这个界面上已经漏过五次。
     */
    private fun forEachIconView(action: (android.widget.ImageView, String) -> Unit) {
        fun walk(view: View) {
            if (view is android.widget.ImageView && view.id != View.NO_ID) {
                val name = runCatching { resources.getResourceEntryName(view.id) }.getOrNull()
                if (name != null) action(view, name)
            }
            if (view is android.view.ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(findViewById(R.id.sv_spatial_settings))
    }

    /** 整行可点区域：`model_*` / `row_*` / `runtime_component`。 */
    private fun forEachRowView(action: (View) -> Unit) {
        fun walk(view: View) {
            if (view.id != View.NO_ID) {
                val name = runCatching { resources.getResourceEntryName(view.id) }.getOrNull()
                if (name != null &&
                    (name.startsWith("model_") || name.startsWith("row_") ||
                        name == "runtime_component")
                ) {
                    action(view)
                }
            }
            if (view is android.view.ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(findViewById(R.id.sv_spatial_settings))
    }

    private fun applyControlAccents() {
        val bg = App.defaultAccentBackground
        // **不再手写任何 id 列表。** 同一个错误犯了五次：手写列表先漏 MoGe-2 两行的删除
        // 图标、又漏 Big-LaMa 的，ripple 列表漏了同样几个，加了 NPU 三行之后又漏了
        // btn_qnn / btn_big_lama_npu / btn_rfdetr_npu——症状都是"控件在、点得到、
        // 但没图标或没按压反馈"。改为**遍历视图树按 id 命名套用**，新增控件只要沿用
        // `btn_*` / `iv_*_delete` 的命名就自动生效，结构上不可能再漏。
        val deleteTint = ContextCompat.getColor(this, R.color.app_chrome_control_unchecked)
        forEachIconView { view, name ->
            when {
                name.endsWith("_delete") -> {
                    view.setImageDrawable(
                        DisplayUtil.opaqueTintDrawable(
                            this,
                            ContextCompat.getDrawable(this, R.drawable.vec_ic_delete),
                            deleteTint
                        )
                    )
                    view.background = GradientRippleDrawable(bg, shapeOval = true)
                }
                name.startsWith("btn_") -> {
                    view.background = GradientRippleDrawable(bg, shapeOval = true)
                }
            }
        }
        // 清除行是整行动作：文字渐变 + 整行矩形渐变 ripple，左缘与其它行对齐。
        findViewById<TextView>(R.id.btn_clear_spatial_derivatives).let { clear ->
            BackgroundUtil.applyTextBackground(clear, bg)
            GradientRippleDrawable.applyAccentRowRipple(clear, bg, bg.representativeColor())
        }
        // 可点击整行同样按命名套用：`model_*` / `row_*` / `runtime_component`。
        forEachRowView { row ->
            GradientRippleDrawable.applyAccentRowRipple(row, bg, bg.representativeColor())
        }
        GradientRippleDrawable.applyAccentRowRipple(
            findViewById(R.id.rl_spatial_tilt_as_bt), bg, bg.representativeColor()
        )
        // 同样从枚举推深度模型那几个——手写列表此前已漏过六次（最近一次漏
        // rb_big_lama / NPU 两行）。两种状态都走完整强调渐变（uncheckedGradient），
        // 与设置首页的勾选框同一套观感：未选中不再是中性灰描边（2026-08-15 用户要求）。
        (SpatialDepthModel.entries.map { radioId(it) } + listOf(
            R.id.rb_migan, R.id.rb_aotgan, R.id.rb_big_lama, R.id.rb_big_lama_npu,
            R.id.rb_edgetam_refinement
        )).forEach { id ->
            val radio = findViewById<RadioButton>(id)
            BackgroundUtil.applyRadioAccent(
                radio, bg,
                footprintDp = BackgroundUtil.CHECKBOX_LABEL_ROW_FOOTPRINT_DP,
                uncheckedGradient = true
            )
            GradientRippleDrawable.applyCheckboxRipple(radio, bg)
        }
        listOf(R.id.cb_rfdetr_seg, R.id.cb_rfdetr_npu, R.id.cb_modnet, R.id.cb_qnn)
            .forEach { id ->
                val checkbox = findViewById<android.widget.CheckBox>(id)
                BackgroundUtil.applyCheckboxAccent(
                    checkbox, bg,
                    footprintDp = BackgroundUtil.CHECKBOX_LABEL_ROW_FOOTPRINT_DP,
                    uncheckedGradient = true
                )
                GradientRippleDrawable.applyCheckboxRipple(checkbox, bg)
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
            // 整行点击只承担"发起下载"这一个主动作。已安装时它什么都不做——删除必须点
            // 右侧的删除图标，此前整行转发到删除，随手一点就弹出删除确认；下载中也什么
            // 都不做——取消必须点那个明确的取消按钮，防误触（2026-08-15 统一交互）。
            val active = runtimeWorkInfo?.let(::isActive) == true ||
                activeModelRuntimeWork() != null
            if (!active && !SpatialRuntimeStore.isVariantInstalled(this, qnn = false)) {
                onRuntimeAction()
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
                // 整页刷新：基础组件装上/删掉决定其余每一行的置灰（gate），只刷自己
                // 这一行的结果是下载完成后 runtime 行恢复了、其余行还灰着
                //（2026-08-15 真机验证时发现）。
                refreshAll()
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
        // 行与 RadioButton 共用同一个监听器，**不要用 `row -> radio.performClick()`**：
        // RadioButton 是 CompoundButton，performClick() 会先 toggle 再回调，而且它
        // **不检查 isEnabled**——于是给 RadioButton 设的 `isEnabled = installed` 被整个
        // 绕过，点未安装的模型照样会勾上（2026-08-14 用户实测 MoGe-2 Base）。
        val select = View.OnClickListener {
            val installed = SpatialModelStore.isInstalled(this, model)
            val active = workInfo[model]?.let(::isActive) == true
            when {
                installed && SpatialModelStore.isDeviceEligible(this, model) -> {
                    // 必选类型（RadioButton）：点击只会选中，点已选中的不取消——深度模型
                    // 是生成的硬前提，不允许出现"有已装模型却一个都没选"的状态。
                    SpatialPreferences.setSelectedModel(this, model)
                    refreshAll()
                }
                // 未安装时整行点击即发起下载，与右侧下载按钮同义（2026-08-15 统一交互）
                !installed && !active -> onModelAction(model)
                // 下载中整行不响应：取消只能点右侧那个明确的取消按钮，防误触
                else -> refreshAll()
            }
        }
        // RadioButton 只作显示：不给它挂监听器，并关掉 clickable，让点击穿透到整行。
        // 否则直接点它时 CompoundButton 会先 toggle 自己，再被 refreshAll() 刷回去，
        // 出现一帧闪烁——而且 setOnClickListener 本身就会把 clickable 设回 true。
        radio(model).isClickable = false
        row(model).setOnClickListener(select)
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
                // **整页刷新，不要只刷这一行**：行与行之间是有依赖的（NPU 版那一行的
                // 状态取决于 CPU 版装没装），窄刷新的结果是 CPU 版下完了、NPU 版那行
                // 还写着"需要先安装 CPU 版"，而且两行同时显示未选中
                // （2026-08-14 用户实测）。整页刷新只是几十次 findViewById。
                refreshAll()
            }
    }

    private fun bindInpaintingModel(model: SpatialInpaintingModel) {
        // 同 bindModel：共用监听器而不是 performClick()，且拒绝时也要刷新
        val select = View.OnClickListener {
            val installed = SpatialInpaintingModelStore.isInstalled(this, model)
            val active = inpaintingWorkInfo[model]?.let(::isActive) == true
            when {
                installed && SpatialInpaintingModelStore.isDeviceEligible(this, model) -> {
                    SpatialPreferences.setSelectedInpaintingModel(this, model)
                    // 选任何 CPU 版都要退掉 Big-LaMa（NPU 版）的选中位——四行是同一个
                    // 单选组，组内换选必须让旧选项熄灭。**只清 Big-LaMa 的位**：此前
                    // 清的是 model 自己的位，而 MI-GAN／AOT-GAN 的这个开关是"透明加速"
                    // 位（默认开），清掉等于静默关闭它们的 NPU 加速（2026-08-15 发现）。
                    SpatialPreferences.setQnnEnabledFor(
                        this,
                        SpatialInpaintingModel.BIG_LAMA_PLACES2_512.stableId,
                        false
                    )
                    refreshAll()
                }
                !installed && !active -> onInpaintingAction(model)
                else -> refreshAll()
            }
        }
        inpaintingRadio(model).isClickable = false  // 同 bindModel：点击交给整行
        inpaintingRow(model).setOnClickListener(select)
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
                refreshAll()  // 同上：NPU 版那一行依赖 CPU 版的安装状态
            }
    }

    private fun bindMattingModel() {
        val model = com.ywwynm.everythingdone.spatial.SpatialMattingModel.MODNET_PHOTOGRAPHIC
        // 整行是唯一的点击面：已安装时切换启用；未安装时发起下载；下载中不响应
        //（取消只能点右侧按钮）。此前整行点击直接进 onMattingAction，已安装时
        // 随手一点就弹删除确认（2026-08-15 统一交互）。
        val toggle = View.OnClickListener {
            val installed = com.ywwynm.everythingdone.spatial.SpatialMattingModelStore
                .isInstalled(this, model)
            val active = mattingWorkInfo?.let(::isActive) == true
            when {
                installed -> {
                    SpatialPreferences.setMattingEnabled(
                        this, !SpatialPreferences.mattingEnabled(this)
                    )
                    refreshAll()
                }
                !active -> onMattingAction(model)
                else -> refreshAll()
            }
        }
        findViewById<View>(R.id.model_modnet).setOnClickListener(toggle)
        // 同各模型行：勾选框只作显示，点击穿透到整行
        findViewById<android.widget.CheckBox>(R.id.cb_modnet).isClickable = false
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
                content = getString(R.string.spatial_delete_matting_message),
                confirmText = getString(R.string.act_delete)
            ) {
                // 确认之后才动状态：此前在弹框**前**就把"启用"关了，
                // 取消对话框也会把勾误关掉。
                SpatialPreferences.setMattingEnabled(this, false)
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
        // 与其它下载项同一套流程：计费网络下先确认再下。此前这一条不问就用流量。
        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val metered = connectivity.isActiveNetworkMetered
        val dataSaver = connectivity.restrictBackgroundStatus ==
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        val enqueueMatting = { allowMetered: Boolean ->
            requestNotificationThen {
                com.ywwynm.everythingdone.spatial.SpatialMattingDownloadCoordinator.enqueue(
                    this,
                    model,
                    allowMetered
                )
                refreshMatting()
            }
        }
        if (metered || dataSaver) {
            showAppAlert(
                title = getString(R.string.spatial_metered_title),
                content = getString(
                    R.string.spatial_metered_message,
                    model.displayName,
                    Formatter.formatFileSize(this, model.sizeBytes)
                ),
                confirmText = getString(R.string.spatial_download)
            ) {
                enqueueMatting(true)
            }
        } else {
            enqueueMatting(false)
        }
    }

    private fun refreshMatting() {
        val model = com.ywwynm.everythingdone.spatial.SpatialMattingModel.MODNET_PHOTOGRAPHIC
        val installed =
            com.ywwynm.everythingdone.spatial.SpatialMattingModelStore.isInstalled(this, model)
        val eligible =
            com.ywwynm.everythingdone.spatial.SpatialMattingModelStore.isDeviceEligible(this, model)
        val info = mattingWorkInfo
        val active = info?.let(::isActive) == true
        val gate = baseRuntimeReady()
        val size = Formatter.formatFileSize(this, model.sizeBytes)
        val mattingOn = SpatialPreferences.mattingEnabled(this)
        applyRowGate(findViewById(R.id.model_modnet), gate)
        findViewById<android.widget.CheckBox>(R.id.cb_modnet).apply {
            isChecked = installed && mattingOn
            isEnabled = gate && eligible
        }
        // 未下载不显示勾选框，装好才谈得上启用（2026-08-15 用户要求，全部模型行统一）
        applyControlVisibility(findViewById(R.id.cb_modnet), installed)
        findViewById<TextView>(R.id.tv_modnet_status).text = when {
            // 与其它模型行同一套措辞："已启用 / 已下载安装"（2026-08-15 统一）
            installed && mattingOn -> getString(R.string.spatial_model_enabled, size)
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
        findViewById<android.widget.ImageView>(R.id.btn_modnet).apply {
            visibility = if (showDelete) View.GONE else View.VISIBLE
            applyActionIcon(this, active, active || (gate && eligible))
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
        // 人物连续性在生成管线中是可选组件，所以是 CheckBox：已选中再点一次即取消，
        // 允许一个都不勾。**取消判断必须把 NPU 版排除在外**：NPU 版选中时
        // selectedSegmentationModel 同样等于本模型，此前点 CPU 行被误判成"再点自己"
        // 而清空整个选择，表现为从 NPU 版切到 CPU 版时两行全灭（2026-08-15 用户实测）。
        val select = View.OnClickListener {
            val installed = SpatialSegmentationModelStore.isInstalled(this, model)
            val active = segmentationWorkInfo?.let(::isActive) == true
            when {
                installed && SpatialSegmentationModelStore.isDeviceEligible(this, model) -> {
                    val cpuChosen =
                        SpatialPreferences.selectedSegmentationModel(this) == model &&
                            !rfdetrNpuChosen()
                    SpatialPreferences.setSelectedSegmentationModel(
                        this, if (cpuChosen) null else model
                    )
                    SpatialPreferences.setQnnEnabledFor(this, model.stableId, false)
                    refreshAll()
                }
                !installed && !active -> onSegmentationAction(model)
                else -> refreshAll()
            }
        }
        findViewById<android.widget.CheckBox>(R.id.cb_rfdetr_seg).isClickable = false
        findViewById<View>(R.id.model_rfdetr_seg).setOnClickListener(select)
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
                refreshAll()  // 同上：RF-DETR（NPU 版）那一行依赖基础模型的安装状态
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
                content = getString(R.string.spatial_delete_segmentation_message),
                confirmText = getString(R.string.act_delete)
            ) {
                background.execute {
                    SpatialSegmentationModelStore.delete(applicationContext, model)
                    if (SpatialPreferences.selectedSegmentationModel(applicationContext) == model) {
                        SpatialPreferences.setSelectedSegmentationModel(applicationContext, null)
                    }
                    // NPU 版的选中位一并清掉，否则重装模型后 NPU 版会自动恢复选中
                    SpatialPreferences.setQnnEnabledFor(
                        applicationContext, model.stableId, false
                    )
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
                if (SpatialRuntimeStore.isVariantInstalled(this, qnn = false)) {
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
        val eligible = SpatialSegmentationModelStore.isDeviceEligible(this, model)
        val info = segmentationWorkInfo
        val active = info?.let(::isActive) == true
        val gate = baseRuntimeReady()
        val size = Formatter.formatFileSize(this, model.sizeBytes)
        // 选中态与状态文案用同一个判定：此前文案不排除 NPU 版，NPU 版选中时
        // CPU 行的勾灭了、文字却写着"已启用"。
        val selected = installed && !rfdetrNpuChosen() &&
            SpatialPreferences.selectedSegmentationModel(this) == model
        applyRowGate(findViewById(R.id.model_rfdetr_seg), gate)
        findViewById<android.widget.CheckBox>(R.id.cb_rfdetr_seg).apply {
            isChecked = selected
            isEnabled = gate && eligible
        }
        // 未下载不显示勾选框
        applyControlVisibility(findViewById(R.id.cb_rfdetr_seg), installed)
        findViewById<TextView>(R.id.tv_rfdetr_seg_status).text = when {
            active -> segmentationWorkStatus(info)
            // 与其它模型行同一套措辞："已启用 / 已下载安装"（2026-08-15 统一）
            selected -> getString(R.string.spatial_model_enabled, size)
            installed -> getString(R.string.spatial_model_installed, size)
            !eligible -> getString(
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
        findViewById<android.widget.ImageView>(R.id.btn_rfdetr_seg).apply {
            visibility = if (showDelete) View.GONE else View.VISIBLE
            applyActionIcon(this, active, active || (gate && eligible))
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
                refreshAll()
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
                content = getString(R.string.spatial_delete_legacy_message),
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
        // 旧组件只对装过它的老用户显示（提供删除入口），新用户不该看到一行永远
        // 不可用的东西。它没有选择语义，勾选控件恒不显示。
        findViewById<View>(R.id.model_edgetam_refinement).visibility =
            if (installed || active) View.VISIBLE else View.GONE
        findViewById<RadioButton>(R.id.rb_edgetam_refinement).isChecked = false
        applyControlVisibility(findViewById(R.id.rb_edgetam_refinement), false)
        findViewById<TextView>(R.id.tv_edgetam_refinement_status).text = when {
            active -> boundaryRefinementWorkStatus(info)
            installed -> getString(R.string.spatial_legacy_component_installed, size)
            else -> getString(R.string.spatial_legacy_component_unused)
        }
        val showDelete = installed && !active
        findViewById<View>(R.id.iv_edgetam_refinement_delete).visibility =
            if (showDelete) View.VISIBLE else View.GONE
        findViewById<android.widget.ImageView>(R.id.btn_edgetam_refinement).apply {
            visibility = if (active) View.VISIBLE else View.GONE
            applyActionIcon(this, active = true, enabled = active)
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
        findViewById<View>(R.id.row_depth_detail).setOnClickListener {
            showDepthDetailChooser()
        }
        refreshDepthDetail()
        // 同各模型行：勾选框只作显示，点击穿透到整行
        findViewById<android.widget.CheckBox>(R.id.cb_qnn).isClickable = false
        findViewById<View>(R.id.row_qnn).setOnClickListener {
            if (!qnnDeviceEligible()) return@setOnClickListener
            applyQnnEnabled(!SpatialPreferences.qnnEnabled(this))
        }
        findViewById<View>(R.id.btn_qnn).setOnClickListener {
            // 设备不达标时按钮已隐藏，这里再挡一次：下载任务本身也会以
            // "本机不是受支持的骁龙 NPU 机型"失败，不该让它入队闪一下进度。
            if (!qnnDeviceEligible()) return@setOnClickListener
            if (qnnRuntimeWorkInfo?.let(::isActive) == true) {
                cancelQnnRuntimeDownload()
            } else if (qnnRuntimeEntry == null) {
                // 目录没就绪时按钮照样可点：点了说明原因并重试拉取，与模型行一致，
                // 而不是把按钮点死让人猜（2026-08-15 统一）。
                Toast.makeText(
                    this,
                    runtimeCatalogError ?: getString(R.string.spatial_catalog_loading),
                    Toast.LENGTH_LONG
                ).show()
                refreshRuntimeCatalog()
            } else {
                startQnnRuntimeDownload()
            }
        }
        WorkManager.getInstance(applicationContext)
            .getWorkInfosForUniqueWorkLiveData(
                SpatialQnnRuntimeDownloadCoordinator.UNIQUE_WORK_NAME
            )
            .observe(this) { infos ->
                qnnRuntimeWorkInfo = currentWorkInfo(infos)
                refreshAll()
            }
        findViewById<View>(R.id.iv_qnn_delete).setOnClickListener {
            // 与其它删除入口一致：先弹确认框说明组件的作用，确认后才动手
            //（此前这里是全页唯一一个不确认直接删的，2026-08-15 用户指出）。
            showAppAlert(
                title = getString(
                    R.string.spatial_delete_model_title,
                    getString(R.string.spatial_qnn_title)
                ),
                content = getString(R.string.spatial_delete_qnn_runtime_message),
                confirmText = getString(R.string.act_delete)
            ) {
                // 只删 NPU 这一份，CPU 版不动；组件没了勾选必须跟着掉。
                SpatialRuntimeStore.deleteVariant(this, qnn = true)
                applyQnnEnabled(false)
            }
        }
        bindNpuVariantRows()
        WorkManager.getInstance(applicationContext)
            .getWorkInfosForUniqueWorkLiveData(
                SpatialQnnPrecompiledDownloadCoordinator.uniqueWorkName(
                    SpatialInpaintingModel.BIG_LAMA_PLACES2_512.stableId
                )
            )
            .observe(this) { infos ->
                precompiledWorkInfo = currentWorkInfo(infos)
                refreshAll()
            }
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
                content = getString(R.string.spatial_delete_depth_message),
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

        if (!SpatialRuntimeStore.isVariantInstalled(this, qnn = false) && runtimeEntry == null) {
            Toast.makeText(
                this,
                runtimeCatalogError ?: getString(R.string.spatial_catalog_loading),
                Toast.LENGTH_LONG
            ).show()
            refreshRuntimeCatalog()
            return
        }
        if (!SpatialRuntimeStore.isVariantInstalled(this, qnn = false) && runtimeEntry?.enabled != true) {
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
                if (SpatialRuntimeStore.isVariantInstalled(this, qnn = false)) 0L else runtimeEntry?.sizeBytes ?: 0L
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
        // **按 CPU 变体判定**：此前用 isInstalled(当前变体)，开着 NPU 时它问的是 QNN 那一份，
        // 于是这一行明明装着 CPU 版却走进下载分支——点删除弹出"使用流量下载"
        // （2026-08-14 实测）。
        if (SpatialRuntimeStore.isVariantInstalled(this, qnn = false)) {
            showAppAlert(
                title = getString(R.string.spatial_delete_runtime_title),
                content = getString(R.string.spatial_delete_runtime_message),
                confirmText = getString(R.string.act_delete)
            ) {
                background.execute {
                    // 只删 CPU 那一份，NPU 版不受影响
                    SpatialRuntimeStore.deleteVariant(applicationContext, qnn = false)
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
                    // 基础模型没了，NPU 版的选中位一并清掉，否则重装后会自动跳回 NPU 版
                    if (model == SpatialInpaintingModel.BIG_LAMA_PLACES2_512) {
                        SpatialPreferences.setQnnEnabledFor(
                            applicationContext, model.stableId, false
                        )
                    }
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
                if (SpatialRuntimeStore.isVariantInstalled(this, qnn = false)) {
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
            // 选中不在这里做：下载装好那一刻由 Worker 写入（页面关着也生效），
            // 装好前把偏好指到未安装的模型上只会与 reconcileSelections 打架。
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
            // 同 requestNotificationAndEnqueue：选中由 Worker 在装好那一刻写入
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
            // 同 requestNotificationAndEnqueue：选中由 Worker 在装好那一刻写入
            //（分割 Worker 本来就这么做，此前这里的入队预选是多余的一份）
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
                // 必须在 fetchOrCached 之后取：那一步才会把 catalog 的 SoC 覆盖表快照下来，
                // 放在前面会用上一轮的旧表，新机型第一次进设置页仍然判不出 dsp_arch。
                val dspArch = SpatialQnnSupport.resolveDspArch(applicationContext)
                // 传 null 也有意义：查不出架构时会退回全 arch 包，正是新芯片的兜底。
                val qnnRuntime = catalog.qnnRuntimeForCurrentDevice(dspArch)
                val qnnPrecompiled = dspArch?.let {
                    catalog.qnnPrecompiledFor(
                        SpatialInpaintingModel.BIG_LAMA_PLACES2_512.stableId,
                        SpatialInpaintingModel.BIG_LAMA_PLACES2_512.version,
                        it
                    )
                }
                // 装着的若是同一个键下的**旧**产物就先删掉——键里不含产物内容，
                // 不主动比一次的话这一行会一直显示"已下载"，用户没有任何途径拿到新的
                // （D262 重编 Big-LaMa 的 context binary 后模型并没有升版）。
                qnnPrecompiled?.let {
                    SpatialQnnPrecompiledStore.purgeIfStale(applicationContext, it)
                }
                CatalogSnapshot(
                    runtime, inpainting, segmentation, boundaryRefinement,
                    qnnRuntime, qnnPrecompiled
                )
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                runtimeCatalogLoading = false
                runtimeEntry = result.getOrNull()?.runtime
                qnnRuntimeEntry = result.getOrNull()?.qnnRuntime
                qnnPrecompiledEntry = result.getOrNull()?.qnnPrecompiled
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

    /**
     * CPU 版推理运行环境是整个空间照片功能的基础：没装时不论 NPU 组件、模型状态如何，
     * 功能一律不可用（2026-08-15 用户裁定）。本页除「推理运行环境」行和不依赖推理的
     * 交互、储存两组外，全部选项据此置灰；正在进行的下载仍可取消，已装文件仍可删除
     * ——清理磁盘不需要推理环境在位。
     */
    private fun baseRuntimeReady(): Boolean =
        SpatialRuntimeStore.isVariantInstalled(this, qnn = false)

    /** 行级置灰。alpha 双向显式设置，避免上一轮置灰的 0.4 在恢复后残留。 */
    private fun applyRowGate(row: View, enabled: Boolean) {
        row.isEnabled = enabled
        row.alpha = if (enabled) 1f else DISABLED_ROW_ALPHA
    }

    /**
     * 勾选控件只在模型已下载时显示（GONE）。控件在时文字容器缩进 12dp 紧随其后；
     * 控件没了就把缩进改成 2dp——行自身 padding 16dp 之上再 2dp，文字左缘落在 18dp，
     * 与组标题图标的左缘同一条线（2026-08-15 用户要求，不顶行首也不吊在 28dp 半空）。
     * 文字容器没有 id，但每行结构固定为「控件、文字、下载钮、删除钮」，按相邻关系取。
     */
    private fun applyControlVisibility(control: android.widget.CompoundButton, visible: Boolean) {
        control.visibility = if (visible) View.VISIBLE else View.GONE
        val parent = control.parent as android.view.ViewGroup
        val texts = parent.getChildAt(parent.indexOfChild(control) + 1)
        val margin = ((if (visible) 12f else 2f) * resources.displayMetrics.density).toInt()
        val lp = texts.layoutParams as android.view.ViewGroup.MarginLayoutParams
        if (lp.marginStart != margin) {
            lp.marginStart = margin
            texts.layoutParams = lp
        }
    }

    /** 人物连续性当前是否选中了 NPU 版。CPU 行与 NPU 行的勾、状态文案共用这一个判定。 */
    private fun rfdetrNpuChosen(): Boolean {
        val rfdetr = SpatialSegmentationModel.RF_DETR_SEG_NANO
        return SpatialPreferences.qnnEnabledFor(this, rfdetr.stableId) &&
            qnnDeviceEligible() && SpatialPreferences.qnnEnabled(this) &&
            SpatialRuntimeStore.isVariantInstalled(this, qnn = true) &&
            SpatialSegmentationModelStore.isInstalled(this, rfdetr) &&
            SpatialPreferences.selectedSegmentationModel(this) == rfdetr
    }

    /**
     * 必选类型（深度、背景补全）的硬约束：只要有已安装模型，就必须恰有一个被选中
     * ——下载完成、删除回落、偏好指向已被删的模型，所有时序都在刷新前先修正。
     * 人物连续性是可选类型（CheckBox），只清掉指向未安装模型的残留选择；
     * 下载中不清，入队时写下的预选要留到装完生效。
     */
    private fun reconcileSelections() {
        val depthInstalled = SpatialDepthModel.entries.filter {
            SpatialModelStore.isInstalled(this, it)
        }
        if (depthInstalled.isNotEmpty() &&
            SpatialPreferences.selectedModel(this) !in depthInstalled
        ) {
            SpatialPreferences.setSelectedModel(this, depthInstalled.first())
        }
        val inpaintingInstalled = SpatialInpaintingModel.entries.filter {
            SpatialInpaintingModelStore.isInstalled(this, it)
        }
        if (inpaintingInstalled.isNotEmpty() &&
            SpatialPreferences.selectedInpaintingModel(this) !in inpaintingInstalled
        ) {
            SpatialPreferences.setSelectedInpaintingModel(this, inpaintingInstalled.first())
        }
        val segmentation = SpatialPreferences.selectedSegmentationModel(this)
        if (segmentation != null &&
            !SpatialSegmentationModelStore.isInstalled(this, segmentation) &&
            segmentationWorkInfo?.let(::isActive) != true
        ) {
            SpatialPreferences.setSelectedSegmentationModel(this, null)
            SpatialPreferences.setQnnEnabledFor(this, segmentation.stableId, false)
        }
    }

    private fun refreshAll() {
        reconcileSelections()
        refreshRuntime()
        SpatialDepthModel.entries.forEach(::refreshModel)
        SpatialInpaintingModel.entries.forEach(::refreshInpainting)
        refreshInpaintingQuality()
        refreshDepthDetail()
        refreshQnn()
        refreshNpuVariantRows()
        refreshMatting()
        refreshSegmentation()
        refreshBoundaryRefinement()
        refreshStorage()
    }

    private fun refreshRuntime() {
        val installed = SpatialRuntimeStore.isVariantInstalled(this, qnn = false)
        val independentInfo = runtimeWorkInfo
        val modelInfo = activeModelRuntimeWork()
        val info = independentInfo?.takeIf(::isActive) ?: modelInfo
        val active = info?.let(::isActive) == true
        val entry = runtimeEntry
        val formattedSize = Formatter.formatFileSize(
            this,
            // 拆分后 totalBytes 是两份之和，这一行只能报 CPU 版自己的体积
            if (installed) SpatialRuntimeStore.variantTotalBytes(this, qnn = false)
            else entry?.sizeBytes ?: 0L
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
        findViewById<android.widget.ImageView>(R.id.btn_runtime).apply {
            visibility = if (showRuntimeDelete) View.GONE else View.VISIBLE
            // 基础组件的下载按钮在任何情况下都可点（2026-08-15 用户裁定）：目录没就绪、
            // 条目被禁用之类的原因由 onRuntimeAction 弹 Toast 说清，而不是把按钮变淡了事。
            applyActionIcon(this, active, enabled = true)
        }
    }

    private fun refreshModel(model: SpatialDepthModel) {
        val installed = SpatialModelStore.isInstalled(this, model)
        val eligible = SpatialModelStore.isDeviceEligible(this, model)
        val info = workInfo[model]
        val active = info?.let(::isActive) == true
        val gate = baseRuntimeReady()
        applyRowGate(row(model), gate)
        // **没装就不许显示选中**。此前一个都没装时会把默认模型（ZipDepth）勾上，
        // 让人以为它已经可用；实际生成时才会撞上"模型未安装"。偏好里存的只是"用户挑了
        // 哪个"，装没装是另一回事，勾选状态必须同时满足两者（2026-08-14 用户要求）。
        val selected = installed && SpatialPreferences.selectedModel(this) == model
        radio(model).apply {
            isChecked = selected
            isEnabled = gate && eligible
        }
        // 未下载不显示单选钮：装好才谈得上选（2026-08-15 用户要求，全部模型行统一）
        applyControlVisibility(radio(model), installed)

        val size = Formatter.formatFileSize(this, model.sizeBytes)
        status(model).text = when {
            // 装了但没在用，与正在用的，要分开说：与发丝细化、人物连续性两组一致
            selected -> getString(R.string.spatial_model_enabled, size)
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
        applyActionIcon(button(model), active, active || (gate && eligible))
    }

    private fun refreshInpainting(model: SpatialInpaintingModel) {
        val installed = SpatialInpaintingModelStore.isInstalled(this, model)
        val eligible = SpatialInpaintingModelStore.isDeviceEligible(this, model)
        val info = inpaintingWorkInfo[model]
        val active = info?.let(::isActive) == true
        val entry = inpaintingEntries[model]
        val gate = baseRuntimeReady()
        applyRowGate(inpaintingRow(model), gate)
        // 选中 NPU 版时 CPU 版必须取消：两者是同一个 RadioGroup 语义上的两个选项，
        // 但分处不同父容器，系统不会自动互斥（2026-08-14 实测两个同时选中）。
        val npuChosen = SpatialPreferences.qnnEnabledFor(this, model.stableId) &&
            npuVariantUsable(model)
        // 同 refreshModel：没装就不许显示选中，此前一个都没装时会把 MI-GAN 勾上。
        val selected = installed && !npuChosen &&
            SpatialPreferences.selectedInpaintingModel(this) == model
        inpaintingRadio(model).apply {
            isChecked = selected
            isEnabled = gate && eligible
        }
        applyControlVisibility(inpaintingRadio(model), installed)
        val size = Formatter.formatFileSize(this, model.sizeBytes)
        inpaintingStatus(model).text = when {
            selected -> getString(R.string.spatial_model_enabled, size)
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
            // 目录未就绪不再把按钮点死：与深度模型行一致，点击后由 onInpaintingAction
            // 说明原因并重试拉取（此前这里多了一道 entry?.enabled 门，目录加载中
            // 补全三行全淡、深度五行却可点，2026-08-15 统一）。
            applyActionIcon(this, active, active || (gate && eligible))
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

    /**
     * NPU 版模型的两行。**独立选项而不是主行上的复选框**：用户要能单独下载、单独选中、
     * 单独删除，开总开关时也不该被动多下几百 MB（2026-08-14 用户明确要求）。
     *
     * 两者的差别在于要不要额外下载：Big-LaMa 的图端上编不出来，必须下发预编译产物；
     * RF-DETR 首次使用时在本机编译（约 14 秒）即可，不需要额外文件。
     */
    private fun bindNpuVariantRows() {
        val bigLama = SpatialInpaintingModel.BIG_LAMA_PLACES2_512
        val selectBigLamaNpu = View.OnClickListener {
            val ctxDownloading = precompiledWorkInfo?.let(::isActive) == true
            when {
                npuVariantSelectable(bigLama.stableId) && npuVariantUsable(bigLama) -> {
                    // 必选类型（RadioButton）：只选中，点已选中的不取消
                    SpatialPreferences.setSelectedInpaintingModel(this, bigLama)
                    SpatialPreferences.setQnnEnabledFor(this, bigLama.stableId, true)
                    refreshAll()
                }
                // 预编译产物未下载时整行点击即发起下载，与其它模型行一致；
                // 前提条件（组件、基础模型）不满足时按钮是禁用态，整行同样不响应
                !ctxDownloading && findViewById<View>(R.id.btn_big_lama_npu).let {
                    it.visibility == View.VISIBLE && it.isEnabled
                } -> startPrecompiledDownload()
                else -> refreshAll()
            }
        }
        // 同 bindModel／bindInpainting：RadioButton 只作显示，不挂监听器（挂了会把
        // clickable 设回 true），点击穿透到整行。此前这两行漏改了（D265 只改了 CPU 那几组）。
        findViewById<RadioButton>(R.id.rb_big_lama_npu).isClickable = false
        findViewById<View>(R.id.model_big_lama_npu).setOnClickListener(selectBigLamaNpu)
        findViewById<View>(R.id.btn_big_lama_npu).setOnClickListener {
            // 这个按钮有两态（下载 / 取消），此前只写了下载那一支——图标已经切成取消叉，
            // 按下去却是再入队一次（唯一任务名 + KEEP，等于什么都没发生），
            // 表现为「取消按不了」。Coordinator 的 cancel 早就写好了，只是没人调
            //（2026-08-15 用户指出）。
            if (precompiledWorkInfo?.let(::isActive) == true) {
                SpatialQnnPrecompiledDownloadCoordinator.cancel(this, bigLama.stableId)
                refreshAll()
            } else {
                startPrecompiledDownload()
            }
        }
        findViewById<View>(R.id.iv_big_lama_npu_delete).setOnClickListener {
            // 与其它删除入口一致：先弹确认框说明产物的作用（此前不确认直接删）。
            showAppAlert(
                title = getString(
                    R.string.spatial_delete_model_title,
                    getString(R.string.spatial_big_lama_npu_title)
                ),
                content = getString(R.string.spatial_delete_qnn_precompiled_message),
                confirmText = getString(R.string.act_delete)
            ) {
                SpatialQnnPrecompiledStore.delete(this, bigLama.stableId)
                // 产物没了就不该还选着 NPU 版，退回 CPU 版
                SpatialPreferences.setQnnEnabledFor(this, bigLama.stableId, false)
                refreshAll()
            }
        }

        val rfdetr = SpatialSegmentationModel.RF_DETR_SEG_NANO
        val selectRfdetrNpu = View.OnClickListener {
            // RF-DETR 的 NPU 版不需要额外产物，可选的前提只有运行组件与基础模型。
            // 可选组件（CheckBox）：已选中再点一次即整体取消，与 CPU 行同一套语义。
            if (npuVariantSelectable(rfdetr.stableId) &&
                SpatialSegmentationModelStore.isInstalled(this, rfdetr)
            ) {
                val chosen = rfdetrNpuChosen()
                SpatialPreferences.setSelectedSegmentationModel(
                    this, if (chosen) null else rfdetr
                )
                SpatialPreferences.setQnnEnabledFor(this, rfdetr.stableId, !chosen)
            }
            refreshAll()
        }
        findViewById<android.widget.CheckBox>(R.id.cb_rfdetr_npu).isClickable = false
        findViewById<View>(R.id.model_rfdetr_npu).setOnClickListener(selectRfdetrNpu)
    }

    /** Big-LaMa（NPU 版）预编译产物的下载入口：与其它下载项同一套计费网络确认流程。
     *  此前无条件 `allowMetered = true` 入队，是修掉 NPU 运行组件那处之后仅剩的
     *  一个不问就用流量的入口。 */
    private fun startPrecompiledDownload() {
        val bigLama = SpatialInpaintingModel.BIG_LAMA_PLACES2_512
        val enqueue = { allowMetered: Boolean ->
            SpatialQnnPrecompiledDownloadCoordinator.enqueue(
                this, bigLama.stableId, bigLama.version, allowMetered
            )
            refreshAll()
        }
        if (isMeteredNetwork()) {
            showAppAlert(
                title = getString(R.string.spatial_metered_title),
                content = getString(
                    R.string.spatial_metered_message,
                    getString(R.string.spatial_big_lama_npu_title),
                    Formatter.formatFileSize(this, qnnPrecompiledEntry?.sizeBytes ?: 0L)
                ),
                confirmText = getString(R.string.spatial_download)
            ) { enqueue(true) }
        } else {
            enqueue(false)
        }
    }

    /**
     * 某模型的 NPU 版当前是否真的可用（据此让 CPU 版与 NPU 版互斥选中）。
     * Big-LaMa 还要求预编译产物在位；RF-DETR 只要运行组件在就行。
     */
    private fun npuVariantUsable(model: SpatialInpaintingModel): Boolean {
        if (model != SpatialInpaintingModel.BIG_LAMA_PLACES2_512) return false
        val dspArch = SpatialQnnSupport.resolveDspArch(this) ?: return false
        // 基础模型也是前提之一：状态文案一直把"需先安装 CPU 版"排在最前，
        // 这里此前却不查，删掉基础模型后 NPU 版仍算可用（2026-08-15 补齐）。
        return qnnDeviceEligible() && SpatialPreferences.qnnEnabled(this) &&
            SpatialRuntimeStore.isVariantInstalled(this, qnn = true) &&
            SpatialInpaintingModelStore.isInstalled(this, model) &&
            SpatialQnnPrecompiledStore.isInstalled(
                this, model.stableId, model.version, dspArch
            )
    }

    /** NPU 版可选的前提：总开关开着、运行组件已装。 */
    private fun npuVariantSelectable(modelStableId: String): Boolean =
        qnnDeviceEligible() && SpatialPreferences.qnnEnabled(this) &&
            SpatialRuntimeStore.isVariantInstalled(this, qnn = true)

    private fun refreshNpuVariantRows() {
        // **总开关关掉时置灰而不是隐藏**：让用户看得见"还有 NPU 版这个东西"，
        // 也就知道该去上面把开关打开；隐藏了反而找不到（2026-08-14 用户明确要求）。
        val gate = baseRuntimeReady()
        val deviceOk = qnnDeviceEligible()
        val eligible = deviceOk && SpatialPreferences.qnnEnabled(this)
        val rowUsable = gate && eligible
        val runtimeReady = eligible && SpatialRuntimeStore.isVariantInstalled(this, qnn = true)
        val dspArch = SpatialQnnSupport.resolveDspArch(this)

        // --- Big-LaMa NPU ---
        val bigLama = SpatialInpaintingModel.BIG_LAMA_PLACES2_512
        val baseInstalled = SpatialInpaintingModelStore.isInstalled(this, bigLama)
        val ctxInstalled = dspArch != null && SpatialQnnPrecompiledStore.isInstalled(
            this, bigLama.stableId, bigLama.version, dspArch
        )
        findViewById<View>(R.id.model_big_lama_npu).apply {
            visibility = if (deviceOk) View.VISIBLE else View.GONE
            isEnabled = rowUsable
            alpha = if (rowUsable) 1f else DISABLED_ROW_ALPHA
        }
        val npuSelected = runtimeReady && ctxInstalled && baseInstalled &&
            SpatialPreferences.selectedInpaintingModel(this) == bigLama &&
            SpatialPreferences.qnnEnabledFor(this, bigLama.stableId)
        findViewById<RadioButton>(R.id.rb_big_lama_npu).apply {
            isChecked = npuSelected
            isEnabled = rowUsable
        }
        // 本行的"模型文件"是预编译产物：没下载就不显示单选钮，
        // 与其余模型行的规则统一（2026-08-15 用户要求）。
        applyControlVisibility(findViewById(R.id.rb_big_lama_npu), ctxInstalled)
        val ctxDownloading = precompiledWorkInfo?.let(::isActive) == true
        val ctxSize = Formatter.formatFileSize(this, qnnPrecompiledEntry?.sizeBytes ?: 0L)
        val npuRuntimeReady = SpatialRuntimeStore.isVariantInstalled(this, qnn = true)
        findViewById<TextView>(R.id.tv_big_lama_npu_status).text = when {
            // 每种状态一条文案，不合并——合并过一次，结果是"提示我做我已经做过的事"。
            !eligible -> getString(R.string.spatial_npu_needs_runtime)
            !npuRuntimeReady -> getString(R.string.spatial_npu_runtime_pending)
            !baseInstalled -> getString(R.string.spatial_npu_needs_base_model)
            // **下载中要排在已安装前面**，与其余模型行相反。别的模型装上了就不会再下，
            // 两个条件互斥；而预编译产物是会**原地升级**的（D262 换产物但模型没升版），
            // 升级过程中旧的还在，`ctxInstalled` 仍为真——按其余行的顺序写，整个下载过程
            // 都会显示"已安装 155 MB"，用户看不到任何进展。
            ctxDownloading -> precompiledWorkStatus(precompiledWorkInfo, ctxSize)
            npuSelected -> getString(R.string.spatial_model_enabled, ctxSize)
            ctxInstalled -> getString(R.string.spatial_model_installed, ctxSize)
            else -> getString(R.string.spatial_model_not_downloaded, ctxSize)
        }
        // **不满足条件时置灰而不是隐藏**：隐藏了用户只会看到"根本没有下载按钮"
        // （2026-08-14 实测反馈）。能不能点由 enabled 表达，为什么不能点由状态文案说。
        findViewById<View>(R.id.btn_big_lama_npu).visibility =
            if (ctxInstalled) View.GONE else View.VISIBLE
        applyActionIcon(
            findViewById(R.id.btn_big_lama_npu),
            active = ctxDownloading,
            // **下载中必须算「可用」**，与本页其余每一行一致（`eligible || active`）。
            // 写成 `&& !ctxDownloading` 的后果是：一开始下载，图标切成取消叉的同时被
            // 判成不可用而着淡色——正在进行的操作反而看着像禁用的
            //（2026-08-15 用户指出）。
            enabled = ctxDownloading || (gate && runtimeReady && baseInstalled)
        )
        findViewById<View>(R.id.iv_big_lama_npu_delete).visibility =
            if (ctxInstalled) View.VISIBLE else View.GONE

        // --- RF-DETR NPU：不需要额外下载，首次使用时端上编译 ---
        val rfdetr = SpatialSegmentationModel.RF_DETR_SEG_NANO
        val rfBaseInstalled = SpatialSegmentationModelStore.isInstalled(this, rfdetr)
        findViewById<View>(R.id.model_rfdetr_npu).apply {
            visibility = if (deviceOk) View.VISIBLE else View.GONE
            isEnabled = rowUsable
            alpha = if (rowUsable) 1f else DISABLED_ROW_ALPHA
        }
        val rfNpuSelected = rfdetrNpuChosen()
        findViewById<android.widget.CheckBox>(R.id.cb_rfdetr_npu).apply {
            isChecked = rfNpuSelected
            isEnabled = rowUsable
        }
        // 本行复用基础模型的文件：基础模型没下载就不显示勾选框
        applyControlVisibility(findViewById(R.id.cb_rfdetr_npu), rfBaseInstalled)
        findViewById<TextView>(R.id.tv_rfdetr_npu_status).text = when {
            !eligible -> getString(R.string.spatial_npu_needs_runtime)
            !npuRuntimeReady -> getString(R.string.spatial_npu_runtime_pending)
            !rfBaseInstalled -> getString(R.string.spatial_npu_needs_base_model)
            // 这一行没有"已下载"这种状态（它不需要额外产物），选中时把"已启用"和
            // 那句说明拼起来，不为此单开一条要翻 12 个语言的文案
            rfNpuSelected -> getString(
                R.string.spatial_model_enabled,
                getString(R.string.spatial_npu_no_extra_download)
            )
            else -> getString(R.string.spatial_npu_no_extra_download)
        }
        findViewById<View>(R.id.btn_rfdetr_npu).visibility = View.GONE
        findViewById<View>(R.id.iv_rfdetr_npu_delete).visibility = View.GONE
    }

    /**
     * 本机能不能用 NPU。**判定表不再是准入依据**（D267）：
     *
     * - 查得到 dsp_arch → 照旧可用，下载对应的单份包；
     * - 查不到 → 只要 catalog 里有全 arch 包就照样可用，由 QNN 自己挑 Skel。
     *
     * 后一条是关键：新骁龙出厂第一天就能用，不必等我们把型号登记进表。反过来，
     * 没有全 arch 包时**不能**放行未知机型——那会变成"看得见、下不动"。
     */
    private fun qnnDeviceEligible(): Boolean {
        if (!SpatialQnnSupport.isNpuPossible(this)) return false
        if (SpatialQnnSupport.resolveDspArch(this) != null) return true
        return qnnRuntimeEntry?.dspArch == SpatialQnnSupport.ALL_ARCH
    }

    /**
     * 切换 NPU 总开关。**换开关等于换整包运行组件**：QNN EP 编译在 libonnxruntime.so 里，
     * 同进程只能加载一份，旧的不删就会出现"开了却还在 CPU 上跑"。
     *
     * 打开时自动把组件下下来——用户勾了就是想用，不该再让他去别处找下载入口
     * （2026-08-14 反馈）。关掉时不自动下 CPU 版：那一份会在下次真正需要时按既有链路取。
     */
    private fun applyQnnEnabled(enabled: Boolean) {
        // **不再删另一份**：两个变体在磁盘上共存，开关只决定加载哪一份。
        // 此前一切换就删，导致来回切要反复重下一百多 MB（2026-08-14 反馈）。
        SpatialPreferences.setQnnEnabled(this, enabled)
        refreshAll()
        if (enabled && !SpatialRuntimeStore.isVariantInstalled(this, qnn = true)) {
            startQnnRuntimeDownload()
        } else {
            Toast.makeText(this, R.string.spatial_qnn_restart_required, Toast.LENGTH_LONG).show()
        }
    }

    private fun isMeteredNetwork(): Boolean =
        (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
            .isActiveNetworkMetered

    /**
     * 与其它下载项**同一套流程**：计费网络下先确认再下。此前 NPU 这一条直接
     * `allowMetered = true` 入队，是唯一一个不问就用流量的（2026-08-14 实测）。
     */
    private fun startQnnRuntimeDownload() {
        val size = Formatter.formatFileSize(this, qnnRuntimeEntry?.sizeBytes ?: 0L)
        if (isMeteredNetwork()) {
            showAppAlert(
                title = getString(R.string.spatial_metered_title),
                content = getString(
                    R.string.spatial_metered_message, getString(R.string.spatial_qnn_title), size
                ),
                confirmText = getString(R.string.spatial_download)
            ) {
                SpatialQnnRuntimeDownloadCoordinator.enqueue(this, allowMetered = true)
                refreshAll()
            }
        } else {
            SpatialQnnRuntimeDownloadCoordinator.enqueue(this, allowMetered = false)
            refreshAll()
        }
    }

    /** 取消下载 = 组件没装成，勾选必须跟着掉，否则会显示"已启用"却什么都没有。 */
    private fun cancelQnnRuntimeDownload() {
        SpatialQnnRuntimeDownloadCoordinator.cancel(this)
        if (!SpatialRuntimeStore.isVariantInstalled(this, qnn = true)) {
            SpatialPreferences.setQnnEnabled(this, false)
        }
        refreshAll()
    }

    /**
     * NPU 总开关。设备不是受支持的骁龙时整行隐藏——给用户一个永远打不开的开关只会制造困惑。
     * 逐模型那个复选框只在总开关打开时才出现，关着时它不起任何作用。
     */
    /** NPU 运行组件的进度文案，与 CPU 版那套并列，读的是各自的 WorkInfo。 */
    private fun qnnRuntimeWorkStatus(info: WorkInfo?, sizeText: String): String {
        if (info == null) return getString(R.string.spatial_model_not_downloaded, sizeText)
        if (info.state == WorkInfo.State.ENQUEUED) {
            return getString(R.string.spatial_download_waiting, sizeText)
        }
        return when (info.progress.getString(SpatialQnnRuntimeDownloadWorker.KEY_STATE)) {
            SpatialQnnRuntimeDownloadWorker.STATE_VERIFYING ->
                getString(R.string.spatial_download_verifying)
            SpatialQnnRuntimeDownloadWorker.STATE_INSTALLING ->
                getString(R.string.spatial_runtime_installing)
            else -> {
                val downloaded =
                    info.progress.getLong(SpatialQnnRuntimeDownloadWorker.KEY_DOWNLOADED, 0L)
                val total = info.progress.getLong(SpatialQnnRuntimeDownloadWorker.KEY_TOTAL, 0L)
                val percent = if (total > 0L) (downloaded * 100L / total).toInt() else 0
                getString(R.string.spatial_download_progress, percent)
            }
        }
    }

    private fun refreshQnn() {
        val gate = baseRuntimeReady()
        val eligible = qnnDeviceEligible()
        val enabled = SpatialPreferences.qnnEnabled(this)
        // **看的是 QNN 那一份自己的安装状态**，与上面 CPU 版那一行互不影响。
        val installed = SpatialRuntimeStore.isVariantInstalled(this, qnn = true)
        val active = qnnRuntimeWorkInfo?.let(::isActive) == true && !installed
        val size = if (SpatialRuntimeStore.isVariantInstalled(this, qnn = true)) {
            SpatialRuntimeStore.variantTotalBytes(this, qnn = true)
        } else {
            qnnRuntimeEntry?.sizeBytes ?: 0L
        }
        val sizeText = Formatter.formatFileSize(this, size)
        // **只有确定不可能支持的机器才隐藏**（非高通、非 arm64、API < 31）——那类设备上
        // 这一行不是一个有意义的概念。是高通但没登记 dsp_arch 时要显示并置灰，否则
        // spatial_qnn_unsupported 永远渲染不出来，未登记的新骁龙在界面上与"不支持"
        // 无从区分（2026-08-15 用户指出）。
        val candidate = SpatialQnnSupport.isSnapdragonNpuCandidate()
        val rowUsable = gate && eligible
        findViewById<View>(R.id.row_qnn).apply {
            visibility = if (candidate) View.VISIBLE else View.GONE
            isEnabled = rowUsable
            alpha = if (rowUsable) 1f else DISABLED_ROW_ALPHA
        }
        findViewById<View>(R.id.tv_qnn_hint).visibility =
            if (rowUsable && enabled) View.VISIBLE else View.GONE
        findViewById<android.widget.CheckBox>(R.id.cb_qnn).apply {
            isChecked = eligible && enabled
            isEnabled = rowUsable
        }
        // 组件未下载时不显示勾选框：整行点击即开启并自动下载
        applyControlVisibility(findViewById(R.id.cb_qnn), installed)
        findViewById<TextView>(R.id.tv_qnn_status).text = when {
            !eligible -> getString(R.string.spatial_qnn_unsupported)
            active -> qnnRuntimeWorkStatus(qnnRuntimeWorkInfo, sizeText)
            // 与其它模型行**同一套措辞**："已启用 · 大小"／"已下载安装 · 大小"，
            // 不再用"已安装 · 大小 · 使用中"这套独有句式（2026-08-15 用户要求）。
            installed && enabled -> getString(R.string.spatial_model_enabled, sizeText)
            installed -> getString(R.string.spatial_model_installed, sizeText)
            else -> getString(R.string.spatial_model_not_downloaded, sizeText)
        }
        val showDelete = installed && !active
        findViewById<View>(R.id.iv_qnn_delete).visibility =
            if (showDelete) View.VISIBLE else View.GONE
        // 设备不达标时连下载图标一起收起来：这一行此时只负责说明原因，不该留一个
        // 按下去什么也不会发生的入口（组件本来就没有这台机器能用的那一份）。
        findViewById<View>(R.id.btn_qnn).visibility =
            if (showDelete || !eligible) View.GONE else View.VISIBLE
        // 目录没就绪也可点（点了说明原因），只有基础组件缺位才禁——正在下载时恒可取消
        applyActionIcon(findViewById(R.id.btn_qnn), active, active || gate)
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
        applyRowGate(findViewById(R.id.row_inpainting_quality), baseRuntimeReady())
        findViewById<TextView>(R.id.tv_inpainting_quality_value).text =
            qualityLabel(SpatialPreferences.inpaintingQuality(this))
    }

    /**
     * 深度细节（`num_tokens`）只对 MoGe 系有意义——另外三个深度模型的输入尺寸是固定的，
     * 没有这个旋钮。未选中 MoGe 或它还没装好时整块隐藏，与工作分辨率同一套处理。
     */
    private fun refreshDepthDetail() {
        val selected = SpatialPreferences.selectedModel(this)
        val applicable = selected.outputContract == SpatialDepthOutputContract.MOGE_POINT_MAP &&
            SpatialModelStore.isInstalled(this, selected)
        findViewById<View>(R.id.ll_depth_detail).visibility =
            if (applicable) View.VISIBLE else View.GONE
        applyRowGate(findViewById(R.id.row_depth_detail), baseRuntimeReady())
        findViewById<TextView>(R.id.tv_depth_detail_value).text =
            detailLabel(SpatialPreferences.depthDetail(this))
    }

    private fun showDepthDetailChooser() {
        val details = SpatialDepthDetail.entries.toList()
        val cdf = ChooserDialogFragment()
        cdf.setAccentBackground(App.defaultAccentBackground)
        cdf.setTitle(getString(R.string.spatial_depth_detail))
        cdf.setShouldShowMore(false)
        cdf.setItems(details.map { detailLabel(it) as String? }.toMutableList())
        val initialIndex = details.indexOf(SpatialPreferences.depthDetail(this))
            .coerceAtLeast(0)
        cdf.setInitialIndex(initialIndex)
        cdf.setConfirmListener(View.OnClickListener {
            val picked = cdf.getPickedIndex()
            if (picked != initialIndex && picked in details.indices) {
                SpatialPreferences.setDepthDetail(this, details[picked])
                refreshDepthDetail()
            }
        })
        cdf.show(supportFragmentManager, ChooserDialogFragment.TAG)
    }

    private fun detailLabel(detail: SpatialDepthDetail): String = getString(
        when (detail) {
            SpatialDepthDetail.FAST -> R.string.spatial_depth_detail_fast
            SpatialDepthDetail.STANDARD -> R.string.spatial_depth_detail_standard
            SpatialDepthDetail.FINE -> R.string.spatial_depth_detail_fine
        }
    )

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

    /**
     * Big-LaMa NPU 版的下载状态，与其余模型行一样按阶段+百分比呈现。
     *
     * 此前这一行不看 WorkInfo 的进度，一律显示静态的"下载中…"——worker 明明在
     * `setProgressAsync` 里发了字节数，只是没人读（2026-08-14 用户指出）。
     * 复用既有文案，不新增字符串，免得 12 个语言再跟一轮。
     */
    private fun precompiledWorkStatus(info: WorkInfo?, formattedSize: String): String {
        if (info?.state == WorkInfo.State.ENQUEUED) {
            return getString(R.string.spatial_download_waiting, formattedSize)
        }
        return when (
            info?.progress?.getString(SpatialQnnPrecompiledDownloadWorker.KEY_STATE)
        ) {
            // 运行组件还没就位时先装它，此时的字节数是组件的，不是这个模型的
            SpatialQnnPrecompiledDownloadWorker.STATE_RUNTIME ->
                getString(R.string.spatial_npu_runtime_pending)
            SpatialQnnPrecompiledDownloadWorker.STATE_VERIFYING ->
                getString(R.string.spatial_download_verifying)
            SpatialQnnPrecompiledDownloadWorker.STATE_INSTALLING ->
                getString(R.string.spatial_runtime_installing)
            else -> {
                val downloaded = info?.progress?.getLong(
                    SpatialQnnPrecompiledDownloadWorker.KEY_DOWNLOADED,
                    0L
                ) ?: 0L
                val total = info?.progress?.getLong(
                    SpatialQnnPrecompiledDownloadWorker.KEY_TOTAL,
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
            SpatialDepthModel.MOGE_2_VITB_NORMAL -> R.id.model_moge2b
        }
    )

    private fun radio(model: SpatialDepthModel): RadioButton = findViewById(radioId(model))

    private fun radioId(model: SpatialDepthModel): Int = when (model) {
        SpatialDepthModel.ZIPDEPTH -> R.id.rb_zipdepth
        SpatialDepthModel.DEPTH_ANYTHING_V2_SMALL -> R.id.rb_dav2
        SpatialDepthModel.DEPTH_ANYTHING_3_SMALL -> R.id.rb_da3
        SpatialDepthModel.MOGE_2_VITS_NORMAL -> R.id.rb_moge2
        SpatialDepthModel.MOGE_2_VITB_NORMAL -> R.id.rb_moge2b
    }

    private fun status(model: SpatialDepthModel): TextView = findViewById(
        when (model) {
            SpatialDepthModel.ZIPDEPTH -> R.id.tv_zipdepth_status
            SpatialDepthModel.DEPTH_ANYTHING_V2_SMALL -> R.id.tv_dav2_status
            SpatialDepthModel.DEPTH_ANYTHING_3_SMALL -> R.id.tv_da3_status
            SpatialDepthModel.MOGE_2_VITS_NORMAL -> R.id.tv_moge2_status
            SpatialDepthModel.MOGE_2_VITB_NORMAL -> R.id.tv_moge2b_status
        }
    )

    private fun button(model: SpatialDepthModel): android.widget.ImageView =
        findViewById(buttonId(model))

    private fun buttonId(model: SpatialDepthModel): Int = when (model) {
        SpatialDepthModel.ZIPDEPTH -> R.id.btn_zipdepth
        SpatialDepthModel.DEPTH_ANYTHING_V2_SMALL -> R.id.btn_dav2
        SpatialDepthModel.DEPTH_ANYTHING_3_SMALL -> R.id.btn_da3
        SpatialDepthModel.MOGE_2_VITS_NORMAL -> R.id.btn_moge2
        SpatialDepthModel.MOGE_2_VITB_NORMAL -> R.id.btn_moge2b
    }

    private fun deleteIcon(model: SpatialDepthModel): View = findViewById(deleteIconId(model))

    /** 穷举 when：加深度模型时编译器会在这里报错，图标着色那一处也就跟着补上了。 */
    private fun deleteIconId(model: SpatialDepthModel): Int = when (model) {
        SpatialDepthModel.ZIPDEPTH -> R.id.iv_zipdepth_delete
        SpatialDepthModel.DEPTH_ANYTHING_V2_SMALL -> R.id.iv_dav2_delete
        SpatialDepthModel.DEPTH_ANYTHING_3_SMALL -> R.id.iv_da3_delete
        SpatialDepthModel.MOGE_2_VITS_NORMAL -> R.id.iv_moge2_delete
        SpatialDepthModel.MOGE_2_VITB_NORMAL -> R.id.iv_moge2b_delete
    }

    // 三个补全模型各占一行；这里必须用穷举 when，加第四个模型时编译器会直接报错，
    // 而原来的两路 if/else 会把新模型静默映射到 AOT-GAN 那一行。
    private fun inpaintingDeleteIcon(model: SpatialInpaintingModel): View =
        findViewById(inpaintingDeleteIconId(model))

    private fun inpaintingDeleteIconId(model: SpatialInpaintingModel): Int = when (model) {
        SpatialInpaintingModel.MIGAN_PLACES2_512_PIPELINE -> R.id.iv_migan_delete
        SpatialInpaintingModel.AOTGAN_PLACES2_512 -> R.id.iv_aotgan_delete
        SpatialInpaintingModel.BIG_LAMA_PLACES2_512 -> R.id.iv_big_lama_delete
    }

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

    private fun inpaintingButton(model: SpatialInpaintingModel): android.widget.ImageView =
        findViewById(inpaintingButtonId(model))

    private fun inpaintingButtonId(model: SpatialInpaintingModel): Int = when (model) {
        SpatialInpaintingModel.MIGAN_PLACES2_512_PIPELINE -> R.id.btn_migan
        SpatialInpaintingModel.AOTGAN_PLACES2_512 -> R.id.btn_aotgan
        SpatialInpaintingModel.BIG_LAMA_PLACES2_512 -> R.id.btn_big_lama
    }

    /**
     * 下载/取消这一对动作图标。与删除图标同规格（40dp、圆形 ripple、同一套着色），
     * 因此暗色模式与按压反馈自动一致——此前它是文字按钮，两者观感对不齐（2026-08-14 反馈）。
     */
    private fun applyActionIcon(view: android.widget.ImageView, active: Boolean, enabled: Boolean) {
        view.setImageDrawable(
            DisplayUtil.opaqueTintDrawable(
                this,
                ContextCompat.getDrawable(
                    this,
                    if (active) R.drawable.vec_ic_download_cancel else R.drawable.vec_ic_download
                ),
                ContextCompat.getColor(
                    this,
                    if (enabled) R.color.app_chrome_control_unchecked
                    else R.color.app_chrome_on_surface_hint
                )
            )
        )
        view.contentDescription =
            getString(if (active) R.string.cancel else R.string.spatial_download)
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.4f
    }

    companion object {
        private const val REQUEST_NOTIFICATION_PERMISSION = 9201

        /** 置灰行的透明度，与 [applyActionIcon] 里禁用图标的取值一致。 */
        private const val DISABLED_ROW_ALPHA = 0.4f
    }
}
