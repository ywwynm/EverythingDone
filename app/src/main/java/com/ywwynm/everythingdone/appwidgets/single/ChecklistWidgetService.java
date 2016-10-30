package com.ywwynm.everythingdone.appwidgets.single;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.helpers.CheckListHelper;
import com.ywwynm.everythingdone.managers.ThingManager;
import com.ywwynm.everythingdone.model.Thing;

import java.util.List;

/**
 * Created by qiizhang on 2016/8/1.
 * adapter service for checklist in a thing
 */
public class ChecklistWidgetService extends RemoteViewsService {

    public static final String TAG = "ChecklistWidgetService";

    private static final int LL_CHECK_LIST = R.id.ll_check_list_tv;

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new ChecklistViewFactory(getApplicationContext(), intent);
    }

    static class ChecklistViewFactory implements RemoteViewsFactory {

        private Context mContext;
        private Intent mIntent;

        private Thing mThing;
        private List<String> mItems;

        ChecklistViewFactory(Context context, Intent intent) {
            mContext = context;
            mIntent  = intent;
        }

        @Override
        public void onCreate() {
            init();
        }

        private void init() {
            Log.i(TAG, "init()");
            long id = mIntent.getLongExtra(Def.Communication.KEY_ID, -1);
            ThingManager thingManager = ThingManager.getInstance(mContext);
            mThing = thingManager.getThingById(id);
            if (mThing == null) {
                ThingDAO thingDAO = ThingDAO.getInstance(mContext);
                mThing = thingDAO.getThingById(id);
                if (mThing == null) {
                    Log.i(TAG, "thing is null!");
                    return;
                }
            }

            Log.i(TAG, "mThing.content[" + mThing.getContent() + "]");
            mItems = CheckListHelper.toCheckListItems(mThing.getContent(), false);
            mItems.remove("2");
            mItems.remove("3");
            mItems.remove("4");
        }

        @Override
        public void onDataSetChanged() {
            Log.i(TAG, "onDataSetChanged()");
            init();
        }

        @Override
        public void onDestroy() {

        }

        @Override
        public int getCount() {
            return mItems.size();
        }

        @Override
        public RemoteViews getViewAt(int position) {
            final int count = getCount();
            if (position < 0 || position >= count) {
                return null;
            }
            RemoteViews rv = AppWidgetHelper.createRemoteViewsForChecklistItem(
                    mContext, mItems.get(position), count, true);
            setupEvents(rv, position);
            return rv;
        }

        private void setupEvents(RemoteViews rv, int position) {
            if (mThing.getState() == Thing.UNDERWAY) {
                Intent intent = new Intent(Def.Communication.BROADCAST_ACTION_UPDATE_CHECKLIST);
                intent.putExtra(Def.Communication.KEY_ID, mThing.getId());
                intent.putExtra(Def.Communication.KEY_POSITION, position);
                rv.setOnClickFillInIntent(LL_CHECK_LIST, intent);
            }
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
            final int count = getCount();
            if (position < 0 || position >= count) {
                return -1L;
            }
            return mItems.get(position).hashCode();
        }

        @Override
        public boolean hasStableIds() {
            return false;
        }
    }

}
