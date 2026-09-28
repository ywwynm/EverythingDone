package com.ywwynm.everythingdone.thingfile

/**
 * 记事文件里的层级怎么还原成本机列表里的先后（纯计算，不依赖 Android）。
 *
 * 发送方每一层按列表顺序写 [ThingFile.KEY_ORDER]（置顶在前）。本机建一项时把它放到所在区段（置顶或普通）的
 * 最前面（`ThingFolderDAO.getFirstChildLocation`），所以文件夹里的项按列表顺序**倒着**建（[creationOrder]），
 * 建完就与发送方一样（决策 2026-09-28 第 7 条）。顶层不同：收到的项按文件里的顺序建（[topLevel]），后收到的
 * 排在前面，与收到记事的既有规则一致（`TapListPrediction`）。
 */
internal object ThingFileTree {

    /**
     * 一项在文件里的位置：所在文件夹的编号（null 为顶层）、在那一层里的先后，以及在清单里的序号
     * （先后相同时的次序：只含记事的旧文件里先后都是 0）。
     */
    class Placement(val parent: Int?, val order: Int, val index: Int)

    /** 按所在的文件夹分组，每组按列表顺序排。 */
    fun <T> group(items: List<T>, placement: (T) -> Placement): Map<Int?, List<T>> {
        val comparator = compareBy<T>({ placement(it).order }, { placement(it).index })
        return items.groupBy { placement(it).parent }.mapValues { (_, level) -> level.sortedWith(comparator) }
    }

    /** 顶层的建立顺序：文件里的顺序。 */
    fun <T> topLevel(levels: Map<Int?, List<T>>): List<T> = levels[null].orEmpty()

    /** 文件夹 [key] 里的项的建立顺序：列表里最下面的先建。 */
    fun <T> creationOrder(levels: Map<Int?, List<T>>, key: Int): List<T> = levels[key].orEmpty().asReversed()
}
