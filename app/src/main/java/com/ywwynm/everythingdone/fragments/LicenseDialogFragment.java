package com.ywwynm.everythingdone.fragments;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.adapters.BaseViewHolder;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.utils.EdgeEffectUtil;

import java.util.Arrays;
import java.util.List;

/**
 * Created by ywwynm on 2017/3/11.
 * DialogFragment used to show licenses.
 */
public class LicenseDialogFragment extends BaseDialogFragment {

    public static final String TAG = "LicensesDialogFragment";

    private Activity mActivity;
    private int mAccentColor;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);

        mActivity = getActivity();

        mAccentColor = DisplayUtil.getRandomColor(mActivity);
        TextView tvTitle = f(R.id.tv_title_license);
        tvTitle.setTextColor(mAccentColor);
        TextView tvGetIt = f(R.id.tv_get_it_as_bt_license);
        tvGetIt.setTextColor(mAccentColor);
        tvGetIt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });

        final View vTop    = f(R.id.view_separator_1);
        final View vBottom = f(R.id.view_separator_2);
        RecyclerView rv    = f(R.id.rv_licenses);

        rv.setAdapter(new LicenseAdapter(getLicenses()));
        rv.setLayoutManager(new LinearLayoutManager(mActivity));
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (!recyclerView.canScrollVertically(-1)) {
                    vTop.setVisibility(View.INVISIBLE);
                    vBottom.setVisibility(View.VISIBLE);
                } else if (!recyclerView.canScrollVertically(1)) {
                    vTop.setVisibility(View.VISIBLE);
                    vBottom.setVisibility(View.INVISIBLE);
                } else {
                    vTop.setVisibility(View.VISIBLE);
                    vBottom.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                EdgeEffectUtil.forRecyclerView(recyclerView, mAccentColor);
            }
        });

        return mContentView;
    }

    private List<License> getLicenses() {
        License[] licenses = {
                new License(
                        "Android Open Source Project",
                        "https://source.android.com/",
                        "Copyright (C) 2017 The Android Open Source Project",
                        License.TYPE_APACHE_V2
                ),
                new License(
                        "Android Support Library",
                        "https://developer.android.com/topic/libraries/support-library/index.html",
                        "Copyright (C) 2017 The Android Open Source Project",
                        License.TYPE_APACHE_V2
                ),
                new License(
                        "RevealLayout",
                        "https://github.com/kyze8439690/RevealLayout",
                        "",
                        License.TYPE_APACHE_V2
                ),
                new License(
                        "Voice Recording Visualizer",
                        "https://github.com/tyorikan/voice-recording-visualizer",
                        "Copyright 2015 tyorikan",
                        License.TYPE_APACHE_V2
                ),
                new License(
                        "Joda-Time",
                        "http://www.joda.org/joda-time/",
                        "Copyright 2001-2017 Stephen Colebourne",
                        License.TYPE_APACHE_V2
                ),
                new License(
                        "PhotoView",
                        "https://github.com/chrisbanes/PhotoView",
                        "Copyright 2016 Chris Banes",
                        License.TYPE_APACHE_V2
                ),
                new License(
                        "Swirl",
                        "https://github.com/mattprecious/swirl",
                        "Copyright 2016 Matthew Precious",
                        License.TYPE_APACHE_V2
                ),
                new License(
                        "Material Pattern LockView",
                        "https://github.com/AmniX/MaterialPatternllockView",
                        "Copyright 2015 AmniX",
                        License.TYPE_APACHE_V2
                ),
                new License(
                        "TimelyTextView",
                        "https://github.com/adnan-SM/TimelyTextView",
                        "Copyright 2014 Adnan A M.",
                        License.TYPE_APACHE_V2
                ),
                new License(
                        "Blurry",
                        "https://github.com/wasabeef/Blurry",
                        "Copyright 2015 Wasabeef",
                        License.TYPE_APACHE_V2
                ),
                new License(
                        "Glide",
                        "https://github.com/bumptech/glide",
                        "",
                        License.TYPE_GLIDE
                )
        };
        return Arrays.asList(licenses);
    }

    @Override
    protected int getLayoutResource() {
        return R.layout.fragment_license;
    }

    private class LicenseAdapter extends RecyclerView.Adapter<LicenseAdapter.Holder> {

        private LayoutInflater mInflater;
        private List<License> mItems;
        private int mLinkColor;

        LicenseAdapter(List<License> items) {
            mInflater = mActivity.getLayoutInflater();
            mItems = items;
            mLinkColor = DisplayUtil.getTransparentColor(mAccentColor, 160);
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new Holder(mInflater.inflate(R.layout.rv_license, parent, false));
        }

        @SuppressLint("SetTextI18n")
        @Override
        public void onBindViewHolder(Holder holder, int position) {
            License license = mItems.get(position);
            holder.tvName.setText(license.name);
            holder.tvLink.setText(license.link);
            holder.tvLink.setLinkTextColor(mLinkColor);
            String copyRight = license.copyRight;
            if (!copyRight.isEmpty()) {
                copyRight += "\n\n";
            }
            holder.tvContent.setText(copyRight + license.getContent());
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        class Holder extends BaseViewHolder {

            TextView tvName;
            TextView tvLink;
            TextView tvContent;

            Holder(View itemView) {
                super(itemView);
                tvName    = f(R.id.tv_license_name);
                tvLink    = f(R.id.tv_license_link);
                tvContent = f(R.id.tv_license_content);
            }
        }

    }

    private class License {

        static final int TYPE_APACHE_V2 = 0;

        static final int TYPE_GLIDE = 1996;

        String name;
        String link;
        String copyRight;
        int type;

        License(String name, String link, String copyRight, int type) {
            this.name = name;
            this.link = link;
            this.copyRight = copyRight;
            this.type = type;
        }

        String getContent() {
            if (type == TYPE_APACHE_V2) {
                return apacheV2Content();
            } else if (type == TYPE_GLIDE) {
                return glideContent();
            }
            return "";
        }

        private String apacheV2Content() {
            return
                "Licensed under the Apache License, Version 2.0 (the \"License\");\n" +
                "you may not use this file except in compliance with the License.\n" +
                "You may obtain a copy of the License at\n" +
                "\n" +
                "   http://www.apache.org/licenses/LICENSE-2.0\n" +
                "\n" +
                "Unless required by applicable law or agreed to in writing, software " +
                "distributed under the License is distributed on an \"AS IS\" BASIS," +
                "WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n" +
                "See the License for the specific language governing permissions and " +
                "limitations under the License.";
        }

        private String glideContent() {
            return
                "License for everything not in third_party and not otherwise marked:\n" +
                "\n" +
                "Copyright 2014 Google, Inc. All rights reserved.\n" +
                "\n" +
                "Redistribution and use in source and binary forms, with or without modification, are " +
                "permitted provided that the following conditions are met:\n" +
                "\n" +
                "1. Redistributions of source code must retain the above copyright notice, this list of" +
                "conditions and the following disclaimer.\n" +
                "\n" +
                "2. Redistributions in binary form must reproduce the above copyright notice, this list " +
                "of conditions and the following disclaimer in the documentation and/or other materials " +
                "provided with the distribution.\n" +
                "\n" +
                "THIS SOFTWARE IS PROVIDED BY GOOGLE, INC. ``AS IS'' AND ANY EXPRESS OR IMPLIED " +
                "WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND " +
                "FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL GOOGLE, INC. OR " +
                "CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR " +
                "CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR " +
                "SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON " +
                "ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING " +
                "NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF " +
                "ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.\n" +
                "\n" +
                "The views and conclusions contained in the software and documentation are those of the " +
                "authors and should not be interpreted as representing official policies, either expressed " +
                "or implied, of Google, Inc.\n" +
                "---------------------------------------------------------------------\n" +
                "License for third_party/disklrucache:\n" +
                "\n" +
                "Copyright 2012 Jake Wharton\n" +
                "Copyright 2011 The Android Open Source Project\n" +
                "\n" +
                "Licensed under the Apache License, Version 2.0 (the \"License\");\n" +
                "you may not use this file except in compliance with the License.\n" +
                "You may obtain a copy of the License at\n" +
                "\n" +
                "   http://www.apache.org/licenses/LICENSE-2.0\n" +
                "\n" +
                "Unless required by applicable law or agreed to in writing, software " +
                "distributed under the License is distributed on an \"AS IS\" BASIS, " +
                "WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n" +
                "See the License for the specific language governing permissions and " +
                "limitations under the License.\n" +
                "---------------------------------------------------------------------\n" +
                "License for third_party/gif_decoder:\n" +
                "\n" +
                "Copyright (c) 2013 Xcellent Creations, Inc.\n" +
                "\n" +
                "Permission is hereby granted, free of charge, to any person obtaining " +
                "a copy of this software and associated documentation files (the " +
                "\"Software\"), to deal in the Software without restriction, including " +
                "without limitation the rights to use, copy, modify, merge, publish, " +
                "distribute, sublicense, and/or sell copies of the Software, and to " +
                "permit persons to whom the Software is furnished to do so, subject to " +
                "the following conditions:\n" +
                "\n" +
                "The above copyright notice and this permission notice shall be " +
                "included in all copies or substantial portions of the Software.\n" +
                "\n" +
                "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, " +
                "EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF " +
                "MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND " +
                "NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE " +
                "LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION " +
                "OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION " +
                "WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.\n" +
                "---------------------------------------------------------------------\n" +
                "License for third_party/gif_encoder/AnimatedGifEncoder.java and\n" +
                "third_party/gif_encoder/LZWEncoder.java:\n" +
                "\n" +
                "No copyright asserted on the source code of this class. May be used for any " +
                "purpose, however, refer to the Unisys LZW patent for restrictions on use of " +
                "the associated LZWEncoder class. Please forward any corrections to\n" +
                "kweiner@fmsware.com.\n" +
                "\n" +
                "---------------------------------------------------------------------\n" +
                "License for third_party/gif_encoder/NeuQuant.java\n" +
                "\n" +
                "Copyright (c) 1994 Anthony Dekker\n" +
                "\n" +
                "NEUQUANT Neural-Net quantization algorithm by Anthony Dekker, 1994. See " +
                "\"Kohonen neural networks for optimal colour quantization\" in \"Network: " +
                "Computation in Neural Systems\" Vol. 5 (1994) pp 351-367. for a discussion of " +
                "the algorithm.\n" +
                "\n" +
                "Any party obtaining a copy of these files from the author, directly or " +
                "indirectly, is granted, free of charge, a full and unrestricted irrevocable, " +
                "world-wide, paid up, royalty-free, nonexclusive right and license to deal in " +
                "this software and documentation files (the \"Software\"), including without " +
                "limitation the rights to use, copy, modify, merge, publish, distribute, " +
                "sublicense, and/or sell copies of the Software, and to permit persons who " +
                "receive copies from any such party to do so, with the only requirement being " +
                "that this copyright notice remain intact.";
        }
    }
}
