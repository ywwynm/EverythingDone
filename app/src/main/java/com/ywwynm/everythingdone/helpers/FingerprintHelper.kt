@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.helpers

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.fragments.PatternLockDialogFragment

import java.io.IOException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.UnrecoverableKeyException
import java.security.cert.CertificateException
import java.util.concurrent.Executor

import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Created by ywwynm on 2016/6/21.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 */
open class FingerprintHelper private constructor(context: Context?) {

    private var mContext: Context? = context!!.getApplicationContext()

    private var mKeyguardManager: KeyguardManager? = null
    private var mKeyStore: KeyStore? = null
    private var mKeyGenerator: KeyGenerator? = null
    private var mCipher: Cipher? = null

    init {
        mKeyguardManager = context!!.getSystemService(
                Context.KEYGUARD_SERVICE) as KeyguardManager
        try {
            mKeyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            mKeyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            mCipher = Cipher.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES + "/" +
                    KeyProperties.BLOCK_MODE_CBC + "/" +
                    KeyProperties.ENCRYPTION_PADDING_PKCS7)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    open fun supportFingerprint(): Boolean {
        val bm: BiometricManager = BiometricManager.from(mContext!!)
        return bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
    }

    open fun hasSystemFingerprintSet(): Boolean {
        return mKeyguardManager != null && mKeyguardManager!!.isKeyguardSecure()
    }

    open fun hasFingerprintRegistered(): Boolean {
        val bm: BiometricManager = BiometricManager.from(mContext!!)
        val result: Int = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    open fun isFingerprintReady(): Boolean {
        return supportFingerprint() && hasSystemFingerprintSet()
    }

    open fun isFingerprintEnabledInEverythingDone(): Boolean {
        val sp: SharedPreferences = mContext!!.getSharedPreferences(
                Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE)
        return sp.getBoolean(Def.Meta.KEY_USE_FINGERPRINT, false)
    }

    open fun createFingerprintKeyForEverythingDone() {
        try {
            mKeyStore!!.load(null)
            mKeyGenerator!!.init(KeyGenParameterSpec.Builder(FINGERPRINT_KEY_NAME,
                    KeyProperties.PURPOSE_ENCRYPT or
                            KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                    .setUserAuthenticationRequired(true)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                    .build())
            mKeyGenerator!!.generateKey()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initFingerprintCipher(): Boolean {
        try {
            mKeyStore!!.load(null)
            val key: SecretKey? = mKeyStore!!.getKey(FINGERPRINT_KEY_NAME, null) as SecretKey?
            if (key == null) {
                return false
            }
            mCipher!!.init(Cipher.ENCRYPT_MODE, key)
            return true
        } catch (e: KeyPermanentlyInvalidatedException) {
            return false
        } catch (e: KeyStoreException) {
            throw RuntimeException("Failed to init Cipher", e)
        } catch (e: CertificateException) {
            throw RuntimeException("Failed to init Cipher", e)
        } catch (e: UnrecoverableKeyException) {
            throw RuntimeException("Failed to init Cipher", e)
        } catch (e: IOException) {
            throw RuntimeException("Failed to init Cipher", e)
        } catch (e: NoSuchAlgorithmException) {
            throw RuntimeException("Failed to init Cipher", e)
        } catch (e: InvalidKeyException) {
            throw RuntimeException("Failed to init Cipher", e)
        }
    }

    open fun tryToAuthenticatingByFingerprint(
            activity: Activity?, accentColor: Int, title: String?, correctPassword: String?,
            callback: AuthenticationHelper.AuthenticationCallback?) {
        tryToAuthenticatingByFingerprint(activity,
                com.ywwynm.everythingdone.model.ThingBackground.pure(accentColor),
                title, correctPassword, callback)
    }

    /** Phase 8: ThingBackground-aware overload — gradient flows into the pattern-lock fallback. */
    open fun tryToAuthenticatingByFingerprint(
            activity: Activity?, accent: com.ywwynm.everythingdone.model.ThingBackground?,
            title: String?, correctPassword: String?,
            callback: AuthenticationHelper.AuthenticationCallback?) {
        if (isFingerprintReady() && isFingerprintEnabledInEverythingDone() && initFingerprintCipher()) {
            authenticateWithBiometricPrompt(activity, title, callback)
        } else {
            showPatternLock(activity, accent, title, correctPassword, callback)
        }
    }

    private fun authenticateWithBiometricPrompt(
            activity: Activity?, title: String?,
            callback: AuthenticationHelper.AuthenticationCallback?) {
        val executor: Executor = ContextCompat.getMainExecutor(mContext!!)
        val promptInfo: BiometricPrompt.PromptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title!!)
                .setNegativeButtonText(mContext!!.getString(android.R.string.cancel))
                .build()

        val prompt: BiometricPrompt = BiometricPrompt(activity as FragmentActivity, executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                            result: BiometricPrompt.AuthenticationResult) {
                        callback!!.onAuthenticated()
                    }

                    override fun onAuthenticationFailed() {
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                                && errorCode != BiometricPrompt.ERROR_USER_CANCELED) {
                            // Biometric error fallback: use a fixed brand-colour pattern lock.
                            showPatternLock(activity,
                                    com.ywwynm.everythingdone.model.ThingBackground.pure(
                                            ContextCompat.getColor(activity, R.color.blue_deep)),
                                    title, "", callback)
                        }
                    }
                })

        prompt.authenticate(promptInfo,
                BiometricPrompt.CryptoObject(mCipher!!))
    }

    private fun showPatternLock(
            activity: Activity?, accent: com.ywwynm.everythingdone.model.ThingBackground?,
            title: String?, correctPassword: String?,
            callback: AuthenticationHelper.AuthenticationCallback?) {
        val pldf: PatternLockDialogFragment = PatternLockDialogFragment()
        // Phase 8: prefer the ThingBackground setter so GRADIENT renders on
        // the dialog's title + right-button. Falls back to int when caller
        // only supplied a representative int (wrapped as PURE).
        if (accent != null) pldf.setAccentBackground(accent)
        pldf.setType(PatternLockDialogFragment.TYPE_VALIDATE)
        pldf.setValidateTitle(title)
        pldf.setCorrectPassword(correctPassword)
        pldf.setAuthenticationCallback(callback)
        pldf.show(activity!!.getFragmentManager(), PatternLockDialogFragment.TAG)
    }

    companion object {
        const val TAG: String = "FingerprintHelper"

        private const val FINGERPRINT_KEY_NAME: String = "everythingdone_fingerprint_key"

        private const val ANDROID_KEYSTORE: String = "AndroidKeyStore"

        @JvmField
        var sFingerprintHelper: FingerprintHelper? = null

        @JvmStatic
        fun getInstance(): FingerprintHelper? {
            if (sFingerprintHelper == null) {
                synchronized(FingerprintHelper::class.java) {
                    if (sFingerprintHelper == null) {
                        sFingerprintHelper = FingerprintHelper(App.getApp())
                    }
                }
            }
            return sFingerprintHelper
        }
    }
}
