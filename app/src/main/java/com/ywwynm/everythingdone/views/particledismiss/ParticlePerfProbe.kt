package com.ywwynm.everythingdone.views.particledismiss

import android.opengl.GLES30
import com.ywwynm.everythingdone.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 仅 debug：粒子动画的计时与采样钩子。默认完全关闭——只有应用私有目录下存在
 * files/particle-perf-probe.json 时才生效，release 包里 [begin] 永远返回 null。
 *
 * 只读计时，不改变粒子模型、材料、随机、积分步数与压力迭代；除显式开启的
 * 临时诊断变体外不改变任何 GL 调用序列。
 */
internal object ParticlePerfProbe {

    const val CONFIG_NAME = "particle-perf-probe.json"

    private const val GL_TIME_ELAPSED_EXT = 0x88BF
    private const val GL_QUERY_RESULT_EXT = 0x8866
    private const val GL_GPU_DISJOINT_EXT = 0x8FBB

    /** 总量（模式 A）与分段（模式 B）互斥，同一时刻只能有一个计时查询在进行。 */
    const val LEVEL_TOTAL = 0
    const val LEVEL_SEGMENT = 1

    @Volatile private var filesDir: File? = null
    @Volatile private var outputRoot: File? = null
    private var sequence = 0

    /** 由 debug 专用 ContentProvider 在进程启动时调用；release 包没有调用点。 */
    fun attach(files: File, external: File?) {
        if (!BuildConfig.DEBUG) return
        filesDir = files
        outputRoot = File(external ?: files, "particle-perf")
    }

    private fun config(): JSONObject? {
        if (!BuildConfig.DEBUG) return null
        val dir = filesDir ?: return null
        val file = File(dir, CONFIG_NAME)
        if (!file.isFile) return null
        return runCatching { JSONObject(file.readText()) }.getOrNull()
    }

    /** 每次动画在 GL 线程调用一次；返回 null 时所有钩子都是空操作。 */
    fun begin(): Session? {
        if (!BuildConfig.DEBUG) return null
        val config = config() ?: return null
        if (!config.optBoolean("enabled", true)) return null
        return Session(config, outputRoot)
    }

    class Session(private val config: JSONObject, private val root: File?) {

        private val mode = config.optString("gpuTiming", "")
        private val runId = config.optString("run_id", "run").let {
            if (it.matches(Regex("[A-Za-z0-9_-]{1,80}"))) it else "run"
        }
        private val records = JSONArray()
        private val extra = JSONObject()
        private val queries = ArrayList<IntArray>()
        private val pending = ArrayList<Any>()
        private var active = 0
        private var activeLevel = -1
        private var supported: Boolean? = null
        private var frame = -1
        private var phase = "pre"
        private var disjointSeen = 0
        private var fenceName = ""
        private var fenceAt = 0L

        private val timing = mode == "a" || mode == "b" || mode == "c"

        private fun levelActive(level: Int) = timing &&
            (level == LEVEL_TOTAL && mode == "a" || level == LEVEL_SEGMENT && (mode == "b" || mode == "c"))

        /**
         * Adreno 上 GL_TIME_ELAPSED 不计 compute dispatch（见阶段 0 记录），
         * 模式 c 退回按段 glFinish 取墙钟：串行化流水线，只用于比较三段的相对量级。
         */
        private fun closeFence() {
            if (fenceName.isEmpty()) return
            GLES30.glFinish()
            records.put(JSONArray(listOf(fenceName, frame, phase, System.nanoTime() - fenceAt)))
            fenceName = ""
        }

        /** 内核计数器按 uid 可读；读一次即清零，用来取整段预计算的真实 GPU 忙时间。 */
        private fun busy(): LongArray? = runCatching {
            val parts = File("/sys/class/kgsl/kgsl-3d0/gpubusy").readText().trim().split(Regex("\\s+"))
            longArrayOf(parts[0].toLong(), parts[1].toLong())
        }.getOrNull()

        fun beginBusy() {
            if (!config.optBoolean("gpuBusy")) return
            busy()
            extra.put("busyStartedAt", System.nanoTime())
        }

        fun endBusy() {
            if (!config.optBoolean("gpuBusy")) return
            val value = busy() ?: return
            extra.put("gpuBusyUs", value[0]).put("gpuWindowUs", value[1])
        }

        private fun query(): Int {
            val ids = IntArray(1)
            GLES30.glGenQueries(1, ids, 0)
            queries += ids
            return ids[0]
        }

        /** 首次使用时确认驱动是否接受 GL_TIME_ELAPSED_EXT；不接受即整段不计时。 */
        private fun ensureSupported(): Boolean {
            supported?.let { return it }
            while (GLES30.glGetError() != GLES30.GL_NO_ERROR) { /* 清空既有错误 */ }
            val id = query()
            GLES30.glBeginQuery(GL_TIME_ELAPSED_EXT, id)
            val error = GLES30.glGetError()
            val ok = error == GLES30.GL_NO_ERROR
            if (ok) GLES30.glEndQuery(GL_TIME_ELAPSED_EXT) else extra.put("timerError", "0x" + Integer.toHexString(error))
            supported = ok
            extra.put("timerSupported", ok)
            extra.put("renderer", GLES30.glGetString(GLES30.GL_RENDERER) ?: "")
            // 读一次清空累计的 disjoint 状态。
            val flag = IntArray(1)
            GLES30.glGetIntegerv(GL_GPU_DISJOINT_EXT, flag, 0)
            if (ok) {
                // 首个查询只用于探测，读回后丢弃。
                val value = IntArray(1)
                GLES30.glGetQueryObjectuiv(id, GL_QUERY_RESULT_EXT, value, 0)
            }
            return ok
        }

        fun frame(index: Int, phase: String) {
            if (!timing) return
            frame = index
            this.phase = phase
        }

        /** 结束当前查询（若有）并按 [name] 开一个新的。 */
        fun mark(name: String, level: Int) {
            if (!levelActive(level)) return
            if (mode == "c") { closeFence(); fenceName = name; fenceAt = System.nanoTime(); return }
            if (!ensureSupported()) return
            stop(level)
            val id = query()
            GLES30.glBeginQuery(GL_TIME_ELAPSED_EXT, id)
            active = id
            activeLevel = level
            pending.add(arrayOf<Any>(name, frame, phase, id))
        }

        fun stop(level: Int) {
            if (!levelActive(level)) return
            if (mode == "c") { closeFence(); return }
            if (activeLevel != level || active == 0) return
            GLES30.glEndQuery(GL_TIME_ELAPSED_EXT)
            active = 0
            activeLevel = -1
        }

        /** 在 glFinish 之后调用，此时结果已可用，读回不会额外阻塞流水线。 */
        fun flush() {
            if (!timing || mode == "c" || pending.isEmpty()) return
            val flag = IntArray(1)
            GLES30.glGetIntegerv(GL_GPU_DISJOINT_EXT, flag, 0)
            if (flag[0] != 0) disjointSeen++
            val value = IntArray(1)
            for (entry in pending) {
                @Suppress("UNCHECKED_CAST") val row = entry as Array<Any>
                GLES30.glGetQueryObjectuiv(row[3] as Int, GL_QUERY_RESULT_EXT, value, 0)
                records.put(JSONArray(listOf(row[0], row[1], row[2], value[0].toLong() and 0xFFFFFFFFL)))
            }
            pending.clear()
        }

        fun note(key: String, value: Any) { extra.put(key, value) }

        fun finish(stage: String) {
            if (!BuildConfig.DEBUG) return
            runCatching {
                flush()
                for (ids in queries) GLES30.glDeleteQueries(1, ids, 0)
                queries.clear()
                val dir = File(root ?: return@runCatching, runId)
                dir.mkdirs()
                val index = synchronized(ParticlePerfProbe) { ++sequence }
                val payload = JSONObject()
                    .put("schema", 1).put("run_id", runId).put("stage", stage).put("mode", mode)
                    .put("disjoint", disjointSeen).put("extra", extra)
                    .put("records", records).put("at", System.nanoTime())
                File(dir, "%s-%03d.json".format(stage, index)).writeText(payload.toString())
            }
        }
    }
}
