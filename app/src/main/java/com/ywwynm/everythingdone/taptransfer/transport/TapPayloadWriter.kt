package com.ywwynm.everythingdone.taptransfer.transport

import android.content.Context
import android.util.Log
import com.ywwynm.everythingdone.taptransfer.TapTransfer
import com.ywwynm.everythingdone.taptransfer.diagnostics.TapDiagnostics
import com.ywwynm.everythingdone.taptransfer.protocol.TapFileTransfer
import com.ywwynm.everythingdone.thingfile.ThingFileWriter
import com.ywwynm.everythingdone.thingfile.ThingShareContent
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * 分享界面打开时后台准备清单与文件内容摘要，不提前打包附件。接收方协商后只发送缺失内容。
 *
 * @param content 主线程复制的记事与文件夹，后台准备时不读取界面正在编辑的对象。
 */
class TapPayloadWriter(context: Context, content: ThingShareContent,
                       private val diagnostics: TapDiagnostics.Session = TapDiagnostics.NONE) : TapPayload {

    private val appContext = context.applicationContext
    private val task = FutureTask<TapFileTransfer.Outgoing?> {
        try {
            diagnostics.event("准备附件摘要")
            ThingFileWriter.prepareTransfer(appContext, content).also {
                diagnostics.event("附件清单就绪", "attachments=${it.sources.size}, bytes=${it.totalBytes}")
            }
        } catch (e: Exception) {
            diagnostics.failure("附件清单生成失败", "exception=${e.javaClass.name}")
            Log.w(TAG, "准备附件清单失败", e)
            null
        }
    }

    init {
        EXECUTOR.execute(task)
    }

    override fun await(timeoutMs: Long): TapFileTransfer.Outgoing? {
        val file = try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            diagnostics.failure("等待附件清单失败", "exception=${e.javaClass.name}")
            Log.w(TAG, "等待附件清单失败", e)
            null
        }
        // 摘要完成后源文件丢失或变化，不能继续发送过期的快照。
        return file?.takeIf { it.isUsable() }
    }

    /** 仍在准备，或准备完成且源文件没有变化。 */
    fun isUsable(): Boolean {
        if (!task.isDone) {
            return true
        }
        return try {
            task.get()?.isUsable() == true
        } catch (e: Exception) {
            false
        }
    }

    fun discard() {
        task.cancel(true)
    }

    companion object {
        private const val TAG = "TapPayload"
        private val EXECUTOR = Executors.newSingleThreadExecutor()

        private const val STALE_MS = 24L * 60 * 60 * 1000

        /** 清掉上次没删成的残留（进程被杀时 [discard] 与接收方的清理都没有机会执行）。 */
        @JvmStatic
        fun cleanUpStale(context: Context) {
            val appContext = context.applicationContext
            EXECUTOR.execute {
                val deadline = System.currentTimeMillis() - STALE_MS
                for (child in arrayOf("out", "in", "sim")) {
                    TapTransfer.workDir(appContext, child).listFiles()
                        ?.filter { file -> file.walkTopDown().none { it.lastModified() >= deadline } }
                        ?.forEach { it.deleteRecursively() }
                }
            }
        }
    }
}
