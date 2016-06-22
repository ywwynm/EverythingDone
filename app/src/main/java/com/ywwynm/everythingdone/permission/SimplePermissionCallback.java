package com.ywwynm.everythingdone.permission;

import android.widget.Toast;

import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.activities.EverythingDoneBaseActivity;

/**
 * Created by ywwynm on 2016/5/21.
 * simple permission callback
 */
public class SimplePermissionCallback implements PermissionCallback {

    private EverythingDoneBaseActivity mActivity;

    public SimplePermissionCallback(EverythingDoneBaseActivity activity) {
        mActivity = activity;
    }

    @Override
    public void onGranted() {

    }

    @Override
    public void onDenied() {
        Toast.makeText(mActivity, R.string.error_permission_denied, Toast.LENGTH_LONG).show();
    }
}
