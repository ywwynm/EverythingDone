package com.ywwynm.everythingdone.helpers;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.fragments.FingerprintDialogFragment;
import com.ywwynm.everythingdone.fragments.PatternLockDialogFragment;
import com.ywwynm.everythingdone.utils.DeviceUtil;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Created by ywwynm on 2016/4/29.
 * helper for fingerprint
 */
@TargetApi(Build.VERSION_CODES.M)
public class FingerprintHelper extends FingerprintManager.AuthenticationCallback {

    public static final String TAG = "FingerprintHelper";

    private static final String FINGERPRINT_KEY_NAME = "everythingdone_fingerprint_key";

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    private static FingerprintHelper sFingerprintHelper;

    private Context mContext;

    private FingerprintManager mFingerprintManager;
    private KeyguardManager mKeyguardManager;
    private KeyStore mKeyStore;
    private KeyGenerator mKeyGenerator;
    private Cipher mCipher;

    private FingerprintCallback mFingerprintCallback;
    private CancellationSignal mCancellationSignal;

    public interface FingerprintCallback {
        void onAuthenticated();
        void onFailed();
        void onError();
    }

    public void setFingerprintCallback(FingerprintCallback fingerprintCallback) {
        mFingerprintCallback = fingerprintCallback;
    }

    private FingerprintHelper(Context context) {
        mContext = context.getApplicationContext();
        if (DeviceUtil.hasMarshmallowApi()) {
            mFingerprintManager = (FingerprintManager) context.getSystemService(
                    Context.FINGERPRINT_SERVICE);
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
        return mFingerprintManager != null && mFingerprintManager.isHardwareDetected();
    }

    public boolean hasSystemFingerprintSet() {
        return mKeyguardManager != null && mKeyguardManager.isKeyguardSecure();
    }

    public boolean hasFingerprintRegistered() {
        return mFingerprintManager != null && mFingerprintManager.hasEnrolledFingerprints();
    }

    public boolean isFingerprintReady() {
        return supportFingerprint() && hasSystemFingerprintSet() && hasFingerprintRegistered();
    }

    public boolean isFingerprintEnabledInEverythingDone() {
        SharedPreferences sp = mContext.getSharedPreferences(
                Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(Def.Meta.KEY_USE_FINGERPRINT, false);
    }

    @TargetApi(Build.VERSION_CODES.M)
    public void createFingerprintKeyForEverythingDone() {
        // The enrolling flow for fingerprint. This is where you ask the user to set up fingerprint
        // for your flow. Use of keys is necessary if you need to know if the set of
        // enrolled fingerprints has changed.
        try {
            mKeyStore.load(null);
            // Set the alias of the entry in Android KeyStore where the key will appear
            // and the constrains (purposes) in the constructor of the Builder
            mKeyGenerator.init(new KeyGenParameterSpec.Builder(FINGERPRINT_KEY_NAME,
                    KeyProperties.PURPOSE_ENCRYPT |
                            KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                    // Require the user to authenticate with a fingerprint to authorize every use
                    // of the key
                    .setUserAuthenticationRequired(true)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                    .build());
            mKeyGenerator.generateKey();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean initFingerprintCipher() {
        if (!DeviceUtil.hasMarshmallowApi()) {
            return false;
        }
        try {
            mKeyStore.load(null);
            SecretKey key = (SecretKey) mKeyStore.getKey(FINGERPRINT_KEY_NAME, null);
            if (key == null) { // Fingerprint? What is that?
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
        // Set up the crypto object for later. The object will be authenticated by use
        // of the fingerprint.
        if (isFingerprintReady() && isFingerprintEnabledInEverythingDone() && initFingerprintCipher()) {
            final FingerprintDialogFragment adf = new FingerprintDialogFragment();
            adf.setAccentColor(accentColor);
            adf.setTitle(title);
            adf.setCryptoObject(new FingerprintManager.CryptoObject(mCipher));
            adf.setAuthenticationCallback(callback);
            // Show the fingerprint dialog. The user has the option to use the fingerprint with
            // crypto, or you can fall back to using a pattern.
            adf.show(activity.getFragmentManager(), FingerprintDialogFragment.TAG);
        } else {
            // This happens if the lock screen has been disabled or or a fingerprint got
            // enrolled. Thus show the dialog to authenticate with their pattern.
            final PatternLockDialogFragment pldf = new PatternLockDialogFragment();
            pldf.setAccentColor(accentColor);
            pldf.setType(PatternLockDialogFragment.TYPE_VALIDATE);
            pldf.setValidateTitle(title);
            pldf.setCorrectPassword(correctPassword);
            pldf.setAuthenticationCallback(callback);
            pldf.show(activity.getFragmentManager(), PatternLockDialogFragment.TAG);
        }
    }

    public void startListening(FingerprintManager.CryptoObject cryptoObject) {
        if (!isFingerprintReady()) {
            return;
        }
        mCancellationSignal = new CancellationSignal();
        mFingerprintManager.authenticate(cryptoObject, mCancellationSignal, 0, this, null);
    }

    public void stopListening() {
        if (mCancellationSignal != null) {
            mCancellationSignal.cancel();
            mCancellationSignal = null;
        }
    }

    @Override
    public void onAuthenticationSucceeded(FingerprintManager.AuthenticationResult result) {
        if (mFingerprintCallback != null) {
            mFingerprintCallback.onAuthenticated();
        }
    }

    @Override
    public void onAuthenticationFailed() {
        if (mFingerprintCallback != null) {
            mFingerprintCallback.onFailed();
        }
    }

    @Override
    public void onAuthenticationError(int errorCode, CharSequence errString) {
        if (mFingerprintCallback != null) {
            mFingerprintCallback.onError();
        }
    }
}
