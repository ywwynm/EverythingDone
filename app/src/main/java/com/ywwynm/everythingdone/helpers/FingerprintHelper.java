package com.ywwynm.everythingdone.helpers;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.fragments.PatternLockDialogFragment;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.concurrent.Executor;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

public class FingerprintHelper {

    public static final String TAG = "FingerprintHelper";

    private static final String FINGERPRINT_KEY_NAME = "everythingdone_fingerprint_key";

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    private static FingerprintHelper sFingerprintHelper;

    private Context mContext;

    private KeyguardManager mKeyguardManager;
    private KeyStore mKeyStore;
    private KeyGenerator mKeyGenerator;
    private Cipher mCipher;

    private FingerprintHelper(Context context) {
        mContext = context.getApplicationContext();
        mKeyguardManager = (KeyguardManager) context.getSystemService(
                Context.KEYGUARD_SERVICE);
        try {
            mKeyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            mKeyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
            mCipher = Cipher.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES + "/"
                    + KeyProperties.BLOCK_MODE_CBC + "/"
                    + KeyProperties.ENCRYPTION_PADDING_PKCS7);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static FingerprintHelper getInstance() {
        if (sFingerprintHelper == null) {
            synchronized (FingerprintHelper.class) {
                if (sFingerprintHelper == null) {
                    sFingerprintHelper = new FingerprintHelper(App.getApp());
                }
            }
        }
        return sFingerprintHelper;
    }

    public boolean supportFingerprint() {
        BiometricManager bm = BiometricManager.from(mContext);
        return bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                == BiometricManager.BIOMETRIC_SUCCESS;
    }

    public boolean hasSystemFingerprintSet() {
        return mKeyguardManager != null && mKeyguardManager.isKeyguardSecure();
    }

    public boolean hasFingerprintRegistered() {
        BiometricManager bm = BiometricManager.from(mContext);
        int result = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        return result == BiometricManager.BIOMETRIC_SUCCESS;
    }

    public boolean isFingerprintReady() {
        return supportFingerprint() && hasSystemFingerprintSet();
    }

    public boolean isFingerprintEnabledInEverythingDone() {
        SharedPreferences sp = mContext.getSharedPreferences(
                Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(Def.Meta.KEY_USE_FINGERPRINT, false);
    }

    public void createFingerprintKeyForEverythingDone() {
        try {
            mKeyStore.load(null);
            mKeyGenerator.init(new KeyGenParameterSpec.Builder(FINGERPRINT_KEY_NAME,
                    KeyProperties.PURPOSE_ENCRYPT |
                            KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                    .setUserAuthenticationRequired(true)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                    .build());
            mKeyGenerator.generateKey();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean initFingerprintCipher() {
        try {
            mKeyStore.load(null);
            SecretKey key = (SecretKey) mKeyStore.getKey(FINGERPRINT_KEY_NAME, null);
            if (key == null) {
                return false;
            }
            mCipher.init(Cipher.ENCRYPT_MODE, key);
            return true;
        } catch (KeyPermanentlyInvalidatedException e) {
            return false;
        } catch (KeyStoreException | CertificateException | UnrecoverableKeyException | IOException
                | NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("Failed to init Cipher", e);
        }
    }

    public void tryToAuthenticatingByFingerprint(
            Activity activity, int accentColor, String title, String correctPassword,
            AuthenticationHelper.AuthenticationCallback callback) {
        if (isFingerprintReady() && isFingerprintEnabledInEverythingDone() && initFingerprintCipher()) {
            authenticateWithBiometricPrompt(activity, title, callback);
        } else {
            showPatternLock(activity, accentColor, title, correctPassword, callback);
        }
    }

    private void authenticateWithBiometricPrompt(
            Activity activity, String title,
            AuthenticationHelper.AuthenticationCallback callback) {
        Executor executor = ContextCompat.getMainExecutor(mContext);
        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setNegativeButtonText(mContext.getString(android.R.string.cancel))
                .build();

        BiometricPrompt prompt = new BiometricPrompt((FragmentActivity) activity, executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            BiometricPrompt.AuthenticationResult result) {
                        callback.onAuthenticated();
                    }

                    @Override
                    public void onAuthenticationFailed() {
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errString) {
                        if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                                && errorCode != BiometricPrompt.ERROR_USER_CANCELED) {
                            showPatternLock(activity, ContextCompat.getColor(activity,
                                    R.color.blue_deep), title, "", callback);
                        }
                    }
                });

        prompt.authenticate(promptInfo,
                new BiometricPrompt.CryptoObject(mCipher));
    }

    private void showPatternLock(
            Activity activity, int accentColor, String title,
            String correctPassword, AuthenticationHelper.AuthenticationCallback callback) {
        final PatternLockDialogFragment pldf = new PatternLockDialogFragment();
        pldf.setAccentColor(accentColor);
        pldf.setType(PatternLockDialogFragment.TYPE_VALIDATE);
        pldf.setValidateTitle(title);
        pldf.setCorrectPassword(correctPassword);
        pldf.setAuthenticationCallback(callback);
        pldf.show(activity.getFragmentManager(), PatternLockDialogFragment.TAG);
    }
}
