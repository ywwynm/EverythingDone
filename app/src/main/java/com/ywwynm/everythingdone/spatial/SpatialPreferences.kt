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
    private const val KEY_SELECTED_SEGMENTATION_MODEL = "selected_segmentation_model"
    private const val KEY_SELECTED_BOUNDARY_REFINEMENT_MODEL =
        "selected_boundary_refinement_model"
    private const val KEY_DEVICE_TILT = "device_tilt"
    private const val KEY_INTERACTION_HINT_SHOWN = "interaction_hint_shown"
}
