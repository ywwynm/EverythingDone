package com.ywwynm.everythingdone.utils;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;

/**
 * Created by ywwynm on 2016/4/3.
 * permission utils for marshmallow
 */
public class PermissionUtil {

    public interface Callback {
        void onGranted();
    }

    public static void doWithPermissionChecked(
            Callback callback, Activity activity, int requestCode, String... permissions) {
        if (VersionUtil.hasMarshmallowApi()) {
            for (String permission : permissions) {
                int pg = ContextCompat.checkSelfPermission(activity, permission);
                if (pg != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(activity, permissions, requestCode);
                    return;
                }
            }
        }
        callback.onGranted();
    }

}
