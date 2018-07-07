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

public class Two extends Figure {

//    private static final float[][] POINTS = {
//            { 0.30939226519337f , 0.331491712707182f  },
//            { 0.325966850828729f, 0.0110497237569061f },
//            { 0.790055248618785f, 0.0220994475138122f },
//            { 0.798342541436464f, 0.337016574585635f  },
//            { 0.798342541436464f, 0.430939226519337f  },
//            { 0.718232044198895f, 0.541436464088398f  },
//            { 0.596685082872928f, 0.674033149171271f  },
//            { 0.519337016574586f, 0.762430939226519f  },
//            { 0.408839779005525f, 0.856353591160221f  },
//            { 0.314917127071823f, 0.977900552486188f  },
//            { 0.314917127071823f, 0.977900552486188f  },
//            { 0.812154696132597f, 0.977900552486188f  },
//            { 0.812154696132597f, 0.977900552486188f  }
//    };

//    private static final float[][] POINTS = {
//            { 0.30939226519337f , 0.331491712707182f  },
//            { 0.325966850828729f, 0.0110497237569061f },
//            { 0.790055248618785f, 0.0220994475138122f },
//            { 0.798342541436464f, 0.337016574585635f  },
//            { 0.798342541436464f, 0.430939226519337f  },
//            { 0.718232044198895f, 0.541436464088398f  },
//            { 0.596685082872928f, 0.674033149171271f  },
//            { 0.519337016574586f, 0.762430939226519f  },
//            { 0.408839779005525f, 0.856353591160221f  },
//            { 0.314917127071823f, 0.977900552486188f  },
//            { 0.314917127071823f, 0.977900552486188f  },
//            { 0.84f             , 0.977900552486188f  },
//            { 0.84f             , 0.977900552486188f  }
//    };

    private static final float[][] POINTS = {
            { 0.29f, 0.33f },
            { 0.33f, 0.01f },
            { 0.79f, 0.02f },
            { 0.80f, 0.34f },
            { 0.80f, 0.43f },
            { 0.72f, 0.54f },
            { 0.60f, 0.67f },
            { 0.52f, 0.76f },
            { 0.41f, 0.86f },
            { 0.28f, 1.02f },
            { 0.28f, 1.02f },
            { 0.84f, 1.02f },
            { 0.84f, 1.02f }
    };

    private static Two INSTANCE = new Two();

    protected Two() {
        super(POINTS);
    }

    public static Two getInstance() {
        return INSTANCE;
    }
}