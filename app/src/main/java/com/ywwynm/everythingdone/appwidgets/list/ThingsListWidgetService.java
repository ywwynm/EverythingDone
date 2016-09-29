package com.ywwynm.everythingdone.appwidgets.list;

import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.managers.ThingManager;
import com.ywwynm.everythingdone.model.Thing;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by qiizhang on 2016/8/8.
 * adapter service for things list
 */
public class ThingsListWidgetService extends RemoteViewsService {

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new ThingsListViewFactory(getApplicationContext(), intent);
    }

    static class ThingsListViewFactory implements RemoteViewsFactory {

        private Context mContext;
        private Intent mIntent;

        private int mAppWidgetId;

        private List<Thing> mThings;

        ThingsListViewFactory(Context context, Intent intent) {
            mContext = context;
            mIntent = intent;
        }

        @Override
        public void onCreate() {
            init();
        }

        private void init() {
            int limit = mIntent.getIntExtra(Def.Communication.KEY_LIMIT, 0);
            List<Thing> things;
            if (limit == App.getApp().getLimit() && !App.isSearching) {
                ThingManager thingManager = ThingManager.getInstance(mContext);
                things = thingManager.getThings();
            } else {
                ThingDAO thingDAO = ThingDAO.getInstance(mContext);
                things = thingDAO.getThingsForDisplay(limit);
            }

            mThings = new ArrayList<>();
            for (Thing thing : things) {
                if (thing.getType() != Thing.HEADER) {
                    mThings.add(new Thing(thing));
                }
            }

            mAppWidgetId = mIntent.getIntExtra(Def.Communication.KEY_WIDGET_ID, 0);
        }

        @Override
        public void onDataSetChanged() {
            init();
        }

        @Override
        public void onDestroy() { }

        @Override
        public int getCount() {
            return mThings.size();
        }

        @Override
        public RemoteViews getViewAt(int position) {
            if (position < -1 || position >= getCount()) {
                return null;
            }
            Thing thing = mThings.get(position);
            RemoteViews rv = AppWidgetHelper.createRemoteViewsForThingsListItem(
                    mContext, thing, mAppWidgetId);
            Intent intent = new Intent();
            intent.putExtra(Def.Communication.KEY_ID, thing.getId());
            intent.putExtra(Def.Communication.KEY_POSITION, position + 1);
            rv.setOnClickFillInIntent(R.id.root_widget_thing, intent);
            return rv;
        }

        @Override
        public RemoteViews getLoadingView() {
            return null;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public long getItemId(int position) {
            if (position < 0 || position > mThings.size() - 1) {
                return -1L;
            }
            return mThings.get(position).getId();
        }

        @Override
        public boolean hasStableIds() {
            return false;
        }
    }
}
