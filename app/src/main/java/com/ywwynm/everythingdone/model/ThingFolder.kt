package com.ywwynm.everythingdone.model

import android.database.Cursor
import com.ywwynm.everythingdone.Def

open class ThingFolder(
    var id: Long,
    var parentFolderId: Long?,
    var title: String,
    var state: Int,
    color: Int,
    var location: Long,
    var isPrivate: Boolean = false,
    var createTime: Long = System.currentTimeMillis(),
    var updateTime: Long = createTime,
    var cardPresentation: ThingFolderCardPresentation = ThingFolderCardPresentation.default()
) {

    private var _color: Int = color
    private var _background: ThingBackground? = ThingBackground.pure(color)
    var selected: Boolean = false

    constructor(id: Long, title: String, color: Int, location: Long) : this(
        id = id,
        parentFolderId = null,
        title = title,
        state = Thing.UNDERWAY,
        color = color,
        location = location
    )

    constructor(c: Cursor) : this(
        id = c.getLong(c.getColumnIndexOrThrow(Def.Database.COLUMN_ID_THING_FOLDERS)),
        parentFolderId = nullableLong(
            c,
            Def.Database.COLUMN_PARENT_FOLDER_ID_THING_FOLDERS
        ),
        title = c.getString(c.getColumnIndexOrThrow(Def.Database.COLUMN_TITLE_THING_FOLDERS))
            ?: "",
        state = c.getInt(c.getColumnIndexOrThrow(Def.Database.COLUMN_STATE_THING_FOLDERS)),
        color = c.getInt(c.getColumnIndexOrThrow(Def.Database.COLUMN_COLOR_THING_FOLDERS)),
        location = c.getLong(c.getColumnIndexOrThrow(Def.Database.COLUMN_LOCATION_THING_FOLDERS)),
        isPrivate = c.getInt(
            c.getColumnIndexOrThrow(Def.Database.COLUMN_IS_PRIVATE_THING_FOLDERS)
        ) == 1,
        createTime = c.getLong(
            c.getColumnIndexOrThrow(Def.Database.COLUMN_CREATE_TIME_THING_FOLDERS)
        ),
        updateTime = c.getLong(
            c.getColumnIndexOrThrow(Def.Database.COLUMN_UPDATE_TIME_THING_FOLDERS)
        ),
        cardPresentation = ThingFolderCardPresentation.fromJson(
            optionalString(c, Def.Database.COLUMN_CARD_PRESENTATION_THING_FOLDERS)
        ) ?: ThingFolderCardPresentation.default()
    ) {
        val bg = ThingBackground.fromJson(optionalString(c, Def.Database.COLUMN_BACKGROUND_THING_FOLDERS))
        _background = bg ?: ThingBackground.pure(_color)
    }

    fun getColor(): Int = _color

    fun setColor(color: Int) {
        _color = color
        if (_background == null || _background!!.mode == ThingBackground.Mode.PURE) {
            _background = ThingBackground.pure(color)
        }
    }

    fun getBackground(): ThingBackground? {
        if (_background == null) _background = ThingBackground.pure(_color)
        return _background
    }

    fun setBackground(background: ThingBackground?) {
        val bg = background ?: ThingBackground.pure(_color)
        _background = bg
        _color = bg.representativeColor()
    }

    fun isUnderway(): Boolean = state == Thing.UNDERWAY

    fun isFinished(): Boolean = state == Thing.FINISHED

    fun isDeleted(): Boolean = state == Thing.DELETED

    fun isSticky(): Boolean = location < 0

    fun isSelected(): Boolean = selected

    fun markUpdated(time: Long = System.currentTimeMillis()) {
        updateTime = time
    }

    companion object {
        private fun nullableLong(c: Cursor, columnName: String): Long? {
            val index = c.getColumnIndex(columnName)
            if (index < 0 || c.isNull(index)) return null
            return c.getLong(index)
        }

        private fun optionalString(c: Cursor, columnName: String): String? {
            val index = c.getColumnIndex(columnName)
            if (index < 0 || c.isNull(index)) return null
            return c.getString(index)
        }
    }
}
