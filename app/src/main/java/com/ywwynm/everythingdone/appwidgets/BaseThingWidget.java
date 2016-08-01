package com.ywwynm.everythingdone.appwidgets;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.support.v4.util.Pair;

import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.database.AppWidgetDAO;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.helpers.AppWidgetHelper;
import com.ywwynm.everythingdone.helpers.CheckListHelper;
import com.ywwynm.everythingdone.managers.ThingManager;
import com.ywwynm.everythingdone.model.Thing;

import java.util.List;

/**
 * Created by qiizhang on 2016/8/1.
 * basic single thing widget
 */
public class BaseThingWidget extends AppWidgetProvider {

    public static final String ACTION_UPDATE_CHECKLIST
            = "com.ywwynm.everythingdone.action.update_checklist";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_UPDATE_CHECKLIST.equals(intent.getAction())) {
            long id = intent.getLongExtra(Def.Communication.KEY_ID, -1);
            ThingManager thingManager = ThingManager.getInstance(context);
            Thing thing = thingManager.getThingById(id);
            if (thing == null) {
                ThingDAO thingDAO = ThingDAO.getInstance(context);
                thing = thingDAO.getThingById(id);
                if (thing == null) {
                    return;
                }
            }

            int position = intent.getIntExtra(Def.Communication.KEY_POSITION, 0);
            String updatedContent = getUpdatedContent(thing.getContent(), position);
            thing.setContent(updatedContent);
            updateThing(context, thing);
        }
        super.onReceive(context, intent);
    }

    private String getUpdatedContent(String content, int position) {
        List<String> items = CheckListHelper.toCheckListItems(content, false);
        items.remove("2");
        items.remove("3");
        items.remove("4");
        String oldItem = items.get(position);
        items.remove(position);
        if (oldItem.startsWith("0")) { // unfinished to finished
            String newItem = "1" + oldItem.substring(1, oldItem.length());
            int firstFinishedIndex = CheckListHelper.getFirstFinishedItemIndex(items);
            if (firstFinishedIndex == -1) {
                items.add(newItem);
            } else {
                items.add(firstFinishedIndex, newItem);
            }
        } else {
            String newItem = "0" + oldItem.substring(1, oldItem.length());
            items.add(0, newItem);
        }
        return CheckListHelper.toContentStr(items);
    }

    private void updateThing(Context context, Thing updatedThing) {
        ThingManager thingManager = ThingManager.getInstance(context);
        int position = thingManager.getPosition(updatedThing);
        if (position != -1) {
            thingManager.update(updatedThing.getType(), updatedThing, position, false);
        } else {
            ThingDAO thingDAO = ThingDAO.getInstance(context);
            thingDAO.update(updatedThing.getType(), updatedThing, false, true);
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        ThingManager thingManager = ThingManager.getInstance(context);
        ThingDAO thingDAO = ThingDAO.getInstance(context);
        AppWidgetDAO appWidgetDAO = AppWidgetDAO.getInstance(context);
        for (int appWidgetId : appWidgetIds) {
            long id = appWidgetDAO.getThingIdByAppWidgetId(appWidgetId);
            Pair<Integer, Thing> pair = getThingAndPositionFromManager(thingManager, id);
            int position;
            Thing thing;
            if (pair == null) {
                position = -1;
                thing = thingDAO.getThingById(id);
                if (thing == null) {
                    return;
                }
            } else {
                position = pair.first;
                thing = pair.second;
            }
            appWidgetManager.updateAppWidget(appWidgetId,
                    AppWidgetHelper.createRemoteViewsForSingleThing(context, thing, position, appWidgetId));
        }
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        super.onDeleted(context, appWidgetIds);
        AppWidgetDAO appWidgetDAO = AppWidgetDAO.getInstance(context);
        for (int appWidgetId : appWidgetIds) {
            appWidgetDAO.delete(appWidgetId);
        }
    }

    private Pair<Integer, Thing> getThingAndPositionFromManager(ThingManager thingManager, long thingId) {
        Pair<Integer, Thing> pair = null;
        List<Thing> things = thingManager.getThings();
        final int size = things.size();
        for (int i = 0; i < size; i++) {
            Thing thing = things.get(i);
            if (thing.getId() == thingId) {
                pair = new Pair<>(i, thing);
            }
        }
        return pair;
    }

}
