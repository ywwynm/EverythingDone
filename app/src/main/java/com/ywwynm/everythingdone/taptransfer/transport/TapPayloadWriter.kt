package com.ywwynm.everythingdone.taptransfer.transport

import android.content.Context
import android.util.Log
import com.ywwynm.everythingdone.taptransfer.TapTransfer
import com.ywwynm.everythingdone.taptransfer.diagnostics.TapDiagnostics
import com.ywwynm.everythingdone.thingfile.ThingFileWriter
import com.ywwynm.everythingdone.thingfile.ThingShareContent
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * 分享界面一打开就在后台把要发的记事写成记事文件（与「记事文件」那一项的产物相同），碰上时
 * 通常已经写好。分享界面关闭时调用 [discard] 删掉。
 *
 * @param content 在主线程上复制好的记事与文件夹：写文件在后台线程，不能读列表里正被界面使用的那几个对象。
 */
class TapPayloadWriter(context: Context, content: ThingShareContent,
                       private val diagnostics: TapDiagnostics.Session = TapDiagnostics.NONE) : TapPayload {

    private val appContext = context.applicationContext
    private val dst = File(
        TapTransfer.workDir(appContext, "out"), "send-${System.currentTimeMillis()}.edthing"
    )

    private val task = FutureTask<File?> {
        diagnostics.event("准备记事文件")
        when (val result = ThingFileWriter.write(appContext, content, dst)) {
            is ThingFileWriter.Result.Ok -> result.file.also {
                diagnostics.event("记事文件就绪", "bytes=${it.length()}")
            }
            is ThingFileWriter.Result.Failed -> {
                diagnostics.failure("记事文件生成失败")
                Log.w(TAG, "写记事文件失败：${result.message}")
                null
            }
        }
    }

    init {
        // 分享界面开着多久都有可能（中途切到后台再回来），清理残留时不能把它当成残留
        IN_USE.add(dst.absolutePath)
        EXECUTOR.execute(task)
    }

    override fun await(timeoutMs: Long): File? {
        val file = try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            diagnostics.failure("等待记事文件失败", "exception=${e.javaClass.name}")
            Log.w(TAG, "等记事文件失败", e)
            null
        }
        // 写好之后被删掉（例如用户清了缓存）：当作没写出来，别发一个空文件过去
        return file?.takeIf { it.isFile }
    }

    /** 还能用：还在写，或者写好了、文件还在。 */
    fun isUsable(): Boolean {
        if (!task.isDone) {
            return true
        }
        return try {
            task.get()?.isFile == true
        } catch (e: Exception) {
            false
        }
    }

    fun discard() {
        task.cancel(false)
        // 执行器是单线程的：删除排在写入之后，已经开始写的也会在写完后删掉
        EXECUTOR.execute {
            dst.delete()
            IN_USE.remove(dst.absolutePath)
        }
    }

    companion object {
        private const val TAG = "TapPayload"
        private val EXECUTOR = Executors.newSingleThreadExecutor()

        /** 正被打开着的分享界面使用的记事文件。 */
        private val IN_USE: MutableSet<String> = Collections.synchronizedSet(HashSet())

        /**
         * 超过这么久没改动的临时文件视为残留。正在收的文件一直在写，改动时间总是新的；
         * 正在发的文件由 [IN_USE] 保护。
         */
        private const val STALE_MS = 60L * 60 * 1000

        /** 清掉上次没删成的残留（进程被杀时 [discard] 与接收方的清理都没有机会执行）。 */
        @JvmStatic
        fun cleanUpStale(context: Context) {
            val appContext = context.applicationContext
            EXECUTOR.execute {
                val deadline = System.currentTimeMillis() - STALE_MS
                for (child in arrayOf("out", "in", "sim")) {
                    TapTransfer.workDir(appContext, child).listFiles()
                        ?.filter { it.lastModified() < deadline && !IN_USE.contains(it.absolutePath) }
                        ?.forEach { it.delete() }
                }
            }
        }
    }
}
