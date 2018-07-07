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

public class Five extends Figure {

//    private static final float[][] POINTS = {
//            {0.806629834254144f, 0.110497237569061f},
//            {0.502762430939227f, 0.110497237569061f},
//            {0.502762430939227f, 0.110497237569061f},
//            {0.502762430939227f, 0.110497237569061f},
//            {0.397790055248619f, 0.430939226519337f},
//            {0.397790055248619f, 0.430939226519337f},
//            {0.397790055248619f, 0.430939226519337f},
//            {0.535911602209945f, 0.364640883977901f},
//            {0.801104972375691f, 0.469613259668508f},
//            {0.801104972375691f, 0.712707182320442f},
//            {0.773480662983425f, 1.01104972375691f },
//            {0.375690607734807f, 1.0939226519337f  },
//            {0.248618784530387f, 0.850828729281768f}
//    };

    private static final float[][] POINTS = {
            { 0.80f , 0.11f },
            { 0.42f , 0.11f },
            { 0.42f , 0.11f },
            { 0.42f , 0.11f },
            { 0.36f , 0.48f },
            { 0.36f , 0.48f },
            { 0.36f , 0.48f },
            { 0.54f , 0.40f },
            { 0.83f , 0.45f },
            { 0.84f , 0.72f },
            { 0.83f , 1.08f },
            { 0.37f , 1.14f },
            { 0.25f , 0.85f }
    };

    private static Five INSTANCE = new Five();

    protected Five() {
        super(POINTS);
    }

    public static Five getInstance() {
        return INSTANCE;
    }
}