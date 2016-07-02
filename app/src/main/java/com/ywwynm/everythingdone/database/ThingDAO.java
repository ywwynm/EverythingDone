package com.ywwynm.everythingdone.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;

import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.model.ThingsCounts;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by ywwynm on 2015/9/6.
 * DAO layer between model {@link Thing} and database table "things". Provides easily-used APIs
 * for controller {@link com.ywwynm.everythingdone.managers.ThingManager}.
 *
 * There are so many fuckable methods. It is my lost youth.
 */
public class ThingDAO {

    public static final String TAG = "ThingDAO";

    private Context mContext;

    private int mLimit;

    private static ThingDAO sThingDAO;

    private SQLiteDatabase db;

    private ThingDAO(Context context) {
        mContext = context;
        mLimit = Def.LimitForGettingThings.ALL_UNDERWAY;
        EverythingDoneSQLiteOpenHelper helper = new EverythingDoneSQLiteOpenHelper(context);
        db = helper.getWritableDatabase();
        checkSelf();
    }

    // Singleton class
    public static ThingDAO getInstance(Context context) {
        if (sThingDAO == null) {
            synchronized (ThingDAO.class) {
                if (sThingDAO == null) {
                    sThingDAO = new ThingDAO(context);
                }
            }
        }
        return sThingDAO;
    }

    public void checkSelf() {
        Cursor cursor = db.query(Def.Database.TABLE_THINGS,
                null, null, null, null, null, "id desc");
        cursor.moveToFirst();
        int type = cursor.getInt(cursor.getColumnIndex(Def.Database.COLUMN_TYPE_THINGS));
        if (type != Thing.HEADER) {
            updateHeader(cursor.getLong(0));
        }
        cursor.close();
    }

    public void setLimit(int limit) {
        mLimit = limit;
    }

    public Thing getThingById(long id) {
        Cursor cursor = db.query(Def.Database.TABLE_THINGS, null,
                "id=" + id, null, null, null, null);
        boolean moved = cursor.moveToFirst();
        if (!moved) {
            return null;
        }
        Thing thing = new Thing(cursor);
        cursor.close();
        return thing;
    }

    public long getHeaderId() {
        Cursor cursor = db.query(Def.Database.TABLE_THINGS, null,
                "type=" + Thing.HEADER, null, null, null, null);
        cursor.moveToFirst();
        long id = cursor.getLong(0);
        cursor.close();
        return id;
    }

    public List<Thing> getThingsForDisplay(int limit) {
        return getThingsForDisplay(limit, null, 0);
    }

    public List<Thing> getThingsForDisplay(int limit, String keyword, int color) {
        Cursor cursor = getThingsCursorForDisplay(limit, keyword, color);
        List<Thing> things = new ArrayList<>();
        while (cursor.moveToNext()) {
            things.add(new Thing(cursor));
        }
        cursor.close();
        return things;
    }

    /**
     * @return {@code true} if there was a SQLiteConstraintException thrown.
     */
    public boolean create(Thing thing, boolean handleNotifyEmpty, boolean handleCurrentLimit) {
        updateHeader(1);

        int type = thing.getType();
        int state = thing.getState();
        if (handleNotifyEmpty) {
            deleteNotifyEmpty(type, state, handleCurrentLimit);
        }

        ContentValues values = new ContentValues();
        values.put(Def.Database.COLUMN_ID_THINGS, thing.getId());
        values.put(Def.Database.COLUMN_TYPE_THINGS, type);
        values.put(Def.Database.COLUMN_STATE_THINGS, state);
        values.put(Def.Database.COLUMN_COLOR_THINGS, thing.getColor());
        values.put(Def.Database.COLUMN_TITLE_THINGS, thing.getTitle());
        values.put(Def.Database.COLUMN_CONTENT_THINGS, thing.getContent());
        values.put(Def.Database.COLUMN_ATTACHMENT_THINGS, thing.getAttachment());
        values.put(Def.Database.COLUMN_LOCATION_THINGS, thing.getLocation());
        values.put(Def.Database.COLUMN_CREATE_TIME_THINGS, thing.getCreateTime());
        values.put(Def.Database.COLUMN_UPDATE_TIME_THINGS, thing.getUpdateTime());
        values.put(Def.Database.COLUMN_FINISH_TIME_THINGS, thing.getFinishTime());

        try {
            db.insert(Def.Database.TABLE_THINGS, null, values);
            return false;
        } catch (SQLiteConstraintException e) {
            e.printStackTrace();
            create(thing, handleNotifyEmpty, handleCurrentLimit);
            return true;
        }
    }

    public void update(@Thing.Type int typeBefore, Thing updatedThing, boolean handleNotifyEmpty,
                       boolean handleCurrentLimit) {
        if (updatedThing == null) {
            return;
        }

        int typeAfter = updatedThing.getType();
        int state = updatedThing.getState();
        if (handleNotifyEmpty) {
            deleteNotifyEmpty(typeAfter, state, handleCurrentLimit);
        }

        ContentValues values = new ContentValues();
        values.put(Def.Database.COLUMN_TYPE_THINGS, typeAfter);
        values.put(Def.Database.COLUMN_COLOR_THINGS, updatedThing.getColor());
        values.put(Def.Database.COLUMN_TITLE_THINGS, updatedThing.getTitle());
        values.put(Def.Database.COLUMN_CONTENT_THINGS, updatedThing.getContent());
        values.put(Def.Database.COLUMN_ATTACHMENT_THINGS, updatedThing.getAttachment());
        values.put(Def.Database.COLUMN_UPDATE_TIME_THINGS, updatedThing.getUpdateTime());

        db.update(Def.Database.TABLE_THINGS, values, "id=" + updatedThing.getId(), null);

        // true only this method is called separately without ThingManager#update called.
        // for example, called in receivers.
        if (handleCurrentLimit) {
            ThingsCounts.getInstance(mContext).handleUpdate(typeBefore, state, typeAfter, state, 1);
        }

        if (handleNotifyEmpty) {
            createNotifyEmpty(typeBefore, state, handleCurrentLimit);
        }
    }

    public void updateState(Thing thing, long location,
                            @Thing.State int stateBefore, @Thing.State int stateAfter,
                            boolean handleNotifyEmpty, boolean handleCurrentLimit,
                            boolean toUndo, long headerLocation, boolean shouldUpdateHeader) {
        long id = thing.getId();
        int type = thing.getType();
        ContentValues values = new ContentValues();

        if (stateBefore == Thing.DELETED_FOREVER) {
            if (handleNotifyEmpty) {
                deleteNotifyEmpty(type, stateAfter, handleCurrentLimit);
            }

            values.put(Def.Database.COLUMN_ID_THINGS, id);
            values.put(Def.Database.COLUMN_TYPE_THINGS, type);
            values.put(Def.Database.COLUMN_STATE_THINGS, stateAfter);
            values.put(Def.Database.COLUMN_COLOR_THINGS, thing.getColor());
            values.put(Def.Database.COLUMN_TITLE_THINGS, thing.getTitle());
            values.put(Def.Database.COLUMN_CONTENT_THINGS, thing.getContent());
            values.put(Def.Database.COLUMN_ATTACHMENT_THINGS, thing.getAttachment());
            values.put(Def.Database.COLUMN_LOCATION_THINGS, thing.getLocation());
            values.put(Def.Database.COLUMN_CREATE_TIME_THINGS, thing.getCreateTime());
            values.put(Def.Database.COLUMN_UPDATE_TIME_THINGS, thing.getUpdateTime());
            values.put(Def.Database.COLUMN_FINISH_TIME_THINGS, thing.getFinishTime());

            db.insert(Def.Database.TABLE_THINGS, null, values);
        } else {
            if (stateAfter != Thing.DELETED_FOREVER) {
                if (handleNotifyEmpty) {
                    deleteNotifyEmpty(type, stateAfter, handleCurrentLimit);
                }

                // we should keep newest finished thing at the first place in Finished page.
                if (!toUndo) {
                    if (shouldUpdateHeader) {
                        updateHeader(1);
                    }
                    values.put(Def.Database.COLUMN_LOCATION_THINGS, headerLocation);
                    if (stateAfter == Thing.FINISHED) {
                        values.put(Def.Database.COLUMN_FINISH_TIME_THINGS, System.currentTimeMillis());
                    }
                } else {
                    values.put(Def.Database.COLUMN_LOCATION_THINGS, location);
                }

                values.put(Def.Database.COLUMN_CONTENT_THINGS, thing.getContent());
                values.put(Def.Database.COLUMN_STATE_THINGS, stateAfter);
                db.update(Def.Database.TABLE_THINGS, values, "id=" + id, null);
            } else {
                db.delete(Def.Database.TABLE_THINGS, "id=" + id, null);
            }

            if (handleNotifyEmpty) {
                createNotifyEmpty(type, stateBefore, handleCurrentLimit);
            }
        }

        if (handleCurrentLimit) {
            ThingsCounts.getInstance(mContext).handleUpdate(type, stateBefore, type, stateAfter, 1);
        }
    }

    public void updateStates(List<Thing> things, List<Long> locations,
                             @Thing.State int stateBefore, @Thing.State int stateAfter,
                             boolean toUndo, long headerLocation) {
        db.beginTransaction();
        try {
            long newHeaderLocation = headerLocation;
            int size = things.size();
            if (!toUndo) {
                updateHeader(size);
                for (int i = size - 1; i >= 0; i--, newHeaderLocation++) {
                    updateState(things.get(i), -1, stateBefore, stateAfter, false, false,
                            false, newHeaderLocation, false);
                }
            } else {
                for (int i = size - 1; i >= 0; i--, newHeaderLocation++) {
                    updateState(things.get(i), locations.get(i), stateBefore, stateAfter,
                            false, false, true, newHeaderLocation, false);
                }
            }

            ThingsCounts thingsCounts = ThingsCounts.getInstance(mContext);
            final int currentLimit = mLimit;
            int neCreated = 0;
            for (int limit = Def.LimitForGettingThings.ALL_UNDERWAY;
                 limit <= Def.LimitForGettingThings.ALL_DELETED; limit++) {
                if (currentLimit == limit) continue;

                Cursor cursor = getThingsCursorForDisplay(limit, null, 0);
                int count = 0;
                while (cursor.moveToNext()) {
                    count++;
                    if (count == 3) break;
                }

                if (count == 1) {
                    Thing ne = Thing.generateNotifyEmpty(limit, getHeaderId(), mContext);
                    create(ne, false, false);
                    thingsCounts.handleCreation(ne.getType());
                    neCreated++;
                } else if (count == 3) {
                    int NEtype = Thing.getNotifyEmptyType(limit);
                    cursor.close();
                    cursor = db.query(Def.Database.TABLE_THINGS, null,
                            "type=" + NEtype, null, null, null, null);
                    if (cursor.getCount() != 0) {
                        db.delete(Def.Database.TABLE_THINGS, "type=" + NEtype, null);
                        thingsCounts.handleUpdate(NEtype, Thing.UNDERWAY,
                                NEtype, Thing.DELETED_FOREVER, 1);
                    }
                }
                cursor.close();
            }

            updateHeader(6 - neCreated);

            db.setTransactionSuccessful();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            db.endTransaction();
        }
    }

    public void updateLocations(Long[] ids, Long[] locations) {
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            for (int i = 0; i < ids.length; i++) {
                values.put(Def.Database.COLUMN_LOCATION_THINGS, locations[i]);
                db.update(Def.Database.TABLE_THINGS, values, "id=" + ids[i], null);
                values.clear();
            }
            db.setTransactionSuccessful();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * A fucking method to get cursor of things for display.
     *
     * Why do I say it's fucking? Because it's very ugly and there is no human intelligence
     * shinning in this method, only stupid switch-cases and exhaustions.
     * Well, dear {@author ywwynm}, you will feel ashamed next time you look upon this block of
     * code after some decades.
     */
    // Fuck this method! I'm so damn fuck stupid!
    public Cursor getThingsCursorForDisplay(int limit, String keyword, int color) {
        StringBuilder limitSb = new StringBuilder();
        switch (limit) {
            case Def.LimitForGettingThings.ALL_UNDERWAY:
                limitSb.append("((((type>=").append(Thing.NOTE)
                        .append(" and type<=").append(Thing.WELCOME_UNDERWAY)
                        .append(") or type=").append(Thing.NOTIFICATION_UNDERWAY)
                        .append(") and state=").append(Thing.UNDERWAY)
                        .append(") or type=").append(Thing.NOTIFY_EMPTY_UNDERWAY)
                        .append(")");
                break;
            case Def.LimitForGettingThings.NOTE_UNDERWAY:
                limitSb.append("(((type=").append(Thing.NOTE)
                        .append(" or type=").append(Thing.WELCOME_NOTE)
                        .append(" or type=").append(Thing.NOTIFICATION_NOTE)
                        .append(") and state=").append(Thing.UNDERWAY)
                        .append(") or type=").append(Thing.NOTIFY_EMPTY_NOTE)
                        .append(")");
                break;
            case Def.LimitForGettingThings.REMINDER_UNDERWAY:
                limitSb.append("(((type=").append(Thing.REMINDER)
                        .append(" or type=").append(Thing.WELCOME_REMINDER)
                        .append(" or type=").append(Thing.NOTIFICATION_REMINDER)
                        .append(") and state=").append(Thing.UNDERWAY)
                        .append(") or type=").append(Thing.NOTIFY_EMPTY_REMINDER)
                        .append(")");
                break;
            case Def.LimitForGettingThings.HABIT_UNDERWAY:
                limitSb.append("(((type=").append(Thing.HABIT)
                        .append(" or type=").append(Thing.WELCOME_HABIT)
                        .append(" or type=").append(Thing.NOTIFICATION_HABIT)
                        .append(") and state=").append(Thing.UNDERWAY)
                        .append(") or type=").append(Thing.NOTIFY_EMPTY_HABIT)
                        .append(")");
                break;
            case Def.LimitForGettingThings.GOAL_UNDERWAY:
                limitSb.append("(((type=").append(Thing.GOAL)
                        .append(" or type=").append(Thing.WELCOME_GOAL)
                        .append(" or type=").append(Thing.NOTIFICATION_GOAL)
                        .append(") and state=").append(Thing.UNDERWAY)
                        .append(") or type=").append(Thing.NOTIFY_EMPTY_GOAL)
                        .append(")");
                break;
            case Def.LimitForGettingThings.ALL_FINISHED:
                limitSb.append("((type>=").append(Thing.NOTE)
                        .append(" and type<=").append(Thing.NOTIFICATION_GOAL)
                        .append(" and state=").append(Thing.FINISHED)
                        .append(") or type=").append(Thing.NOTIFY_EMPTY_FINISHED)
                        .append(")");
                break;
            case Def.LimitForGettingThings.ALL_DELETED:
                limitSb.append("((type>=").append(Thing.NOTE)
                        .append(" and type<=").append(Thing.NOTIFICATION_GOAL)
                        .append(" and state=").append(Thing.DELETED)
                        .append(") or type=").append(Thing.NOTIFY_EMPTY_DELETED)
                        .append(")");
                break;
        }
        if (color != -1979711488 && color != 0) {
            limitSb.append(" and color=").append(color);
        }
        if (keyword != null) {
            keyword = keyword.replaceAll("'", "''");
            limitSb.append(" and (title like '%").append(keyword)
                    .append("%' or content like '%").append(keyword).append("%')");
        }
        limitSb.append(" or type=").append(Thing.HEADER);
        return db.query(Def.Database.TABLE_THINGS,
                null, limitSb.toString(), null, null, null, "location desc");
    }

    public Cursor getAllThingsCursor() {
        return db.query(Def.Database.TABLE_THINGS, null, null, null, null, null, null);
    }

    public Cursor getThingsCursor(String selection) {
        return db.query(Def.Database.TABLE_THINGS, null, selection, null, null, null, null);
    }

    /**
     * Every time user creates a new thing, id and location of header should plus 1.
     * By doing this, EverythingDone can use old id/location of header as that of
     * the new thing created by user(like a new {@link Thing.NOTE})
     * or app itself(like a new {@link Thing.NOTIFY_EMPTY_NOTE}).
     */
    private void updateHeader(int addSize) {
        final String SQL = "update " + Def.Database.TABLE_THINGS
                + " set id=id+" + addSize + ",location=location+" + addSize
                + " where type=" + Thing.HEADER;
        try {
            db.execSQL(SQL);
        } catch (SQLiteConstraintException e) {
            e.printStackTrace();
            updateHeader(addSize);
        }
    }

    private void updateHeader(long newId) {
        final String SQL = "update " + Def.Database.TABLE_THINGS
                + " set id=" + newId + ",location=" + newId
                + " where type=" + Thing.HEADER;
        try {
            db.execSQL(SQL);
        } catch (SQLiteConstraintException e) {
            e.printStackTrace();
            updateHeader(newId);
        }
    }

    private void deleteNotifyEmpty(@Thing.Type int type, @Thing.State int state, boolean handleCurrentLimit) {
        int[] limits = Thing.getLimits(type, state);
        final int currentLimit = mLimit;
        ThingsCounts thingsCounts = ThingsCounts.getInstance(mContext);
        if (handleCurrentLimit) {
            for (int limit : limits) {
                int NEtype = Thing.getNotifyEmptyType(limit);
                Cursor cursor = db.query(Def.Database.TABLE_THINGS, null,
                        "type=" + type, null, null, null, null);
                if (cursor.getCount() != 0) {
                    db.delete(Def.Database.TABLE_THINGS, "type=" + NEtype, null);
                    thingsCounts.handleUpdate(NEtype, Thing.UNDERWAY, NEtype, Thing.DELETED_FOREVER, 1);
                }
                cursor.close();
            }
        } else {
            for (int limit : limits) {
                if (currentLimit != limit) {
                    int NEtype = Thing.getNotifyEmptyType(limit);
                    Cursor cursor = db.query(Def.Database.TABLE_THINGS, null,
                            "type=" + NEtype, null, null, null, null);
                    if (cursor.getCount() != 0) {
                        db.delete(Def.Database.TABLE_THINGS, "type=" + NEtype, null);
                        thingsCounts.handleUpdate(NEtype, Thing.UNDERWAY, NEtype, Thing.DELETED_FOREVER, 1);
                    }
                    cursor.close();
                }
            }
        }
    }

    private void createNotifyEmpty(@Thing.Type int type, @Thing.State int state, boolean handleCurrentLimit) {
        int[] limits = Thing.getLimits(type, state);
        final int currentLimit = mLimit;
        ThingsCounts thingsCounts = ThingsCounts.getInstance(mContext);
        Cursor cursor;
        Thing notifyEmpty;

        if (handleCurrentLimit) {
            for (int limit : limits) {
                cursor = getThingsCursorForDisplay(limit, null, 0);
                if (cursor.getCount() == 1) {
                    notifyEmpty = Thing.generateNotifyEmpty(limit, getHeaderId(), mContext);
                    create(notifyEmpty, false, false);
                    thingsCounts.handleCreation(notifyEmpty.getType());
                }
                cursor.close();
            }
        } else {
            for (int limit : limits) {
                if (currentLimit != limit) {
                    cursor = getThingsCursorForDisplay(limit, null, 0);
                    if (cursor.getCount() == 1) {
                        notifyEmpty = Thing.generateNotifyEmpty(limit, getHeaderId(), mContext);
                        create(notifyEmpty, false, false);
                        thingsCounts.handleCreation(notifyEmpty.getType());
                    }
                    cursor.close();
                }
            }
        }

    }
}
