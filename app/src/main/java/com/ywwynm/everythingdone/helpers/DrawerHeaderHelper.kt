package com.ywwynm.everythingdone.helpers

import android.content.Context
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.activities.SettingsActivity
import com.ywwynm.everythingdone.model.DrawerHeaderImageCrop
import com.ywwynm.everythingdone.permission.PermissionUtil
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shared loading for the Drawer Header Image — the single user-chosen header that
 * drives both the navigation drawer ([com.ywwynm.everythingdone.views.DrawerHeader])
 * and the statistic screen ([com.ywwynm.everythingdone.activities.StatisticActivity]).
 *
 * Both surfaces resolve the same `KEY_DRAWER_HEADER` path + `KEY_DRAWER_HEADER_CROP`
 * crop and render through Glide + [MediaCropTransformation], so a custom image keeps
 * its shared crop and an Animated Image animates per frame (ADR-0007 / ADR-0008).
 */
object DrawerHeaderHelper {

    sealed class Header {
        object Default : Header()
        data class Custom(val path: String, val crop: DrawerHeaderImageCrop) : Header()

        fun cropOrDefault(): DrawerHeaderImageCrop = when (this) {
            is Custom -> crop
            Default -> DrawerHeaderImageCrop.default()
        }
    }

    /**
     * Resolve the current header, mirroring the surfaces' historical fallbacks:
     * missing file resets the pref to default; missing image permission shows the
     * default without clearing the pref (stale data after a reinstall).
     */
    fun resolve(context: Context): Header {
        val sp = context.getSharedPreferences(Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE)
        val default = SettingsActivity.DEFAULT_DRAWER_HEADER
        val path = sp.getString(Def.Meta.KEY_DRAWER_HEADER, default) ?: default
        if (default == path) return Header.Default
        if (!File(path).exists()) {
            sp.edit().putString(Def.Meta.KEY_DRAWER_HEADER, default).apply()
            return Header.Default
        }
        if (!PermissionUtil.hasImagePermission(context)) {
            return Header.Default
        }
        val crop = DrawerHeaderImageCrop.fromJson(
            sp.getString(Def.Meta.KEY_DRAWER_HEADER_CROP, null)
        )
        return Header.Custom(path, crop)
    }

    /** Header display height for a given target width and crop ratio (ratio = width / height). */
    fun targetHeight(targetWidth: Int, crop: DrawerHeaderImageCrop): Int {
        val tw = max(1, targetWidth)
        val ratio = DrawerHeaderImageCrop.normalizeRatio(crop.ratio)
        return max(1, (tw / ratio).roundToInt())
    }

    fun loadCustomInto(
        imageView: ImageView,
        path: String,
        crop: DrawerHeaderImageCrop,
        targetWidth: Int
    ) {
        val tw = max(1, targetWidth)
        val th = targetHeight(tw, crop)
        val mediaCrop = MediaCropBitmapRenderer.Crop(
            centerX = crop.centerX,
            centerY = crop.centerY,
            userScale = crop.scale
        )
        Glide.with(imageView.context)
            .load(File(path))
            .override(tw, th)
            .transform(MediaCropTransformation(tw, th, mediaCrop))
            .into(imageView)
    }
}
