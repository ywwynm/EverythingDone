package com.ywwynm.everythingdone.fragments;

import android.app.Activity;
import android.os.Bundle;
import android.support.v4.util.Pair;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.adapters.BaseViewHolder;

import java.util.List;

/**
 * Created by ywwynm on 2016/4/30.
 * attachment info dialog fragment
 */
public class AttachmentInfoDialogFragment extends BaseDialogFragment {

    public static final String TAG = "AttachmentInfoDialogFragment";

    private int mAccentColor;
    private List<Pair<String, String>> mItems;

    private LayoutInflater mInflater;

    @Override
    protected int getLayoutResource() {
        return R.layout.fragment_attachment_info;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);

        Activity activity = getActivity();
        mInflater = LayoutInflater.from(activity);

        TextView title = f(R.id.tv_title_attachment_info);
        title.setTextColor(mAccentColor);
        TextView confirm = f(R.id.tv_confirm_as_bt_attachment_info);
        confirm.setTextColor(mAccentColor);
        confirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });

        RecyclerView recyclerView = f(R.id.rv_attachment_info);
        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        recyclerView.setAdapter(new Adapter());

        return mContentView;
    }

    public void setAccentColor(int accentColor) {
        mAccentColor = accentColor;
    }

    public void setItems(List<Pair<String, String>> items) {
        mItems = items;
    }

    class Adapter extends RecyclerView.Adapter<Adapter.Holder> {

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new Holder(mInflater.inflate(R.layout.rv_attachment_info, parent, false));
        }

        @Override
        public void onBindViewHolder(Holder holder, int position) {
            Pair<String, String> item = mItems.get(position);
            holder.tvTitle.setText(item.first);
            holder.tvContent.setText(item.second);
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        class Holder extends BaseViewHolder {

            final TextView tvTitle;
            final TextView tvContent;

            Holder(View itemView) {
                super(itemView);

                tvTitle   = f(R.id.tv_rv_attachment_info_title);
                tvContent = f(R.id.tv_rv_attachment_info_content);
            }
        }

    }
}
