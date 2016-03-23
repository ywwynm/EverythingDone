package com.ywwynm.everythingdone.adapters;

import android.content.Context;
import android.support.v7.widget.AppCompatRadioButton;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.ywwynm.everythingdone.R;

import java.util.List;

/**
 * Created by ywwynm on 2016/3/12.
 * RecyclerView adapter for chooser fragment.
 */
public class ChooserFragmentAdapter extends SingleChoiceAdapter {

    public static final String TAG = "ChooserFragmentAdapter";

    private LayoutInflater mInflater;
    private List<String> mItems;

    private View.OnClickListener mOnItemClickListener;

    public ChooserFragmentAdapter(Context context, List<String> items) {
        mInflater = LayoutInflater.from(context);
        mItems = items;
    }

    public void setOnItemClickListener(View.OnClickListener onItemClickListener) {
        mOnItemClickListener = onItemClickListener;
    }

    @Override
    public void pick(int position) {
        notifyItemChanged(mPickedPosition);
        mPickedPosition = position;
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        return new ChoiceHolder(mInflater.inflate(R.layout.rv_fragment_chooser, parent, false));
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder viewHolder, int position) {
        ChoiceHolder holder = (ChoiceHolder) viewHolder;
        holder.rb.setText(mItems.get(position));
        holder.rb.setChecked(mPickedPosition == position);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    class ChoiceHolder extends RecyclerView.ViewHolder {

        final AppCompatRadioButton rb;

        public ChoiceHolder(View itemView) {
            super(itemView);

            rb = (AppCompatRadioButton) itemView.findViewById(R.id.rb_rv_chooser_fragment);
            rb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pick(getAdapterPosition());
                    if (mOnItemClickListener != null) {
                        mOnItemClickListener.onClick(v);
                    }
                }
            });
        }
    }
}
