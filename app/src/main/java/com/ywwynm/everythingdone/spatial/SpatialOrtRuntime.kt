package com.ywwynm.everythingdone.spatial

import ai.onnxruntime.OrtEnvironment
import android.content.Context

/**
 * ONNX Runtime Java 入口必须统一经过这里。
 *
 * 项目使用的 Java-only ORT jar 对固定版本 loader 做了最小补丁：Android 设置
 * onnxruntime.native.path 后也复用 ORT 原有的绝对路径分支。组件先由 Store 按绝对路径加载；
 * 初始化完成后立即恢复全局属性，不改变进程中其它调用方的环境。
 */
object SpatialOrtRuntime {

    @Volatile
    private var initialized = false

    @Synchronized
    fun environment(context: Context): OrtEnvironment {
        SpatialRuntimeStore.ensureLoaded(context)
        if (initialized) return OrtEnvironment.getEnvironment()

        val nativeDirectory = SpatialRuntimeStore.nativeLibraryDirectory(context).absolutePath
        val previousNativePath = System.getProperty(ORT_NATIVE_PATH_PROPERTY)
        try {
            System.setProperty(ORT_NATIVE_PATH_PROPERTY, nativeDirectory)
            return OrtEnvironment.getEnvironment().also {
                initialized = true
            }
        } finally {
            restoreProperty(ORT_NATIVE_PATH_PROPERTY, previousNativePath)
        }
    }

    private fun restoreProperty(name: String, value: String?) {
        if (value == null) {
            System.clearProperty(name)
        } else {
            System.setProperty(name, value)
        }
    }

    private const val ORT_NATIVE_PATH_PROPERTY = "onnxruntime.native.path"
}
