package com.ywwynm.everythingdone.services;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.support.v4.content.ContextCompat;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StrikethroughSpan;
import android.util.TypedValue;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.helpers.CheckListHelper;
import com.ywwynm.everythingdone.managers.ThingManager;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.utils.DisplayUtil;

import java.util.List;

/**
 * Created by qiizhang on 2016/8/1.
 * adapter service for checklist in a thing
 */
public class ChecklistWidgetService extends RemoteViewsService {

    private static final int LL_CHECK_LIST = R.id.ll_check_list_tv;
    private static final int IV_STATE      = R.id.iv_check_list_state;
    private static final int TV_CONTENT    = R.id.tv_check_list;

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new ChecklistViewFactory(getApplicationContext(), intent);
    }

    class ChecklistViewFactory implements RemoteViewsFactory {

        private Context mContext;
        private Intent mIntent;

        private Thing mThing;
        private List<String> mItems;

        public ChecklistViewFactory(Context context, Intent intent) {
            mContext = context;
            mIntent  = intent;
        }

        @Override
        public void onCreate() {
            init();
        }

        private void init() {
            long id = mIntent.getLongExtra(Def.Communication.KEY_ID, -1);
            ThingManager thingManager = ThingManager.getInstance(mContext);
            mThing = thingManager.getThingById(id);
            if (mThing == null) {
                ThingDAO thingDAO = ThingDAO.getInstance(mContext);
                mThing = thingDAO.getThingById(id);
                if (mThing == null) {
                    return;
                }
            }

            mItems = CheckListHelper.toCheckListItems(mThing.getContent(), false);
            mItems.remove("2");
            mItems.remove("3");
            mItems.remove("4");
        }

        @Override
        public void onDataSetChanged() {
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
            RemoteViews rv = new RemoteViews(mContext.getPackageName(), R.layout.check_list_tv);

            int white_76 = ContextCompat.getColor(mContext, R.color.white_76p);
            int white_50 = Color.parseColor("#80FFFFFF");
            float density = DisplayUtil.getScreenDensity(mContext);

            rv.setViewPadding(LL_CHECK_LIST, (int) (-6 * density), 0, 0, 0);

            String stateContent = mItems.get(position);
            char state = stateContent.charAt(0);
            String text = stateContent.substring(1, stateContent.length());
            if (state == '0') {
                rv.setImageViewResource(IV_STATE, R.drawable.checklist_unchecked_card);
                rv.setTextColor(TV_CONTENT, white_76);
                rv.setTextViewText(TV_CONTENT, text);
            } else if (state == '1') {
                rv.setImageViewResource(IV_STATE, R.drawable.checklist_checked_card);
                rv.setTextColor(TV_CONTENT, white_50);
                SpannableString spannable = new SpannableString(text);
                spannable.setSpan(new StrikethroughSpan(), 0, text.length(),
                        Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
                rv.setTextViewText(TV_CONTENT, spannable);
            }

            int size = mItems.size();
            if (size >= 8) {
                rv.setTextViewTextSize(TV_CONTENT, TypedValue.COMPLEX_UNIT_SP, 14);
                rv.setViewPadding(TV_CONTENT, 0, (int) (density * 2), 0, 0);
            } else {
                float textSize = -4 * size / 7f + 130f / 7;
                rv.setTextViewTextSize(TV_CONTENT, TypedValue.COMPLEX_UNIT_SP, textSize);
                float mt = - 2 * textSize / 3 + 34f / 3;
                rv.setViewPadding(TV_CONTENT, 0, (int) mt, 0, 0);
            }

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
            return mItems.get(position).hashCode();
        }

        @Override
        public boolean hasStableIds() {
            return false;
        }
    }

}
