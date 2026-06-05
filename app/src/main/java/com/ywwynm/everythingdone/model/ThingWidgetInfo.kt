package com.ywwynm.everythingdone.model

import android.database.Cursor
import androidx.annotation.IntDef
import com.ywwynm.everythingdone.Def

/**
 * Created by ywwynm on 2016/8/2.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * thing widget info
 */
open class ThingWidgetInfo(
    var id: Int,
    var thingId: Long,
    var size: Int,
    var alpha: Int,
    var style: Int
) {

    constructor(cursor: Cursor?) : this(
        cursor!!.getInt(cursor.getColumnIndex(Def.Database.COLUMN_ID_APP_WIDGET)),
        cursor.getLong(cursor.getColumnIndex(Def.Database.COLUMN_THING_ID_APP_WIDGET)),
        cursor.getInt(cursor.getColumnIndex(Def.Database.COLUMN_SIZE_APP_WIDGET)),
        cursor.getInt(cursor.getColumnIndex(Def.Database.COLUMN_ALPHA_APP_WIDGET)),
        cursor.getInt(cursor.getColumnIndex(Def.Database.COLUMN_STYLE_APP_WIDGET))
    )

    @IntDef(
        0, 1, 2, 3,
        4, 5, 6, 7,
        8, 9, 10, 11, 12, 13, 14,
        15, 16, 17, 18, 19, 20, 21, 22, 23
    )
    @Retention(AnnotationRetention.SOURCE)
    annotation class Size

    @IntDef(0, 1)
    @Retention(AnnotationRetention.SOURCE)
    annotation class Style

    companion object {
        const val SIZE_TINY: Int   = 0
        const val SIZE_SMALL: Int  = 1
        const val SIZE_MIDDLE: Int = 2
        const val SIZE_LARGE: Int  = 3
        const val SIZE_4X2: Int    = 4
        const val SIZE_2X4: Int    = 5
        const val SIZE_4X3: Int    = 6
        const val SIZE_3X4: Int    = 7
        const val SIZE_5X2: Int    = 8
        const val SIZE_2X5: Int    = 9
        const val SIZE_5X3: Int    = 10
        const val SIZE_3X5: Int    = 11
        const val SIZE_5X4: Int    = 12
        const val SIZE_4X5: Int    = 13
        const val SIZE_5X5: Int    = 14
        const val SIZE_6X2: Int    = 15
        const val SIZE_2X6: Int    = 16
        const val SIZE_6X3: Int    = 17
        const val SIZE_3X6: Int    = 18
        const val SIZE_6X4: Int    = 19
        const val SIZE_4X6: Int    = 20
        const val SIZE_6X5: Int    = 21
        const val SIZE_5X6: Int    = 22
        const val SIZE_6X6: Int    = 23

        const val HEADER_ALPHA_0: Int = -19950129

        const val STYLE_NORMAL: Int = 0
        const val STYLE_SIMPLE: Int = 1
    }
}
