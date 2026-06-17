package com.ywwynm.everythingdone.model

sealed class ThingListEntry {

    abstract val stableId: Long
    abstract val location: Long

    data class ThingEntry(val thing: Thing) : ThingListEntry() {
        override val stableId: Long
            get() = thing.id
        override val location: Long
            get() = thing.location
    }

    data class FolderEntry(
        val folder: ThingFolder,
        val recursiveThingCount: Int,
        val directFolderCount: Int = 0,
        val thumbnailEntries: List<ThingListEntry> = emptyList(),
        val thumbnailEntryCount: Int = 0,
        val thumbnailThings: List<Thing> = emptyList(),
        val effectivePrivate: Boolean = folder.isPrivate,
        val effectiveDeleted: Boolean = folder.isDeleted()
    ) : ThingListEntry() {
        override val stableId: Long
            get() = -folder.id
        override val location: Long
            get() = folder.location
    }
}
