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

public class Four extends Figure {

//    private static final float[][] POINTS = {
//            { 0.856353591160221f, 0.806629834254144f },
//            { 0.856353591160221f, 0.806629834254144f },
//            { 0.237569060773481f, 0.806629834254144f },
//            { 0.237569060773481f, 0.806629834254144f },
//            { 0.237569060773481f, 0.806629834254144f },
//            { 0.712707182320442f, 0.138121546961326f },
//            { 0.712707182320442f, 0.138121546961326f },
//            { 0.712707182320442f, 0.138121546961326f },
//            { 0.712707182320442f, 0.806629834254144f },
//            { 0.712707182320442f, 0.806629834254144f },
//            { 0.712707182320442f, 0.806629834254144f },
//            { 0.712707182320442f, 0.988950276243094f },
//            { 0.712707182320442f, 0.988950276243094f }
//
//    };

//    private static final float[][] POINTS = {
//            { 0.90f, 0.84f },
//            { 0.90f, 0.84f },
//            { 0.23f, 0.84f },
//            { 0.23f, 0.84f },
//            { 0.23f, 0.84f },
//            { 0.74f, 0.12f },
//            { 0.74f, 0.12f },
//            { 0.74f, 0.12f },
//            { 0.74f, 0.84f },
//            { 0.74f, 0.84f },
//            { 0.74f, 0.84f },
//            { 0.74f, 1.00f },
//            { 0.74f, 1.00f }
//    };

    private static final float[][] POINTS = {
            { 0.90f, 0.84f },
            { 0.90f, 0.84f },
            { 0.23f, 0.84f },
            { 0.23f, 0.84f },
            { 0.23f, 0.84f },
            { 0.74f, 0.12f },
            { 0.74f, 0.12f },
            { 0.74f, 0.12f },
            { 0.74f, 0.84f },
            { 0.74f, 0.84f },
            { 0.74f, 0.84f },
            { 0.74f, 1.04f },
            { 0.74f, 1.04f }
    };

    private static Four INSTANCE = new Four();

    protected Four() {
        super(POINTS);
    }

    public static Four getInstance() {
        return INSTANCE;
    }
}