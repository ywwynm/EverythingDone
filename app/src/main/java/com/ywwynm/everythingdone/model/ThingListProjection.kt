package com.ywwynm.everythingdone.model

import com.ywwynm.everythingdone.Def

data class ThingListProjection(
    val status: Int = Def.ThingStatus.UNDERWAY,
    val folderPath: List<Long> = emptyList(),
    val typeFilterMask: Int = ThingWidgetInfo.TYPE_FILTER_ALL
) {

    val currentFolderId: Long?
        get() = folderPath.lastOrNull()

    fun isRoot(): Boolean = folderPath.isEmpty()

    fun key(): String {
        val filterKey = ThingWidgetInfo.normalizedTypeFilterMask(typeFilterMask)
        return "$status:$filterKey:${folderPath.joinToString("/")}"
    }

    fun withStatus(status: Int): ThingListProjection {
        return ThingListProjection(normalizeStatus(status), emptyList())
    }

    fun withTypeFilterMask(mask: Int): ThingListProjection {
        return copy(
            typeFilterMask = ThingWidgetInfo.normalizedTypeFilterMask(mask)
        )
    }

    fun openFolder(folderId: Long): ThingListProjection {
        return copy(folderPath = folderPath + folderId)
    }

    fun navigateToPathIndex(index: Int): ThingListProjection {
        if (index < 0) return copy(folderPath = emptyList())
        val safeIndex = index.coerceAtMost(folderPath.size - 1)
        return copy(folderPath = folderPath.take(safeIndex + 1))
    }

    fun parent(): ThingListProjection {
        if (folderPath.isEmpty()) return this
        return copy(folderPath = folderPath.dropLast(1))
    }

    companion object {
        @JvmStatic
        fun root(status: Int): ThingListProjection {
            return ThingListProjection(normalizeStatus(status), emptyList())
        }

        @JvmStatic
        fun normalizeStatus(status: Int): Int {
            return when (status) {
                Def.ThingStatus.UNDERWAY,
                Def.ThingStatus.FINISHED,
                Def.ThingStatus.DELETED -> status
                else -> Def.ThingStatus.UNDERWAY
            }
        }
    }
}
