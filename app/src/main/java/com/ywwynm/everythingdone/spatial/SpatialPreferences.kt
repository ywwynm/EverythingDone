package com.ywwynm.everythingdone.spatial

import android.content.Context

object SpatialPreferences {

    fun selectedModel(context: Context): SpatialDepthModel {
        val stored = preferences(context).getString(KEY_SELECTED_MODEL, null)
        return SpatialDepthModel.fromStableId(stored) ?: SpatialDepthModel.ZIPDEPTH
    }

    fun setSelectedModel(context: Context, model: SpatialDepthModel) {
        preferences(context).edit().putString(KEY_SELECTED_MODEL, model.stableId).apply()
    }

    fun selectedInpaintingModel(context: Context): SpatialInpaintingModel {
        val stored = preferences(context).getString(KEY_SELECTED_INPAINTING_MODEL, null)
        return SpatialInpaintingModel.fromStableId(stored)
            ?: SpatialInpaintingModel.MIGAN_PLACES2_512_PIPELINE
    }

    fun setSelectedInpaintingModel(context: Context, model: SpatialInpaintingModel) {
        preferences(context).edit()
            .putString(KEY_SELECTED_INPAINTING_MODEL, model.stableId)
            .apply()
    }

    fun inpaintingQuality(context: Context): SpatialInpaintingQuality {
        val stored = preferences(context).getString(KEY_INPAINTING_QUALITY, null)
        return SpatialInpaintingQuality.fromStableId(stored)
            ?: SpatialInpaintingQuality.HIGH
    }

    fun setInpaintingQuality(context: Context, quality: SpatialInpaintingQuality) {
        preferences(context).edit()
            .putString(KEY_INPAINTING_QUALITY, quality.stableId)
            .apply()
    }

    /**
     * MoGe 系模型的几何细节档（`num_tokens`）。只影响新生成，已有派生不受影响。
     */
    fun depthDetail(context: Context): SpatialDepthDetail {
        val stored = preferences(context).getString(KEY_DEPTH_DETAIL, null)
        return SpatialDepthDetail.fromStableId(stored) ?: SpatialDepthDetail.DEFAULT
    }

    fun setDepthDetail(context: Context, detail: SpatialDepthDetail) {
        preferences(context).edit()
            .putString(KEY_DEPTH_DETAIL, detail.stableId)
            .apply()
    }

    /** null 表示保留纯深度 ownership；下载模型后由设置页显式选中。 */
    fun selectedSegmentationModel(context: Context): SpatialSegmentationModel? {
        val stored = preferences(context).getString(KEY_SELECTED_SEGMENTATION_MODEL, null)
        return SpatialSegmentationModel.fromStableId(stored)
    }

    fun setSelectedSegmentationModel(
        context: Context,
        model: SpatialSegmentationModel?
    ) {
        preferences(context).edit().apply {
            if (model == null) remove(KEY_SELECTED_SEGMENTATION_MODEL)
            else putString(KEY_SELECTED_SEGMENTATION_MODEL, model.stableId)
        }.apply()
    }

    /** null 表示关闭 prompt 边界细化；它只在实例分割启用时参与生成。 */
    fun selectedBoundaryRefinementModel(context: Context): SpatialBoundaryRefinementModel? {
        val stored = preferences(context).getString(KEY_SELECTED_BOUNDARY_REFINEMENT_MODEL, null)
        return SpatialBoundaryRefinementModel.fromStableId(stored)
    }

    fun setSelectedBoundaryRefinementModel(
        context: Context,
        model: SpatialBoundaryRefinementModel?
    ) {
        preferences(context).edit().apply {
            if (model == null) remove(KEY_SELECTED_BOUNDARY_REFINEMENT_MODEL)
            else putString(KEY_SELECTED_BOUNDARY_REFINEMENT_MODEL, model.stableId)
        }.apply()
    }

    /**
     * 发丝边缘细化（MODNet）是否启用。装了不等于要用——它是可选组件，用户应当能在
     * 不删除模型的前提下关掉（2026-08-13 反馈）。分割与边界细化那两个走的是
     * "选中的模型可为 null"，MODNet 只有一个候选，所以用布尔。
     */
    fun mattingEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_MATTING_ENABLED, true)

    fun setMattingEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_MATTING_ENABLED, enabled).apply()
    }

    /**
     * 是否启用骁龙 NPU（QNN HTP）加速。**默认关**——它需要另一套运行组件（QNN 版
     * onnxruntime + QAIRT 库，约 128 MB），不能让所有人白下。
     *
     * 打开只表示"愿意用"：设备不是骁龙、或架构不在白名单、或组件还没装好时，
     * [SpatialQnnSessionFactory] 仍然返回 null，各引擎照常走 CPU。
     */
    fun qnnEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_QNN_ENABLED, false)

    fun setQnnEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_QNN_ENABLED, enabled).apply()
    }

    /**
     * 逐模型的 QNN 开关。总开关之下再分模型——不同模型上 NPU 的收益差别很大，
     * 而且首次要在设备上编译计算图（20–50 秒），用户应当能只给值得的那些开。
     * 默认全开：总开关关着时这一层不起作用。
     */
    fun qnnEnabledFor(context: Context, modelStableId: String): Boolean =
        preferences(context).getBoolean(KEY_QNN_MODEL_PREFIX + modelStableId, true)

    fun setQnnEnabledFor(context: Context, modelStableId: String, enabled: Boolean) {
        preferences(context).edit()
            .putBoolean(KEY_QNN_MODEL_PREFIX + modelStableId, enabled).apply()
    }

    fun deviceTiltEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_DEVICE_TILT, true)

    fun setDeviceTiltEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_DEVICE_TILT, enabled).apply()
    }

    fun shouldShowInteractionHint(context: Context): Boolean =
        !preferences(context).getBoolean(KEY_INTERACTION_HINT_SHOWN, false)

    fun markInteractionHintShown(context: Context) {
        preferences(context).edit().putBoolean(KEY_INTERACTION_HINT_SHOWN, true).apply()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private const val PREFERENCES_NAME = "spatial_photo_settings"
    private const val KEY_SELECTED_MODEL = "selected_model"
    private const val KEY_SELECTED_INPAINTING_MODEL = "selected_inpainting_model"
    private const val KEY_INPAINTING_QUALITY = "inpainting_quality"
    private const val KEY_DEPTH_DETAIL = "depth_detail"
    private const val KEY_MATTING_ENABLED = "matting_enabled"
    private const val KEY_QNN_ENABLED = "qnn_enabled"
    private const val KEY_QNN_MODEL_PREFIX = "qnn_model_"
    private const val KEY_SELECTED_SEGMENTATION_MODEL = "selected_segmentation_model"
    private const val KEY_SELECTED_BOUNDARY_REFINEMENT_MODEL =
        "selected_boundary_refinement_model"
    private const val KEY_DEVICE_TILT = "device_tilt"
    private const val KEY_INTERACTION_HINT_SHOWN = "interaction_hint_shown"
}
