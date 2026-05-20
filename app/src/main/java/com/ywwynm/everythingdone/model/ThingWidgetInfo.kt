package com.ywwynm.everythingdone.model

import android.database.Cursor
import androidx.annotation.IntDef
import com.ywwynm.everythingdone.Def

/**
 * Created by ywwynm on 2016/8/2.
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

    @IntDef(0, 1, 2, 3)
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

        const val HEADER_ALPHA_0: Int = -19950129

        const val STYLE_NORMAL: Int = 0
        const val STYLE_SIMPLE: Int = 1
    }
}
