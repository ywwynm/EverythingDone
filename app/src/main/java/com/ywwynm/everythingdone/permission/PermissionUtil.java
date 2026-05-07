package com.ywwynm.everythingdone.permission;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.content.ContextCompat;

import com.ywwynm.everythingdone.helpers.AttachmentHelper;
import com.ywwynm.everythingdone.model.Thing;

import java.util.List;

/**
 * Created by ywwynm on 2016/7/8.
 * utils for Permission.
 *
 * <p>API 33+ uses split media permissions ({@code READ_MEDIA_IMAGES},
 * {@code READ_MEDIA_VIDEO}, {@code READ_MEDIA_AUDIO}). Older code that asked for
 * "any media access" via {@link #getStoragePermissions()} should migrate to the
 * type-specific helpers below — denying audio access should not block image
 * attachment loading, and vice versa.
 */
public class PermissionUtil {

    public static String[] getImagePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[] { Manifest.permission.READ_MEDIA_IMAGES };
        }
        return new String[] { Manifest.permission.READ_EXTERNAL_STORAGE };
    }

    public static String[] getVideoPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[] { Manifest.permission.READ_MEDIA_VIDEO };
        }
        return new String[] { Manifest.permission.READ_EXTERNAL_STORAGE };
    }

    public static String[] getAudioPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[] { Manifest.permission.READ_MEDIA_AUDIO };
        }
        return new String[] { Manifest.permission.READ_EXTERNAL_STORAGE };
    }

    public static boolean hasImagePermission(Context context) {
        return allGranted(context, getImagePermissions());
    }

    public static boolean hasVideoPermission(Context context) {
        return allGranted(context, getVideoPermissions());
    }

    public static boolean hasAudioPermission(Context context) {
        return allGranted(context, getAudioPermissions());
    }

    /**
     * Returns the union of all media read permissions appropriate for the
     * running platform.
     *
     * @deprecated Asking for image, video and audio permissions together is
     * intrusive on Android 13+ and a denial on any one type currently aborts
     * unrelated flows. Use {@link #getImagePermissions()},
     * {@link #getVideoPermissions()} or {@link #getAudioPermissions()} for
     * new code, and consider {@code ActivityResultContracts.PickVisualMedia} or
     * SAF ({@code ACTION_OPEN_DOCUMENT}) which need no permission at all.
     */
    @Deprecated
    public static String[] getStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[] {
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO
            };
        }
        return new String[] { Manifest.permission.READ_EXTERNAL_STORAGE };
    }

    /**
     * Returns true when the app holds at least one media read permission. Older
     * call sites used to require all three on API 33+, which meant denying audio
     * access also broke image-attachment loading. The relaxed check is enough
     * for code paths that load whichever attachments happen to exist.
     */
    public static boolean hasStoragePermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return hasImagePermission(context)
                    || hasVideoPermission(context)
                    || hasAudioPermission(context);
        }
        return ContextCompat.checkSelfPermission(context,
                Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean shouldRequestPermissionWhenLoadingThings(List<Thing> things) {
        for (Thing thing : things) {
            if (AttachmentHelper.isValidForm(thing.getAttachment())) {
                return true;
            }
        }
        return false;
    }

    private static boolean allGranted(Context context, String[] permissions) {
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(context, perm)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
}
