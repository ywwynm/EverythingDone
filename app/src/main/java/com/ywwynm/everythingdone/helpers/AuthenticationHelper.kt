@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.helpers

import androidx.fragment.app.FragmentActivity

import com.ywwynm.everythingdone.fragments.PatternLockDialogFragment

/**
 * Created by ywwynm on 2016/6/21.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A Helper class for authentication such as pattern or fingerprint.
 */
object AuthenticationHelper {

    const val TAG: String = "AuthenticationHelper"

    @JvmStatic
    fun authenticate(
            activity: FragmentActivity?, accentColor: Int, title: String?, correctPassword: String?,
            callback: AuthenticationCallback?) {
        authenticate(activity,
                com.ywwynm.everythingdone.model.ThingBackground.pure(accentColor),
                title, correctPassword, callback)
    }

    /**
     * Phase 8: ThingBackground-aware authenticate so the fallback pattern-lock
     * dialog's title / right-button render gradient when the source thing has
     * a GRADIENT background. BiometricPrompt is a system UI that can't accept
     * a shader, so it just receives the title string and ignores accent.
     */
    @JvmStatic
    fun authenticate(
            activity: FragmentActivity?, accent: com.ywwynm.everythingdone.model.ThingBackground?,
            title: String?, correctPassword: String?, callback: AuthenticationCallback?) {
        if (correctPassword == null) {
            callback!!.onAuthenticated()
            return
        }

        FingerprintHelper.getInstance()!!
                .tryToAuthenticatingByFingerprint(
                        activity, accent, title, correctPassword, callback)
    }

    private fun authenticateByPattern(
            activity: FragmentActivity?, accentColor: Int, title: String?, correctPassword: String?,
            callback: AuthenticationCallback?) {
        val pldf = PatternLockDialogFragment()
        pldf.setAccentBackground(
                com.ywwynm.everythingdone.model.ThingBackground.pure(accentColor))
        pldf.setType(PatternLockDialogFragment.TYPE_VALIDATE)
        pldf.setValidateTitle(title)
        pldf.setCorrectPassword(correctPassword)
        pldf.setAuthenticationCallback(callback)
        pldf.show(activity!!.supportFragmentManager, PatternLockDialogFragment.TAG)
    }

    interface AuthenticationCallback {
        fun onAuthenticated()
        fun onCancel()
    }

}
