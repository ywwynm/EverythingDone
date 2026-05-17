package com.ywwynm.everythingdone.helpers;

import android.app.Activity;

import com.ywwynm.everythingdone.fragments.PatternLockDialogFragment;
import com.ywwynm.everythingdone.utils.DeviceUtil;

/**
 * Created by ywwynm on 2016/6/21.
 * A Helper class for authentication such as pattern or fingerprint.
 */
public class AuthenticationHelper {

    public static final String TAG = "AuthenticationHelper";

    private AuthenticationHelper() {}

    public static void authenticate(
            Activity activity, int accentColor, String title, String correctPassword,
            AuthenticationCallback callback) {
        authenticate(activity,
                com.ywwynm.everythingdone.model.ThingBackground.pure(accentColor),
                title, correctPassword, callback);
    }

    /**
     * Phase 8: ThingBackground-aware authenticate so the fallback pattern-lock
     * dialog's title / right-button render gradient when the source thing has
     * a GRADIENT background. BiometricPrompt is a system UI that can't accept
     * a shader, so it just receives the title string and ignores accent.
     */
    public static void authenticate(
            Activity activity, com.ywwynm.everythingdone.model.ThingBackground accent,
            String title, String correctPassword, AuthenticationCallback callback) {
        if (correctPassword == null) {
            callback.onAuthenticated();
            return;
        }

        FingerprintHelper.getInstance()
                .tryToAuthenticatingByFingerprint(
                        activity, accent, title, correctPassword, callback);
    }

    private static void authenticateByPattern(
            Activity activity, int accentColor, String title, String correctPassword,
            AuthenticationCallback callback) {
        final PatternLockDialogFragment pldf = new PatternLockDialogFragment();
        pldf.setAccentColor(accentColor);
        pldf.setType(PatternLockDialogFragment.TYPE_VALIDATE);
        pldf.setValidateTitle(title);
        pldf.setCorrectPassword(correctPassword);
        pldf.setAuthenticationCallback(callback);
        pldf.show(activity.getFragmentManager(), PatternLockDialogFragment.TAG);
    }

    public interface AuthenticationCallback {
        void onAuthenticated();
        void onCancel();
    }

}
