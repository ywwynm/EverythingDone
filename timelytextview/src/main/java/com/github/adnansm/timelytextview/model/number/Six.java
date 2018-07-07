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

public class Six extends Figure {

//    private static final float[][] POINTS = {
//            { 0.607734806629834f, 0.110497237569061f },
//            { 0.607734806629834f, 0.110497237569061f },
//            { 0.607734806629834f, 0.110497237569061f },
//            { 0.607734806629834f, 0.110497237569061f },
//            { 0.392265193370166f, 0.43646408839779f  },
//            { 0.265193370165746f, 0.50828729281768f  },
//            { 0.25414364640884f , 0.696132596685083f },
//            { 0.287292817679558f, 1.13017127071823f  },
//            { 0.87292817679558f , 1.06077348066298f  },
//            { 0.845303867403315f, 0.696132596685083f },
//            { 0.806629834254144f, 0.364640883977901f },
//            { 0.419889502762431f, 0.353591160220994f },
//            { 0.295580110497238f, 0.552486187845304f }
//    };

    private static final float[][] POINTS = {
            { 0.66f, 0.10f },
            { 0.66f, 0.10f },
            { 0.66f, 0.10f },
            { 0.66f, 0.10f },
            { 0.40f, 0.48f },
            { 0.30f, 0.51f },
            { 0.27f, 0.74f },
            { 0.27f, 1.14f },
            { 0.89f, 1.14f },
            { 0.88f, 0.74f },
            { 0.87f, 0.38f },
            { 0.46f, 0.38f },
            { 0.33f, 0.56f }
    };

    private static Six INSTANCE = new Six();

    protected Six() {
        super(POINTS);
    }

    public static Six getInstance() {
        return INSTANCE;
    }
}