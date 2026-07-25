@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.helpers

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider

import com.google.gson.Gson
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.AboutActivity
import com.ywwynm.everythingdone.fragments.AlertDialogFragment
import com.ywwynm.everythingdone.fragments.DebugUpdateDialogFragment
import com.ywwynm.everythingdone.fragments.DebugUpdateDownloadDialogFragment
import com.ywwynm.everythingdone.fragments.LoadingDialogFragment

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

open class DebugApkUpdateHelper(private val mActivity: AboutActivity) {

    private var mCheckingDialog: LoadingDialogFragment? = null
    private var mDownloadDialog: DebugUpdateDownloadDialogFragment? = null
    private var mPendingInstallFile: File? = null
    private var mWaitingForInstallPermission: Boolean = false

    @Volatile
    private var mDownloadCanceled: Boolean = false
    @Volatile
    private var mActiveConnection: HttpURLConnection? = null
    @Volatile
    private var mActivePartFile: File? = null

    open fun checkForUpdate() {
        if (!BuildConfig.DEBUG || BuildConfig.DEBUG_UPDATE_METADATA_URL.isBlank()) {
            Toast.makeText(
                mActivity,
                R.string.debug_update_source_not_configured,
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        showCheckingDialog()
        Thread {
            try {
                val json = readText(BuildConfig.DEBUG_UPDATE_METADATA_URL)
                val info = Gson().fromJson(json, DebugApkUpdateInfo::class.java)
                validateMetadata(info)
                runOnUi {
                    dismissCheckingDialog()
                    if (info.debugUpdateCode <= currentDebugUpdateCode()) {
                        Toast.makeText(
                            mActivity,
                            R.string.debug_update_already_latest,
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        showUpdateAvailableDialog(info)
                    }
                }
            } catch (e: Exception) {
                runOnUi {
                    dismissCheckingDialog()
                    showAlert(
                        R.string.debug_update_check_failed_title,
                        getErrorMessage(e, R.string.debug_update_metadata_invalid)
                    )
                }
            }
        }.start()
    }

    open fun continuePendingInstallIfAllowed() {
        if (!mWaitingForInstallPermission) return
        val file = mPendingInstallFile ?: return
        if (canRequestPackageInstalls()) {
            mWaitingForInstallPermission = false
            launchInstaller(file)
        } else {
            mWaitingForInstallPermission = false
            Toast.makeText(
                mActivity,
                R.string.debug_update_install_permission_missing,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showCheckingDialog() {
        val dialog = LoadingDialogFragment()
        dialog.setTitle(mActivity.getString(R.string.debug_update_checking_title))
        dialog.setContent(mActivity.getString(R.string.debug_update_checking_content))
        dialog.setAccentBackground(App.defaultAccentBackground)
        mCheckingDialog = dialog
        dialog.show(mActivity.supportFragmentManager, LoadingDialogFragment.TAG)
    }

    private fun dismissCheckingDialog() {
        mCheckingDialog?.dismiss()
        mCheckingDialog = null
    }

    private fun showUpdateAvailableDialog(info: DebugApkUpdateInfo) {
        val dialog = DebugUpdateDialogFragment()
        dialog.setUpdateInfo(info, formatBytes(info.sizeBytes), formatPublishedAt(info.publishedAt))
        dialog.setOnDownloadClickListener(object : DebugUpdateDialogFragment.OnDownloadClickListener {
            override fun onDownloadClicked(info: DebugApkUpdateInfo) {
                downloadAndInstall(info)
            }
        })
        dialog.show(mActivity.supportFragmentManager, DebugUpdateDialogFragment.TAG)
    }

    private fun downloadAndInstall(info: DebugApkUpdateInfo) {
        mDownloadCanceled = false
        val dialog = DebugUpdateDownloadDialogFragment()
        dialog.setAccentBackground(App.defaultAccentBackground)
        dialog.setOnCancelDownloadListener(object :
            DebugUpdateDownloadDialogFragment.OnCancelDownloadListener {
            override fun onCancelDownload() {
                cancelDownload()
            }
        })
        mDownloadDialog = dialog
        dialog.show(mActivity.supportFragmentManager, DebugUpdateDownloadDialogFragment.TAG)
        dialog.updateProgress(0L, info.sizeBytes, formatBytes(0L), formatBytes(info.sizeBytes), formatBytes(0L))

        Thread {
            val partFile: File
            val apkFile: File
            try {
                val cacheDir = getUpdateCacheDir()
                cleanPartialFiles(cacheDir)
                val baseName = "app-debug-${info.debugUpdateCode}"
                partFile = File(cacheDir, "$baseName.apk.part")
                apkFile = File(cacheDir, "$baseName.apk")
                if (apkFile.exists() && !apkFile.delete()) {
                    throw IllegalStateException("Cannot replace existing APK.")
                }
                mActivePartFile = partFile

                downloadToPartFile(info, partFile)
                if (mDownloadCanceled) throw DownloadCanceledException()
                if (!partFile.renameTo(apkFile)) {
                    throw IllegalStateException("Cannot finalize downloaded APK.")
                }

                if (!sha256Hex(apkFile).equals(info.sha256, ignoreCase = false)) {
                    apkFile.delete()
                    throw VerificationException()
                }
                if (!isInstallableUpdate(apkFile)) {
                    apkFile.delete()
                    throw InvalidPackageException()
                }
                cleanVerifiedApks(cacheDir, apkFile)

                runOnUi {
                    dismissDownloadDialog()
                    mPendingInstallFile = apkFile
                    requestPermissionOrInstall(apkFile)
                }
            } catch (_: DownloadCanceledException) {
                mActivePartFile?.delete()
                runOnUi {
                    dismissDownloadDialog()
                    Toast.makeText(
                        mActivity,
                        R.string.debug_update_download_canceled,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (_: VerificationException) {
                mActivePartFile?.delete()
                runOnUi {
                    dismissDownloadDialog()
                    showAlert(
                        R.string.debug_update_verification_failed_title,
                        mActivity.getString(R.string.debug_update_verification_failed_content)
                    )
                }
            } catch (_: InvalidPackageException) {
                mActivePartFile?.delete()
                runOnUi {
                    dismissDownloadDialog()
                    showAlert(
                        R.string.debug_update_package_invalid_title,
                        mActivity.getString(R.string.debug_update_package_invalid_content)
                    )
                }
            } catch (e: Exception) {
                if (mDownloadCanceled) {
                    mActivePartFile?.delete()
                    runOnUi {
                        dismissDownloadDialog()
                        Toast.makeText(
                            mActivity,
                            R.string.debug_update_download_canceled,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    mActivePartFile?.delete()
                    runOnUi {
                        dismissDownloadDialog()
                        showAlert(
                            R.string.debug_update_download_failed_title,
                            getErrorMessage(e, R.string.debug_update_download_failed_content)
                        )
                    }
                }
            } finally {
                mActiveConnection = null
                mActivePartFile = null
            }
        }.start()
    }

    private fun cancelDownload() {
        mDownloadCanceled = true
        mActiveConnection?.disconnect()
    }

    private fun dismissDownloadDialog() {
        mDownloadDialog?.dismiss()
        mDownloadDialog = null
    }

    private fun downloadToPartFile(info: DebugApkUpdateInfo, partFile: File) {
        val connection = URL(info.apkUrl).openConnection() as HttpURLConnection
        mActiveConnection = connection
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.connect()
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("HTTP ${connection.responseCode}")
        }

        val total = if (info.sizeBytes > 0) info.sizeBytes else connection.contentLengthLong
        connection.inputStream.use { input ->
            FileOutputStream(partFile).use { output ->
                val buffer = ByteArray(128 * 1024)
                var downloaded = 0L
                var lastBytes = 0L
                var lastTime = SystemClock.elapsedRealtime()
                while (true) {
                    if (mDownloadCanceled) throw DownloadCanceledException()
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    downloaded += read.toLong()

                    val now = SystemClock.elapsedRealtime()
                    if (now - lastTime >= 400L || downloaded == total) {
                        val elapsed = (now - lastTime).coerceAtLeast(1L)
                        val speed = ((downloaded - lastBytes) * 1000L) / elapsed
                        lastBytes = downloaded
                        lastTime = now
                        runOnUi {
                            mDownloadDialog?.updateProgress(
                                downloaded,
                                total,
                                formatBytes(downloaded),
                                formatBytes(total),
                                formatBytes(speed)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun requestPermissionOrInstall(file: File) {
        if (canRequestPackageInstalls()) {
            launchInstaller(file)
            return
        }

        val dialog = AlertDialogFragment()
        dialog.setTitleBackground(App.defaultAccentBackground)
        dialog.setConfirmBackground(App.defaultAccentBackground)
        dialog.setTitle(mActivity.getString(R.string.debug_update_install_permission_title))
        dialog.setContent(mActivity.getString(R.string.debug_update_install_permission_content))
        dialog.setConfirmText(mActivity.getString(R.string.debug_update_open_settings))
        dialog.setConfirmListener(object : AlertDialogFragment.ConfirmListener {
            override fun onConfirm() {
                mWaitingForInstallPermission = true
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${mActivity.packageName}")
                )
                try {
                    mActivity.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    mWaitingForInstallPermission = false
                    showAlert(
                        R.string.debug_update_installer_failed_title,
                        mActivity.getString(R.string.debug_update_installer_failed_content, "")
                    )
                }
            }
        })
        dialog.show(mActivity.supportFragmentManager, AlertDialogFragment.TAG)
    }

    private fun launchInstaller(file: File) {
        try {
            val uri = FileProvider.getUriForFile(mActivity, mActivity.packageName, file)
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            intent.setData(uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            intent.putExtra(Intent.EXTRA_RETURN_RESULT, true)
            mActivity.startActivity(intent)
        } catch (e: Exception) {
            showAlert(
                R.string.debug_update_installer_failed_title,
                getErrorMessage(e, R.string.debug_update_installer_failed_content)
            )
        }
    }

    private fun validateMetadata(info: DebugApkUpdateInfo?) {
        if (info == null) throw IllegalArgumentException(mActivity.getString(R.string.debug_update_metadata_invalid))
        if (info.channel != "debug") throw IllegalArgumentException(mActivity.getString(R.string.debug_update_metadata_invalid))
        if (info.debugUpdateCode <= 0L) throw IllegalArgumentException(mActivity.getString(R.string.debug_update_metadata_invalid))
        if (info.versionName.isNullOrBlank()) throw IllegalArgumentException(mActivity.getString(R.string.debug_update_metadata_invalid))
        if (info.apkUrl.isNullOrBlank()) throw IllegalArgumentException(mActivity.getString(R.string.debug_update_metadata_invalid))
        val hash = info.sha256
        if (hash.isNullOrBlank() || !hash.matches(Regex("[0-9a-f]{64}"))) {
            throw IllegalArgumentException(mActivity.getString(R.string.debug_update_metadata_invalid))
        }
        if (info.sizeBytes <= 0L) throw IllegalArgumentException(mActivity.getString(R.string.debug_update_metadata_invalid))
        if (info.versionCode < currentAndroidVersionCode()) {
            throw IllegalArgumentException(mActivity.getString(R.string.debug_update_metadata_invalid))
        }
    }

    private fun isInstallableUpdate(apkFile: File): Boolean {
        val packageManager = mActivity.packageManager
        val archiveInfo = packageManager.getPackageArchiveInfo(
            apkFile.absolutePath,
            PackageManager.GET_ACTIVITIES
        ) ?: return false
        if (archiveInfo.packageName != mActivity.packageName) return false
        return getLongVersionCode(archiveInfo) >= currentAndroidVersionCode()
    }

    private fun canRequestPackageInstalls(): Boolean {
        return mActivity.packageManager.canRequestPackageInstalls()
    }

    private fun currentAndroidVersionCode(): Long {
        val info = mActivity.packageManager.getPackageInfo(mActivity.packageName, 0)
        return getLongVersionCode(info)
    }

    private fun getLongVersionCode(info: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    private fun currentDebugUpdateCode(): Long {
        return mActivity.getString(R.string.debug_update_code).toLongOrNull() ?: 0L
    }

    private fun getUpdateCacheDir(): File {
        val dir = File(mActivity.cacheDir, "debug-updates")
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("Cannot create update cache directory.")
        }
        return dir
    }

    private fun cleanPartialFiles(dir: File) {
        dir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".apk.part")) file.delete()
        }
    }

    private fun cleanVerifiedApks(dir: File, keep: File) {
        dir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".apk") && file.absolutePath != keep.absolutePath) {
                file.delete()
            }
        }
    }

    private fun readText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.connect()
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("HTTP ${connection.responseCode}")
        }
        return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun showAlert(titleRes: Int, content: String) {
        val dialog = AlertDialogFragment()
        dialog.setTitleBackground(App.defaultAccentBackground)
        dialog.setConfirmBackground(App.defaultAccentBackground)
        dialog.setShowCancel(false)
        dialog.setTitle(mActivity.getString(titleRes))
        dialog.setContent(content)
        dialog.setConfirmText(mActivity.getString(R.string.act_get_it))
        dialog.show(mActivity.supportFragmentManager, AlertDialogFragment.TAG)
    }

    private fun getErrorMessage(e: Exception, fallbackRes: Int): String {
        return e.message?.takeIf { it.isNotBlank() }
            ?: mActivity.getString(fallbackRes, "")
    }

    private fun formatPublishedAt(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return try {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                .withLocale(Locale.getDefault())
                .withZone(ZoneId.systemDefault())
                .format(Instant.parse(value))
        } catch (_: Exception) {
            value
        }
    }

    private fun formatBytes(bytes: Long): String {
        val safeBytes = bytes.coerceAtLeast(0L).toDouble()
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            safeBytes < kb -> "${safeBytes.toLong()} B"
            safeBytes < mb -> String.format(Locale.getDefault(), "%.1f KB", safeBytes / kb)
            safeBytes < gb -> String.format(Locale.getDefault(), "%.1f MB", safeBytes / mb)
            else -> String.format(Locale.getDefault(), "%.1f GB", safeBytes / gb)
        }
    }

    private fun runOnUi(action: () -> Unit) {
        mActivity.runOnUiThread {
            if (!mActivity.isFinishing) action()
        }
    }

    private class DownloadCanceledException : Exception()
    private class VerificationException : Exception()
    private class InvalidPackageException : Exception()
}
