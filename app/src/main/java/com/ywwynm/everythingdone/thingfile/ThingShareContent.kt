package com.ywwynm.everythingdone.thingfile

import android.content.Context
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.database.ThingFolderDAO
import com.ywwynm.everythingdone.model.Reminder
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingFolder
import com.ywwynm.everythingdone.utils.ThingsSorter

/**
 * 一次分享的内容：顶层的若干项（散的记事与文件夹，按列表顺序），文件夹带着要分享的整棵子树
 * （决策 2026-09-28）。只含记事时写出来的记事文件与以前相同（版本 1）。
 *
 * 记事与文件夹都是副本：记事文件在后台线程写，不能读列表里正被界面使用的对象。
 */
class ThingShareContent(val items: List<Node>) {

    sealed class Node {
        /** 在所在的文件夹里置顶。顶层的项不看它：收到的一律不置顶（决策 2026-09-28 第 7 条）。 */
        abstract val sticky: Boolean
    }

    /**
     * @param reminder 预先给定的提醒：还没写进数据库的记事（碰一碰模拟的样例）用它；为 null 时写文件时按 id 从数据库取。
     */
    class ThingNode(
        val thing: Thing,
        override val sticky: Boolean = false,
        val reminder: Reminder? = null
    ) : Node()

    /** [children] 按列表顺序（置顶在前）。 */
    class FolderNode(
        val folder: ThingFolder,
        val children: List<Node>,
        override val sticky: Boolean = false
    ) : Node()

    val hasFolders: Boolean = items.any { it is FolderNode }

    /** 全部记事，深度优先、按列表顺序：也是写进文件的顺序。 */
    val things: List<Thing> = ArrayList<Thing>().also { collectThings(items, it) }

    /** 全部文件夹（含子文件夹）的个数。 */
    val folderCount: Int = countFolders(items)

    val isEmpty: Boolean get() = things.isEmpty() && folderCount == 0

    companion object {

        @JvmStatic
        fun ofThings(things: List<Thing>): ThingShareContent = ThingShareContent(things.map { ThingNode(it) })

        private fun collectThings(nodes: List<Node>, out: MutableList<Thing>) {
            for (node in nodes) {
                when (node) {
                    is ThingNode -> out.add(node.thing)
                    is FolderNode -> collectThings(node.children, out)
                }
            }
        }

        private fun countFolders(nodes: List<Node>): Int = nodes.sumOf {
            if (it is FolderNode) 1 + countFolders(it.children) else 0
        }
    }
}

/**
 * 从数据库取一个文件夹要分享的子树。
 */
object ThingShareContentBuilder {

    /**
     * 文件夹 [folderId] 要分享的整棵子树：正在进行、类型符合 [typeFilterMask] 的记事，以及子树里至少有一条
     * 这样的记事的子文件夹——与「正在进行」列表里看得到的一致（决策 2026-09-28 第 1–3 条；列表本来就不显示
     * 没有正在进行记事的文件夹，开不开筛选都一样）。回收站里的子文件夹不带。每一层按列表的先后排（置顶在前）。
     *
     * 返回的对象都是从数据库新读的，可以交给后台线程。整棵子树里没有一条这样的记事时返回 null。
     */
    @JvmStatic
    fun folderNode(context: Context, folderId: Long, typeFilterMask: Int): ThingShareContent.FolderNode? {
        val dao = ThingFolderDAO.getInstance(context) ?: return null
        val root = dao.getFolderById(folderId) ?: return null
        if (root.isDeleted()) return null
        val things = dao.getDescendantThingsForProjection(folderId, Def.ThingStatus.UNDERWAY, typeFilterMask)
        if (things.isEmpty()) return null
        val byFolder = things.groupBy { it.folderId }
        return build(dao, root, byFolder, HashSet())
    }

    private class Child(val location: Long, val stableId: Long, val node: ThingShareContent.Node)

    private fun build(
        dao: ThingFolderDAO,
        folder: ThingFolder,
        byFolder: Map<Long?, List<Thing>>,
        visited: MutableSet<Long>
    ): ThingShareContent.FolderNode? {
        // 数据库里的父子关系应当无环；万一有，别在这里转不出来
        if (!visited.add(folder.id)) return null
        val children = ArrayList<Child>()
        for (thing in byFolder[folder.id].orEmpty()) {
            // 列表里记事的 stableId 就是 id，文件夹是 −id（ThingListEntry）
            children.add(Child(thing.location, thing.id, ThingShareContent.ThingNode(thing, thing.location < 0)))
        }
        for (child in dao.getChildFolders(folder.id)) {
            if (child.isDeleted()) continue
            val node = build(dao, child, byFolder, visited) ?: continue
            children.add(Child(child.location, -child.id, node))
        }
        if (children.isEmpty()) return null
        children.sortWith { a, b ->
            val result = ThingsSorter.compareByLocationAndSticky(a.location, b.location)
            if (result != 0) result else a.stableId.compareTo(b.stableId)
        }
        return ThingShareContent.FolderNode(folder, children.map { it.node }, folder.isSticky())
    }
}
