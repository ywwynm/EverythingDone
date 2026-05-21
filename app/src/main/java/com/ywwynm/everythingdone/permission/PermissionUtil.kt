package com.ywwynm.everythingdone.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.model.Thing
import java.util.ArrayList

/**
 * Created by ywwynm on 2016/7/8.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * utils for Permission.
 *
 *
 * API 33+ uses split media permissions (`READ_MEDIA_IMAGES`,
 * `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`). Older code that asked for
 * "any media access" via [getStoragePermissions] should migrate to the
 * type-specific helpers below — denying audio access should not block image
 * attachment loading, and vice versa.
 */
object PermissionUtil {

    @JvmStatic
    fun getImagePermissions(): Array<String?>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        }
        return arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    @JvmStatic
    fun getVideoPermissions(): Array<String?>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        }
        return arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    @JvmStatic
    fun getAudioPermissions(): Array<String?>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        }
        return arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    @JvmStatic
    fun hasImagePermission(context: Context?): Boolean {
        return allGranted(context, getImagePermissions())
    }

    @JvmStatic
    fun hasVideoPermission(context: Context?): Boolean {
        return allGranted(context, getVideoPermissions())
    }

    @JvmStatic
    fun hasAudioPermission(context: Context?): Boolean {
        return allGranted(context, getAudioPermissions())
    }

    /**
     * Returns only the permissions actually needed for the given things'
     * attachments. On Android 12 and below returns
     * `READ_EXTERNAL_STORAGE`; on Android 13+ returns the subset of
     * `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, and
     * `READ_MEDIA_AUDIO` that matches the attachment types present.
     */
    @JvmStatic
    fun getRequiredPermissionsForThings(things: List<Thing?>?): Array<String?>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        var needsImage = false
        var needsVideo = false
        var needsAudio = false
        for (thing in things!!) {
            val attachment: String? = thing!!.attachment
            if (!AttachmentHelper.isValidForm(attachment)) continue
            val parts = attachment!!.split(AttachmentHelper.SIGNAL!!.toRegex()).toTypedArray()
            for (i in 1 until parts.size) {
                val type = parts[i][0]
                when (type) {
                    '0' -> needsImage = true
                    '1' -> needsVideo = true
                    '2' -> needsAudio = true
                }
            }
        }

        val perms = ArrayList<String?>(3)
        if (needsImage) perms.add(Manifest.permission.READ_MEDIA_IMAGES)
        if (needsVideo) perms.add(Manifest.permission.READ_MEDIA_VIDEO)
        if (needsAudio) perms.add(Manifest.permission.READ_MEDIA_AUDIO)

        if (perms.isEmpty()) {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
        }
        return perms.toTypedArray()
    }

    /**
     * Returns the union of all media read permissions appropriate for the
     * running platform.
     */
    @Deprecated(
        "Asking for image, video and audio permissions together is intrusive on Android 13+ and a denial on any one type currently aborts unrelated flows. Use getImagePermissions, getVideoPermissions or getAudioPermissions for new code, and consider ActivityResultContracts.PickVisualMedia or SAF (ACTION_OPEN_DOCUMENT) which need no permission at all."
    )
    @JvmStatic
    fun getStoragePermissions(): Array<String?>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO
            )
        }
        return arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /**
     * Returns true when the app holds at least one media read permission. Older
     * call sites used to require all three on API 33+, which meant denying audio
     * access also broke image-attachment loading. The relaxed check is enough
     * for code paths that load whichever attachments happen to exist.
     */
    @JvmStatic
    fun hasStoragePermission(context: Context?): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return hasImagePermission(context)
                    || hasVideoPermission(context)
                    || hasAudioPermission(context)
        }
        return ContextCompat.checkSelfPermission(context!!,
                Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    @JvmStatic
    fun shouldRequestPermissionWhenLoadingThings(things: List<Thing?>?): Boolean {
        for (thing in things!!) {
            if (AttachmentHelper.isValidForm(thing!!.attachment)) {
                return true
            }
        }
        return false
    }

    @JvmStatic
    private fun allGranted(context: Context?, permissions: Array<String?>?): Boolean {
        for (perm in permissions!!) {
            if (ContextCompat.checkSelfPermission(context!!, perm!!) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }
}
