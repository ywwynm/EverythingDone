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

public class Three extends Figure {

//    private static final float[][] POINTS = {
//            { 0.361878453038674f, 0.298342541436464f  },
//            { 0.348066298342541f, 0.149171270718232f  },
//            { 0.475138121546961f, 0.0994475138121547f },
//            { 0.549723756906077f, 0.0994475138121547f },
//            { 0.861878453038674f, 0.0994475138121547f },
//            { 0.806629834254144f, 0.530386740331492f  },
//            { 0.549723756906077f, 0.530386740331492f  },
//            { 0.87292817679558f , 0.530386740331492f  },
//            { 0.828729281767956f, 0.994475138121547f  },
//            { 0.552486187845304f, 0.994475138121547f  },
//            { 0.298342541436464f, 0.994475138121547f  },
//            { 0.30939226519337f , 0.828729281767956f  },
//            { 0.312154696132597f, 0.790055248618785f  }
//    };

//    private static final float[][] POINTS = {
//            { 0.31f             , 0.27f },
//            { 0.30f             , 0.14f },
//            { 0.475138121546961f, 0.1f  },
//            { 0.549723756906077f, 0.1f  },
//            { 0.94f             , 0.1f  },
//            { 0.806629834254144f, 0.53f },
//            { 0.48f             , 0.53f },
//            { 0.92f             , 0.53f },
//            { 0.91f             , 1f    },
//            { 0.552486187845304f, 1f    },
//            { 0.298342541436464f, 1f    },
//            { 0.26f             , 0.86f },
//            { 0.28f             , 0.83f }
//    };

    private static final float[][] POINTS = {
            { 0.28f, 0.26f },
            { 0.30f, 0.10f },
            { 0.48f, 0.06f },
            { 0.55f, 0.06f },
            { 0.94f, 0.06f },
            { 0.81f, 0.53f },
            { 0.48f, 0.53f },
            { 0.92f, 0.53f },
            { 0.91f, 1.04f },
            { 0.55f, 1.04f },
            { 0.30f, 1.04f },
            { 0.26f, 0.86f },
            { 0.28f, 0.83f }
    };

    private static Three INSTANCE = new Three();

    protected Three() {
        super(POINTS);
    }

    public static Three getInstance() {
        return INSTANCE;
    }
}