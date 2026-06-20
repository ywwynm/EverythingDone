package com.ywwynm.everythingdone.appwidgets.list

import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingListEntry

sealed class ThingsListWidgetItem {
    abstract val stableId: Long
    abstract val location: Long

    data class ThingItem(
        val thing: Thing
    ) : ThingsListWidgetItem() {
        override val stableId: Long
            get() = thing.id
        override val location: Long
            get() = thing.location
    }

    data class FolderItem(
        val entry: ThingListEntry.FolderEntry
    ) : ThingsListWidgetItem() {
        override val stableId: Long
            get() = entry.stableId
        override val location: Long
            get() = entry.location
    }

    data class GridRow(
        val slots: List<ThingsListWidgetItem?>,
        val columns: Int,
        val rowId: Long
    ) : ThingsListWidgetItem() {
        override val stableId: Long
            get() = rowId
        override val location: Long
            get() = slots.firstOrNull { it != null }?.location ?: 0L
    }
}
