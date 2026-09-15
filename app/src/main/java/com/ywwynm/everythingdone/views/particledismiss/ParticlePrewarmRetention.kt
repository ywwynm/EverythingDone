package com.ywwynm.everythingdone.views.particledismiss

/**
 * 磁盘上的「创建页预测素材」保留多少份、淘汰哪一份。
 *
 * 纯逻辑、不碰 Android，方便用 JVM 单测钉住（[ParticleBatchFiveTest]）。
 * 真正的读写在 [ParticlePrewarmStore]。
 */
internal object ParticlePrewarmRetention {

    /** 最多保留几个**不同配置**（键）各一张。 */
    const val MAX_ENTRIES = 4

    /** 目录总大小上限。超过就按「最旧先走」淘汰。 */
    const val MAX_BYTES = 8L * 1024 * 1024

    /**
     * 磁盘上的一份预测素材。[key] 是**去掉新记事背景色之后**的那一份缓存键
     * （背景色每次新建都重掷，存一张带着某个背景色的图去要求它相等没有意义；
     * 背景偏差只影响途中每一片的权重，末帧仍由当次真实快照拼回）。
     */
    data class Record(val key: String, val file: String, val savedAt: Long, val bytes: Long)

    /**
     * 写入 [incoming] 之后应当**保留**哪些条目，按「新的在前」排好。调用方把不在返回值里的
     * 条目连同文件一起删掉。
     *
     * 规则：
     * - 同键替换（新的顶掉旧的）；
     * - 先按条数淘汰最旧，再按总大小淘汰最旧——两条都是从最旧那一端开始丢；
     * - [incoming] 自己就超过 [maxBytes] 时不收它，但**已经在盘上的旧条目不受牵连**。
     */
    fun retain(existing: List<Record>, incoming: Record?,
               maxEntries: Int = MAX_ENTRIES, maxBytes: Long = MAX_BYTES): List<Record> {
        if (maxEntries <= 0 || maxBytes <= 0) return emptyList()
        val accepted = incoming?.takeIf { it.bytes in 1..maxBytes }
        val rest = existing
            .filter { accepted == null || it.key != accepted.key }
            .sortedByDescending { it.savedAt }
        val kept = ArrayList<Record>(maxEntries)
        var total = 0L
        for (record in listOfNotNull(accepted) + rest) {
            if (kept.size >= maxEntries) break
            if (total + record.bytes > maxBytes) break
            kept += record
            total += record.bytes
        }
        return kept
    }
}
