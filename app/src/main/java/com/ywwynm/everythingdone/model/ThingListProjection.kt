package com.ywwynm.everythingdone.model

import com.ywwynm.everythingdone.Def

data class ThingListProjection(
    val limit: Int = Def.LimitForGettingThings.ALL_UNDERWAY,
    val folderPath: List<Long> = emptyList()
) {

    val currentFolderId: Long?
        get() = folderPath.lastOrNull()

    fun isRoot(): Boolean = folderPath.isEmpty()

    fun key(): String {
        return "$limit:${folderPath.joinToString("/")}"
    }

    fun withLimit(limit: Int): ThingListProjection {
        return ThingListProjection(normalizeLimit(limit), emptyList())
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
        fun root(limit: Int): ThingListProjection {
            return ThingListProjection(normalizeLimit(limit), emptyList())
        }

        @JvmStatic
        fun normalizeLimit(limit: Int): Int {
            return when (limit) {
                Def.LimitForGettingThings.NOTE_UNDERWAY,
                Def.LimitForGettingThings.REMINDER_UNDERWAY,
                Def.LimitForGettingThings.HABIT_UNDERWAY,
                Def.LimitForGettingThings.GOAL_UNDERWAY,
                Def.LimitForGettingThings.ALL_FINISHED,
                Def.LimitForGettingThings.ALL_DELETED -> limit
                else -> Def.LimitForGettingThings.ALL_UNDERWAY
            }
        }
    }
}
