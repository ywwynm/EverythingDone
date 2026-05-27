package com.ywwynm.everythingdone.activities

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import android.util.SparseArray
import android.view.View

import com.ywwynm.everythingdone.permission.PermissionCallback
import com.ywwynm.everythingdone.utils.LocaleUtil

/**
 * Created by ywwynm on 2015/6/4.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A base Activity class to reduce same codes in different subclasses.
 */
abstract class EverythingDoneBaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleUtil.attachBaseContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep AppCompat's per-app locale state in sync before inflating views.
        LocaleUtil.changeLanguage()

        beforeSetContentView()

        setContentView(getLayoutResource())

        beforeInit()
        init()
    }

    @Suppress("UNCHECKED_CAST")
    protected fun <T : View?> f(@IdRes id: Int): T {
        return findViewById<View>(id) as T
    }

    @Suppress("UNCHECKED_CAST")
    protected fun <T : View?> f(v: View, @IdRes id: Int): T {
        return v.findViewById<View>(id) as T
    }

    protected open fun beforeSetContentView() {}

    @LayoutRes
    protected abstract fun getLayoutResource(): Int

    protected open fun beforeInit() {}

    protected open fun init() {
        initMembers()
        findViews()
        initUI()
        setActionbar()
        setEvents()
    }

    protected abstract fun initMembers()

    protected abstract fun findViews()

    protected abstract fun initUI()

    protected abstract fun setActionbar()

    protected abstract fun setEvents()

    private var mCallbacks: SparseArray<PermissionCallback>? = null

    open fun doWithPermissionChecked(
        permissionCallback: PermissionCallback, requestCode: Int, vararg permissions: String?
    ) {
        if (mCallbacks == null) {
            mCallbacks = SparseArray()
        }
        for (permission in permissions) {
            val pg = ContextCompat.checkSelfPermission(this, permission!!)
            if (pg != PackageManager.PERMISSION_GRANTED) {
                mCallbacks!!.put(requestCode, permissionCallback)
                @Suppress("UNCHECKED_CAST")
                ActivityCompat.requestPermissions(this, permissions as Array<String>, requestCode)
                return
            }
        }

        permissionCallback.onGranted()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (mCallbacks == null) return
        val callback: PermissionCallback = mCallbacks!!.get(requestCode) ?: return
        for (grantResult in grantResults) {
            if (grantResult != PackageManager.PERMISSION_GRANTED) {
                callback.onDenied()
                return
            }
        }
        callback.onGranted()
    }

    companion object {
        const val TAG: String = "EverythingDoneBaseActivity"
    }
}
