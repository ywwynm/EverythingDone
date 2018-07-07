/*
Changed by ywwynm

Copyright 2014 Adnan A M.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */

package com.github.adnansm.timelytextview.model.number;

import com.github.adnansm.timelytextview.model.core.Figure;

public class Zero extends Figure {

//    private static final float[][] POINTS = {
//            { 0.24585635359116f , 0.552486187845304f  },
//            { 0.24585635359116f , 0.331491712707182f  },
//            { 0.370165745856354f, 0.0994475138121547f },
//            { 0.552486187845304f, 0.0994475138121547f },
//            { 0.734806629834254f, 0.0994475138121547f },
//            { 0.861878453038674f, 0.331491712707182f  },
//            { 0.861878453038674f, 0.552486187845304f  },
//            { 0.861878453038674f, 0.773480662983425f  },
//            { 0.734806629834254f, 0.994475138121547f  },
//            { 0.552486187845304f, 0.994475138121547f  },
//            { 0.370165745856354f, 0.994475138121547f  },
//            { 0.24585635359116f , 0.773480662983425f  },
//            { 0.24585635359116f , 0.552486187845304f  }
//    };

    private static final float[][] POINTS = {
            { 0.25f, 0.57f },
            { 0.25f, 0.35f },
            { 0.35f, 0.10f },
            { 0.55f, 0.10f },
            { 0.75f, 0.10f },
            { 0.86f, 0.35f },
            { 0.86f, 0.57f },
            { 0.86f, 0.79f },
            { 0.75f, 1.02f },
            { 0.55f, 1.02f },
            { 0.35f, 1.02f },
            { 0.25f, 0.79f },
            { 0.25f, 0.57f }
    };

    private static Zero INSTANCE = new Zero();

    protected Zero() {
        super(POINTS);
    }

    public static Zero getInstance() {
        return INSTANCE;
    }
}