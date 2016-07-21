package com.ywwynm.everythingdone.permission;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.support.v4.content.ContextCompat;

/**
 * Created by ywwynm on 2016/7/8.
 * utils for Permission
 */
public class PermissionUtil {

    public static boolean hasStoragePermission(Context packageContext) {
        return ContextCompat.checkSelfPermission(
                packageContext, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

}
