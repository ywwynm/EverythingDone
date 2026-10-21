package com.ywwynm.everythingdone.adapters

import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingFolder
import com.ywwynm.everythingdone.model.ThingListEntry

/** 展示版本独立于 Manager 的可变记录；包括文件夹和嵌套预览，避免只冻结通知却提前换数据。 */
internal object ThingListPresentation {
    fun snapshot(entries: List<ThingListEntry>): List<ThingListEntry> = entries.map { entry ->
        when (entry) {
            is ThingListEntry.ThingEntry -> ThingListEntry.ThingEntry(Thing(entry.thing))
            is ThingListEntry.FolderEntry -> entry.copy(
                folder = entry.folder.let { f ->
                    ThingFolder(f.id, f.parentFolderId, f.title, f.state, f.getColor(), f.location,
                        f.isPrivate, f.createTime, f.updateTime, f.cardPresentation).also {
                        it.setBackground(f.getBackground())
                        it.selected = f.selected
                    }
                },
                thumbnailEntries = snapshot(entry.thumbnailEntries),
                thumbnailThings = entry.thumbnailThings.map(::Thing)
            )
        }
    }

    fun changedIds(before: List<ThingListEntry>, after: List<ThingListEntry>): Set<Long> {
        val previous = before.associateBy { it.stableId }
        return after.filter { content(previous[it.stableId]) != content(it) }.mapTo(mutableSetOf()) { it.stableId }
    }

    private fun thing(t: Thing): List<Any?> = listOf(t.type, t.state, t.title, t.content, t.attachment,
        t.location, t.createTime, t.updateTime, t.finishTime, t.folderId, t.selected,
        t.getBackground(), t.thingCardAppearance, t.detailAttachmentMediaAppearance)

    private fun content(entry: ThingListEntry?): Any? = when (entry) {
        null -> null
        is ThingListEntry.ThingEntry -> thing(entry.thing)
        is ThingListEntry.FolderEntry -> entry.folder.let { f ->
            listOf(f.title, f.parentFolderId, f.state, f.location, f.isPrivate, f.selected,
                f.updateTime, f.getBackground(), f.cardPresentation, entry.recursiveThingCount,
                entry.directFolderCount, entry.thumbnailEntryCount, entry.effectivePrivate,
                entry.effectiveDeleted, entry.thumbnailEntries.map { it.stableId to content(it) },
                entry.thumbnailThings.map { it.id to thing(it) })
        }
    }
}
