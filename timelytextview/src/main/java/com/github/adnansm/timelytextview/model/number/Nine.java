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

public class Nine extends Figure {

//    private static final float[][] POINTS = {
//            { 0.80939226519337f , 0.552486187845304f   },
//            { 0.685082872928177f, 0.751381215469613f   },
//            { 0.298342541436464f, 0.740331491712707f   },
//            { 0.259668508287293f, 0.408839779005525f   },
//            { 0.232044198895028f, 0.0441988950276243f  },
//            { 0.81767955801105f , -0.0441988950276243f },
//            { 0.850828729281768f, 0.408839779005525f   },
//            { 0.839779005524862f, 0.596685082872928f   },
//            { 0.712707182320442f, 0.668508287292818f   },
//            { 0.497237569060773f, 0.994475138121547f   },
//            { 0.497237569060773f, 0.994475138121547f   },
//            { 0.497237569060773f, 0.994475138121547f   },
//            { 0.497237569060773f, 0.994475138121547f   }
//    };

    private static final float[][] POINTS = {
            { 0.82f,  0.54f  },
            { 0.72f,  0.74f  },
            { 0.30f,  0.74f  },
            { 0.25f,  0.41f  },
            { 0.23f,  -0.04f },
            { 0.88f,  -0.04f },
            { 0.86f,  0.41f  },
            { 0.84f,  0.60f  },
            { 0.74f,  0.67f  },
            { 0.46f,  1.02f  },
            { 0.46f,  1.02f  },
            { 0.46f,  1.02f  },
            { 0.46f,  1.02f  }
    };

    private static Nine INSTANCE = new Nine();

    protected Nine() {
        super(POINTS);
    }

    public static Nine getInstance() {
        return INSTANCE;
    }
}